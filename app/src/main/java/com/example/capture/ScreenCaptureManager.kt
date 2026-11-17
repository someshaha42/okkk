package com.example.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.DataSpace
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.example.color.ChromaSubsampling
import com.example.color.ColorPipelinePlan
import com.example.color.ColorPipelinePlanner
import com.example.color.ColorRangeMode
import com.example.color.FrameShaderColorPlan
import com.example.color.PixelFormatMode
import com.example.color.ShaderColorTransform
import com.example.compat.PlatformCompat
import com.example.region.PixelRegion
import com.example.settings.InternalRecordingConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages MediaProjection, VirtualDisplay, dynamic display resize handling, and the OpenGL ES
 * 2.0/3.0 GPU surface-to-surface pipeline for Full Screen capture and pixel-exact Region cropping
 * within the user's selected Portrait or Landscape orientation without CPU frame copies.
 */
class ScreenCaptureManager(
  private val context: Context,
  private val mediaProjection: MediaProjection,
  private val outputSurface: Surface,
  private val outputWidth: Int,
  private val outputHeight: Int,
  private val cropRegion: PixelRegion?,
  private val colorPlan: ColorPipelinePlan = DEFAULT_COLOR_PLAN,
  private val onProjectionStopped: () -> Unit,
) {
  private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
  private val isReleased = AtomicBoolean(false)

  private var glThread: HandlerThread? = null
  private var glHandler: Handler? = null
  private var glPipeline: GlVideoPipeline? = null
  private var virtualDisplay: VirtualDisplay? = null

  private var currentCaptureWidth = 0
  private var currentCaptureHeight = 0
  private var currentDensityDpi = DisplayMetrics.DENSITY_DEFAULT

  private val projectionCallback =
    object : MediaProjection.Callback() {
      override fun onStop() {
        Log.i(TAG, "MediaProjection stopped by system or user")
        if (!isReleased.get()) {
          onProjectionStopped()
        }
      }

      override fun onCapturedContentResize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || isReleased.get()) return
        Log.d(TAG, "onCapturedContentResize: ${width}x$height")
        resizeCapture(width, height)
      }

      override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        Log.d(TAG, "onCapturedContentVisibilityChanged: isVisible=$isVisible")
      }
    }

  private val displayListener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) = Unit

      override fun onDisplayRemoved(displayId: Int) = Unit

      override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY || isReleased.get()) return
        val (w, h, dpi) = queryScreenMetrics(context)
        if (w != currentCaptureWidth || h != currentCaptureHeight) {
          Log.d(TAG, "Display rotated/changed: ${w}x$h")
          resizeCapture(w, h, dpi)
        }
      }
    }

  fun start() {
    val (w, h, dpi) = queryScreenMetrics(context)
    currentCaptureWidth = w
    currentCaptureHeight = h
    currentDensityDpi = dpi

    val thread = HandlerThread("FrameCut-GLPipeline").apply { start() }
    glThread = thread
    val handler = Handler(thread.looper)
    glHandler = handler

    val readyLatch = CountDownLatch(1)
    var initError: Throwable? = null
    var inputSurface: Surface? = null

    handler.post {
      try {
        val pipeline =
          GlVideoPipeline(
            encoderSurface = outputSurface,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            initialCaptureWidth = w,
            initialCaptureHeight = h,
            cropRegion = cropRegion,
            colorPlan = colorPlan,
          )
        pipeline.initGl()
        glPipeline = pipeline
        inputSurface = pipeline.captureSurface
      } catch (t: Throwable) {
        initError = t
      } finally {
        readyLatch.countDown()
      }
    }

    readyLatch.await(3, TimeUnit.SECONDS)
    initError?.let { throw it }

    val surface =
      inputSurface ?: throw IllegalStateException("Failed to initialize GL capture surface")

    mediaProjection.registerCallback(projectionCallback, handler)
    displayManager.registerDisplayListener(displayListener, handler)

    virtualDisplay =
      mediaProjection.createVirtualDisplay(
        "FrameCut-VirtualDisplay",
        w,
        h,
        dpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        surface,
        null,
        handler,
      )
  }

  fun setPaused(paused: Boolean) {
    glHandler?.post { glPipeline?.setPaused(paused) }
  }

  private fun resizeCapture(newW: Int, newH: Int, newDpi: Int = currentDensityDpi) {
    val evenW = if (newW % 2 == 0) newW else (newW - 1).coerceAtLeast(2)
    val evenH = if (newH % 2 == 0) newH else (newH - 1).coerceAtLeast(2)
    if (evenW == currentCaptureWidth && evenH == currentCaptureHeight) return

    currentCaptureWidth = evenW
    currentCaptureHeight = evenH
    currentDensityDpi = newDpi

    glHandler?.post {
      glPipeline?.updateCaptureDimensions(evenW, evenH)
      runCatching { virtualDisplay?.resize(evenW, evenH, newDpi) }
    }
  }

  fun stopAndRelease() {
    if (isReleased.getAndSet(true)) return

    runCatching { displayManager.unregisterDisplayListener(displayListener) }
    runCatching { mediaProjection.unregisterCallback(projectionCallback) }
    runCatching { virtualDisplay?.release() }
    virtualDisplay = null

    val releaseLatch = CountDownLatch(1)
    glHandler?.post {
      runCatching { glPipeline?.release() }
      glPipeline = null
      releaseLatch.countDown()
    }
    releaseLatch.await(1500, TimeUnit.MILLISECONDS)

    glThread?.quitSafely()
    runCatching { glThread?.join(1000) }
    glThread = null
    glHandler = null

    runCatching { mediaProjection.stop() }
  }

  companion object {
    private const val TAG = "ScreenCaptureManager"

    val DEFAULT_COLOR_PLAN =
      ColorPipelinePlan(
        hevcProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        hevcLevel = null,
        pixelFormatMode = PixelFormatMode.RGBA_8888,
        expectedChroma = ChromaSubsampling.YUV_420,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorRange = ColorRangeMode.LIMITED,
        useDisplayP3PassthroughEgl = false,
        useQpBounds = false,
      )

    fun queryScreenMetrics(context: Context): Triple<Int, Int, Int> {
      return PlatformCompat.queryMaximumDisplayMetrics(context)
    }
  }
}

