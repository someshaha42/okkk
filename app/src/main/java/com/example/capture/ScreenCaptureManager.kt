package com.example.capture

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.settings.ColorModeOption
import com.example.settings.RegionRectPx
import com.example.vulkan.VulkanPipelineCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureManager(
    private val mediaProjection: MediaProjection,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val displayDensityDpi: Int,
    private val cropRegion: RegionRectPx,
    private val encoderWidth: Int,
    private val encoderHeight: Int,
    private val encoderSurface: Surface,
    private val targetFps: Int,
    initialColorMode: ColorModeOption
) {
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val paused = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0L)
    private val colorModeRef = AtomicReference(initialColorMode)
    private var lastSubmittedFrameNs = 0L

    @SuppressLint("WrongConstant")
    fun start() {
        val processor = VulkanPipelineCoordinator.ensureInitialized()
        VulkanPipelineCoordinator.setColorMode(colorModeRef.get())
        VulkanPipelineCoordinator.setRecordingActive(true)
        VulkanPipelineCoordinator.attachEncoderSurface(
            surface = encoderSurface,
            width = encoderWidth,
            height = encoderHeight,
            prefer10Bit = colorModeRef.get() == ColorModeOption.DEEP_COLOR_10BIT
        )

        val thread = HandlerThread("FrameCut-VulkanCapture").apply { start() }
        val handler = Handler(thread.looper)
        captureThread = thread
        captureHandler = handler

        val reader = ImageReader.newInstance(
            displayWidth,
            displayHeight,
            PixelFormat.RGBA_8888,
            3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN
        )
        imageReader = reader

        val minFrameIntervalNs = if (targetFps > 0) 1_000_000_000L / targetFps else 16_666_666L

        reader.setOnImageAvailableListener({ ir ->
            if (stopped.get()) return@setOnImageAvailableListener
            val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (paused.get()) {
                    return@setOnImageAvailableListener
                }
                val nowNs = System.nanoTime()
                if (lastSubmittedFrameNs != 0L && (nowNs - lastSubmittedFrameNs) < (minFrameIntervalNs * 85L / 100L)) {
                    return@setOnImageAvailableListener
                }
                val hwBuffer = image.hardwareBuffer ?: return@setOnImageAvailableListener
                val activeMode = VulkanPipelineCoordinator.getColorMode()
                colorModeRef.set(activeMode)
                val ok = processor.processHardwareBuffer(
                    buffer = hwBuffer,
                    srcWidth = displayWidth,
                    srcHeight = displayHeight,
                    cropRect = cropRegion,
                    dstWidth = encoderWidth,
                    dstHeight = encoderHeight,
                    colorMode = activeMode,
                    frameIndex = frameCounter.incrementAndGet()
                )
                if (ok) {
                    lastSubmittedFrameNs = nowNs
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Frame processing failed: ${t.message}")
            } finally {
                runCatching { image.close() }
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "FrameCut-VulkanCapture",
            displayWidth,
            displayHeight,
            displayDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    fun setPaused(isPaused: Boolean) {
        paused.set(isPaused)
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        VulkanPipelineCoordinator.setRecordingActive(false)
        VulkanPipelineCoordinator.attachEncoderSurface(null, 0, 0, false)

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null

        runCatching { captureThread?.quitSafely() }
        runCatching { captureThread?.join(1000L) }
        captureThread = null
        captureHandler = null
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}