/**
 * High-precision OpenGL ES 2.0/3.0 OES-to-RGBA surface pipeline. Supports 10-bit RGBA_1010102 and
 * 8-bit RGBA_8888 EGL surfaces, Display P3 passthrough tagging, per-frame SurfaceTexture DataSpace
 * colorimetry inspection with accurate linear gamut transforms, GL_NEAREST 1:1 unscaled sampling,
 * and disabled dithering to preserve original screen colors cleanly.
 */
private class GlVideoPipeline(
  private val encoderSurface: Surface,
  private val outputWidth: Int,
  private val outputHeight: Int,
  initialCaptureWidth: Int,
  initialCaptureHeight: Int,
  private val cropRegion: PixelRegion?,
  private val colorPlan: ColorPipelinePlan,
) : SurfaceTexture.OnFrameAvailableListener {

  private val minFrameIntervalNs =
    ((1_000_000_000L / InternalRecordingConfig.TARGET_FPS.coerceIn(1, 240)) * 85L) / 100L
  private var lastSubmittedPtsNs = -1L

  private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

  private var programId = 0
  private var textureId = 0
  private var positionHandle = 0
  private var texCoordHandle = 0
  private var texMatrixHandle = 0
  private var transferModeHandle = 0
  private var colorMatrixHandle = 0

  private var surfaceTexture: SurfaceTexture? = null
  var captureSurface: Surface? = null
    private set

  private var captureWidth = initialCaptureWidth
  private var captureHeight = initialCaptureHeight
  private var isUnscaledDimensions = false
  private var currentTexFilter = GLES20.GL_LINEAR

  private var lastFrameDataSpace = Int.MIN_VALUE
  private var currentShaderPlan =
    FrameShaderColorPlan(
      ShaderColorTransform.PASSTHROUGH,
      ColorPipelinePlanner.IDENTITY_MATRIX_COL_MAJOR,
    )

  private val stMatrix = FloatArray(16)
  private val vertexBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
  private val texCoordBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

  private var isPaused = false
  private var firstFrameNs = 0L
  private var pauseStartNs = 0L
  private var pausedAccumulatedNs = 0L
  private var released = false

  fun initGl() {
    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }
    val version = IntArray(2)
    check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "Unable to initialize EGL14" }

    val initializedTenBit =
      colorPlan.isTenBit &&
        tryCreateEglContextAndSurface(
          tenBit = true,
          useP3Passthrough = colorPlan.useDisplayP3PassthroughEgl,
        )

    if (!initializedTenBit) {
      if (colorPlan.isTenBit) {
        Log.w(TAG, "10-bit EGL init failed on device GPU; falling back to 8-bit RGBA_8888")
      }
      val initializedEightBit =
        tryCreateEglContextAndSurface(
          tenBit = false,
          useP3Passthrough = colorPlan.useDisplayP3PassthroughEgl,
        )
      check(initializedEightBit) { "Unable to initialize 8-bit RGBA_8888 EGL pipeline" }
    }

    // Disable 8-bit spatial dithering so gradients and flat UI colors stay faithful.
    GLES20.glDisable(GLES20.GL_DITHER)

    programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
    texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
    texMatrixHandle = GLES20.glGetUniformLocation(programId, "uTexMatrix")
    transferModeHandle = GLES20.glGetUniformLocation(programId, "uTransferMode")
    colorMatrixHandle = GLES20.glGetUniformLocation(programId, "uColorMatrix")

    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    textureId = textures[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MIN_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MAG_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_S,
      GLES20.GL_CLAMP_TO_EDGE,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_T,
      GLES20.GL_CLAMP_TO_EDGE,
    )

    surfaceTexture =
      SurfaceTexture(textureId).apply {
        setDefaultBufferSize(captureWidth, captureHeight)
        setOnFrameAvailableListener(this@GlVideoPipeline)
      }
    captureSurface = Surface(surfaceTexture)
    recomputeGeometry()
  }

  private fun tryCreateEglContextAndSurface(tenBit: Boolean, useP3Passthrough: Boolean): Boolean {
    val attribList =
      if (tenBit) {
        intArrayOf(
          EGL14.EGL_RED_SIZE, 10,
          EGL14.EGL_GREEN_SIZE, 10,
          EGL14.EGL_BLUE_SIZE, 10,
          EGL14.EGL_ALPHA_SIZE, 2,
          EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
          EGL_RECORDABLE_ANDROID, 1,
          EGL14.EGL_NONE,
        )
      } else {
        intArrayOf(
          EGL14.EGL_RED_SIZE, 8,
          EGL14.EGL_GREEN_SIZE, 8,
          EGL14.EGL_BLUE_SIZE, 8,
          EGL14.EGL_ALPHA_SIZE, 8,
          EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
          EGL_RECORDABLE_ANDROID, 1,
          EGL14.EGL_NONE,
        )
      }

    val configs = arrayOfNulls<EGLConfig>(1)
    val numConfigs = IntArray(1)
    if (
      !EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) ||
        numConfigs[0] <= 0 ||
        configs[0] == null
    ) {
      return false
    }
    val config = configs[0]!!

    val contextVersion = if (tenBit) 3 else 2
    val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, contextVersion, EGL14.EGL_NONE)
    val ctx = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
    if (ctx == EGL14.EGL_NO_CONTEXT) {
      return false
    }

    var surface = EGL14.EGL_NO_SURFACE
    if (useP3Passthrough) {
      val p3SurfaceAttribs =
        intArrayOf(
          EGL_GL_COLORSPACE_KHR,
          EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT,
          EGL14.EGL_NONE,
        )
      surface =
        runCatching {
            EGL14.eglCreateWindowSurface(eglDisplay, config, encoderSurface, p3SurfaceAttribs, 0)
          }
          .getOrDefault(EGL14.EGL_NO_SURFACE)
    }

    if (surface == EGL14.EGL_NO_SURFACE) {
      val standardSurfaceAttribs = intArrayOf(EGL14.EGL_NONE)
      surface =
        runCatching {
            EGL14.eglCreateWindowSurface(
              eglDisplay,
              config,
              encoderSurface,
              standardSurfaceAttribs,
              0,
            )
          }
          .getOrDefault(EGL14.EGL_NO_SURFACE)
    }

    if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(eglDisplay, surface, surface, ctx)) {
      if (surface != EGL14.EGL_NO_SURFACE) {
        EGL14.eglDestroySurface(eglDisplay, surface)
      }
      EGL14.eglDestroyContext(eglDisplay, ctx)
      return false
    }

    eglContext = ctx
    eglSurface = surface
    Log.i(
      TAG,
      "EGL initialized: esVersion=$contextVersion, tenBit=$tenBit, p3Passthrough=$useP3Passthrough",
    )
    return true
  }

  fun setPaused(paused: Boolean) {
    if (paused == isPaused) return
    val now = SystemClock.elapsedRealtimeNanos()
    if (paused) {
      pauseStartNs = now
      isPaused = true
    } else {
      if (pauseStartNs > 0L && firstFrameNs > 0L) {
        pausedAccumulatedNs += (now - pauseStartNs).coerceAtLeast(0L)
      }
      pauseStartNs = 0L
      isPaused = false
    }
  }

  fun updateCaptureDimensions(newW: Int, newH: Int) {
    captureWidth = newW
    captureHeight = newH
    surfaceTexture?.setDefaultBufferSize(newW, newH)
    recomputeGeometry()
  }

  private fun recomputeGeometry() {
    var xScale = 1f
    var yScale = 1f

    val sLeft: Float
    val sRight: Float
    val tBottom: Float
    val tTop: Float

    if (cropRegion == null) {
      sLeft = 0f
      sRight = 1f
      tBottom = 0f
      tTop = 1f

      // Fit rotated capture cleanly inside fixed orientation canvas without distortion
      val captureAspect = captureWidth.toFloat() / captureHeight.toFloat()
      val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()
      if (kotlin.math.abs(captureAspect - outputAspect) > 0.01f) {
        if (captureAspect > outputAspect) {
          yScale = outputAspect / captureAspect
        } else {
          xScale = captureAspect / outputAspect
        }
      }
      isUnscaledDimensions =
        captureWidth == outputWidth &&
          captureHeight == outputHeight &&
          kotlin.math.abs(xScale - 1f) < 0.001f &&
          kotlin.math.abs(yScale - 1f) < 0.001f
    } else {
      if (captureWidth == cropRegion.screenWidth && captureHeight == cropRegion.screenHeight) {
        sLeft = cropRegion.left.toFloat() / captureWidth.toFloat()
        sRight = cropRegion.right.toFloat() / captureWidth.toFloat()
        tTop = 1f - (cropRegion.top.toFloat() / captureHeight.toFloat())
        tBottom = 1f - (cropRegion.bottom.toFloat() / captureHeight.toFloat())
        isUnscaledDimensions = cropRegion.width == outputWidth && cropRegion.height == outputHeight
      } else {
        // Orientation changed during region capture: keep proportional crop inside current capture
        val normLeft = cropRegion.left.toFloat() / cropRegion.screenWidth.toFloat()
        val normRight = cropRegion.right.toFloat() / cropRegion.screenWidth.toFloat()
        val normTop = cropRegion.top.toFloat() / cropRegion.screenHeight.toFloat()
        val normBottom = cropRegion.bottom.toFloat() / cropRegion.screenHeight.toFloat()
        sLeft = normLeft.coerceIn(0f, 0.9f)
        sRight = normRight.coerceIn(sLeft + 0.05f, 1f)
        tTop = (1f - normTop).coerceIn(0.1f, 1f)
        tBottom = (1f - normBottom).coerceIn(0f, tTop - 0.05f)

        val croppedW = (sRight - sLeft) * captureWidth
        val croppedH = (tTop - tBottom) * captureHeight
        val cropAspect = croppedW / croppedH.coerceAtLeast(1f)
        val outAspect = outputWidth.toFloat() / outputHeight.toFloat()
        if (kotlin.math.abs(cropAspect - outAspect) > 0.01f) {
          if (cropAspect > outAspect) yScale = outAspect / cropAspect
          else xScale = cropAspect / outAspect
        }
        isUnscaledDimensions = false
      }
    }

    // Triangle strip: bottom-left, bottom-right, top-left, top-right
    vertexBuffer.clear()
    vertexBuffer.put(
      floatArrayOf(
        -xScale, -yScale,
        xScale, -yScale,
        -xScale, yScale,
        xScale, yScale,
      ),
    )
    vertexBuffer.position(0)

    texCoordBuffer.clear()
    texCoordBuffer.put(
      floatArrayOf(
        sLeft, tBottom,
        sRight, tBottom,
        sLeft, tTop,
        sRight, tTop,
      ),
    )
    texCoordBuffer.position(0)
  }

  override fun onFrameAvailable(st: SurfaceTexture) {
    if (released) return
    try {
      st.updateTexImage()
      if (isPaused) return

      st.getTransformMatrix(stMatrix)
      val nowNs = SystemClock.elapsedRealtimeNanos()
      if (firstFrameNs == 0L) {
        firstFrameNs = nowNs
      }
      val ptsNs = (nowNs - firstFrameNs - pausedAccumulatedNs).coerceAtLeast(0L)
      if (lastSubmittedPtsNs >= 0L && (ptsNs - lastSubmittedPtsNs) < minFrameIntervalNs) {
        return
      }
      lastSubmittedPtsNs = ptsNs

      val frameDataSpace = runCatching { st.dataSpace }.getOrDefault(DataSpace.DATASPACE_UNKNOWN)
      if (frameDataSpace != lastFrameDataSpace) {
        lastFrameDataSpace = frameDataSpace
        currentShaderPlan = ColorPipelinePlanner.resolveFrameShaderColorPlan(frameDataSpace, colorPlan)
      }

      val isAxisAligned1To1 =
        isUnscaledDimensions &&
          kotlin.math.abs(stMatrix[0]) > 0.999f &&
          kotlin.math.abs(stMatrix[5]) > 0.999f
      val desiredFilter = if (isAxisAligned1To1) GLES20.GL_NEAREST else GLES20.GL_LINEAR

      GLES20.glViewport(0, 0, outputWidth, outputHeight)
      GLES20.glClearColor(0f, 0f, 0f, 1f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

      GLES20.glUseProgram(programId)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

      if (desiredFilter != currentTexFilter) {
        GLES20.glTexParameteri(
          GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
          GLES20.GL_TEXTURE_MIN_FILTER,
          desiredFilter,
        )
        GLES20.glTexParameteri(
          GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
          GLES20.GL_TEXTURE_MAG_FILTER,
          desiredFilter,
        )
        currentTexFilter = desiredFilter
      }

      GLES20.glEnableVertexAttribArray(positionHandle)
      GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

      GLES20.glEnableVertexAttribArray(texCoordHandle)
      GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

      GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, stMatrix, 0)
      GLES20.glUniform1i(transferModeHandle, currentShaderPlan.transformMode.modeInt)
      GLES20.glUniformMatrix3fv(
        colorMatrixHandle,
        1,
        false,
        currentShaderPlan.columnMajorMatrix,
        0,
      )

      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

      GLES20.glDisableVertexAttribArray(positionHandle)
      GLES20.glDisableVertexAttribArray(texCoordHandle)

      EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
      EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    } catch (e: Exception) {
      Log.e(TAG, "Error rendering GL frame", e)
    }
  }

  fun release() {
    if (released) return
    released = true
    runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
    runCatching { captureSurface?.release() }
    captureSurface = null
    runCatching { surfaceTexture?.release() }
    surfaceTexture = null

    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
      if (eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        if (textureId != 0) {
          GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
          textureId = 0
        }
        if (programId != 0) {
          GLES20.glDeleteProgram(programId)
          programId = 0
        }
      }
      EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
      if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
      if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
      EGL14.eglReleaseThread()
      EGL14.eglTerminate(eglDisplay)
    }
    eglSurface = EGL14.EGL_NO_SURFACE
    eglContext = EGL14.EGL_NO_CONTEXT
    eglDisplay = EGL14.EGL_NO_DISPLAY
  }

  private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val prog = GLES20.glCreateProgram()
    GLES20.glAttachShader(prog, vs)
    GLES20.glAttachShader(prog, fs)
    GLES20.glLinkProgram(prog)
    val status = IntArray(1)
    GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) {
      "GL program link failed: ${GLES20.glGetProgramInfoLog(prog)}"
    }
    GLES20.glDeleteShader(vs)
    GLES20.glDeleteShader(fs)
    return prog
  }

  private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) {
      "GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
    }
    return shader
  }

  companion object {
    private const val TAG = "GlVideoPipeline"
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val EGL_GL_COLORSPACE_KHR = 0x309D
    private const val EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT = 0x3490

    private const val VERTEX_SHADER =
      """
      attribute vec4 aPosition;
      attribute vec4 aTexCoord;
      uniform mat4 uTexMatrix;
      varying vec2 vTexCoord;
      void main() {
        gl_Position = aPosition;
        vTexCoord = (uTexMatrix * aTexCoord).xy;
      }
      """

    private const val FRAGMENT_SHADER =
      """
      #extension GL_OES_EGL_image_external : require
      precision highp float;
      varying vec2 vTexCoord;
      uniform samplerExternalOES sTexture;
      uniform int uTransferMode;
      uniform mat3 uColorMatrix;

      vec3 srgbToLinear(vec3 c) {
        vec3 clamped = max(c, vec3(0.0));
        vec3 low = clamped / 12.92;
        vec3 high = pow((clamped + 0.055) / 1.055, vec3(2.4));
        vec3 mask = step(vec3(0.04045), clamped);
        return mix(low, high, mask);
      }

      vec3 linearToSrgb(vec3 c) {
        vec3 clamped = clamp(c, 0.0, 1.0);
        vec3 low = clamped * 12.92;
        vec3 safeBase = max(clamped, vec3(0.0031308));
        vec3 high = 1.055 * pow(safeBase, vec3(1.0 / 2.4)) - 0.055;
        vec3 mask = step(vec3(0.0031308), clamped);
        return mix(low, high, mask);
      }

      vec3 hdrToSdrLinear(vec3 c) {
        vec3 clamped = clamp(c, 0.0, 1.0);
        vec3 lin = pow(max(clamped, vec3(0.0001)), vec3(2.2));
        vec3 gamuted = max(uColorMatrix * lin, vec3(0.0));
        float luma = dot(gamuted, vec3(0.2126, 0.7152, 0.0722));
        float compressed = luma / (1.0 + luma * 0.35);
        float scale = luma > 0.0001 ? (compressed / luma) : 1.0;
        return clamp(gamuted * scale, 0.0, 1.0);
      }

      void main() {
        vec4 texel = texture2D(sTexture, vTexCoord);
        if (uTransferMode == 0) {
          gl_FragColor = texel;
        } else if (uTransferMode == 1) {
          vec3 lin = srgbToLinear(texel.rgb);
          vec3 converted = clamp(uColorMatrix * lin, 0.0, 1.0);
          gl_FragColor = vec4(linearToSrgb(converted), texel.a);
        } else {
          vec3 sdrLin = hdrToSdrLinear(texel.rgb);
          gl_FragColor = vec4(linearToSrgb(sdrLin), texel.a);
        }
      }
      """
  }
}
