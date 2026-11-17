package com.example.color

import android.content.Context
import android.graphics.ColorSpace
import android.hardware.DataSpace
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.util.Log
import android.view.Display
import com.example.settings.InternalRecordingConfig

enum class SourceColorSpace(val label: String) {
  SRGB("sRGB"),
  BT709("BT.709"),
  DISPLAY_P3("Display P3"),
  BT2020("BT.2020"),
}

enum class ChromaSubsampling(val label: String) {
  YUV_444("4:4:4"),
  YUV_422("4:2:2"),
  YUV_420("4:2:0"),
}

enum class ColorRangeMode(val mediaFormatValue: Int, val label: String) {
  FULL(MediaFormat.COLOR_RANGE_FULL, "Full"),
  LIMITED(MediaFormat.COLOR_RANGE_LIMITED, "Limited"),
}

enum class PixelFormatMode(val label: String, val bitDepth: Int) {
  RGBA_1010102("RGBA_1010102", 10),
  RGBA_8888("RGBA_8888", 8),
}

data class DisplayColorProfile(
  val sourceColorSpace: SourceColorSpace,
  val isWideColorGamut: Boolean,
  val isHdr: Boolean,
  val supportsHdr10: Boolean,
  val supportsHlg: Boolean,
)

data class HardwareColorCapabilities(
  val supportsMain10Profile: Boolean,
  val supportsHdr10Profile: Boolean,
  val supportsHdrEditing: Boolean,
  val supportsHlgEditing: Boolean,
  val supportsQpBounds: Boolean,
  val supports10BitEglSurface: Boolean,
  val supportsDisplayP3PassthroughEgl: Boolean,
  val bestChromaSubsampling: ChromaSubsampling,
  val supports10BitYuvInput: Boolean,
)

data class ColorPipelinePlan(
  val hevcProfile: Int,
  val hevcLevel: Int?,
  val pixelFormatMode: PixelFormatMode,
  val expectedChroma: ChromaSubsampling,
  val colorStandard: Int,
  val colorTransfer: Int,
  val colorRange: ColorRangeMode,
  val useDisplayP3PassthroughEgl: Boolean,
  val useQpBounds: Boolean,
) {
  val isTenBit: Boolean
    get() = pixelFormatMode.bitDepth == 10

  fun summaryLabel(): String {
    val standardLabel =
      when (colorStandard) {
        MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
        else -> "BT.709"
      }
    val transferLabel =
      when (colorTransfer) {
        MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ"
        MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR"
      }
    return "${pixelFormatMode.bitDepth}-bit $standardLabel/$transferLabel ${colorRange.label} (${expectedChroma.label})"
  }
}

enum class ShaderColorTransform(val modeInt: Int) {
  PASSTHROUGH(0),
  GAMUT_MATRIX_SRGB_TRANSFER(1),
  HDR_TO_SDR_TONEMAP(2),
}

data class FrameShaderColorPlan(
  val transformMode: ShaderColorTransform,
  val columnMajorMatrix: FloatArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is FrameShaderColorPlan) return false
    return transformMode == other.transformMode && columnMajorMatrix.contentEquals(other.columnMajorMatrix)
  }

  override fun hashCode(): Int = 31 * transformMode.hashCode() + columnMajorMatrix.contentHashCode()
}

/**
 * Probes actual Display color capabilities, EGL surface extensions/10-bit configs, and MediaCodec
 * HEVC hardware profile/chroma/color-space capabilities to build the highest-fidelity color
 * pipeline plan with safe fallbacks.
 */
object ColorPipelinePlanner {

  private const val TAG = "ColorPipelinePlanner"
  private const val EGL_EXT_DISPLAY_P3_PASSTHROUGH = "EGL_EXT_gl_colorspace_display_p3_passthrough"

  fun detectDisplayColorProfile(context: Context): DisplayColorProfile {
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return defaultSdrDisplayProfile()

    val isWcg = runCatching { display.isWideColorGamut }.getOrDefault(false)
    val preferredWcg = runCatching { display.preferredWideGamutColorSpace }.getOrNull()
    val isHdr = runCatching { display.isHdr }.getOrDefault(false)
    val hdrTypes =
      runCatching { display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty() }
        .getOrDefault(emptySet())

    val supportsHdr10 =
      hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) ||
        hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)
    val supportsHlg = hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HLG)

    val sourceSpace =
      when {
        preferredWcg == ColorSpace.get(ColorSpace.Named.DISPLAY_P3) ||
          preferredWcg == ColorSpace.get(ColorSpace.Named.DCI_P3) -> SourceColorSpace.DISPLAY_P3
        preferredWcg == ColorSpace.get(ColorSpace.Named.BT2020) ||
          preferredWcg == ColorSpace.get(ColorSpace.Named.BT2020_PQ) ||
          preferredWcg == ColorSpace.get(ColorSpace.Named.BT2020_HLG) -> SourceColorSpace.BT2020
        preferredWcg == ColorSpace.get(ColorSpace.Named.BT709) -> SourceColorSpace.BT709
        isWcg -> SourceColorSpace.DISPLAY_P3
        else -> SourceColorSpace.SRGB
      }

    return DisplayColorProfile(
      sourceColorSpace = sourceSpace,
      isWideColorGamut = isWcg,
      isHdr = isHdr,
      supportsHdr10 = supportsHdr10,
      supportsHlg = supportsHlg,
    )
  }

  fun defaultSdrDisplayProfile(): DisplayColorProfile =
    DisplayColorProfile(
      sourceColorSpace = SourceColorSpace.SRGB,
      isWideColorGamut = false,
      isHdr = false,
      supportsHdr10 = false,
      supportsHlg = false,
    )

  fun probeEglColorSupport(): Pair<Boolean, Boolean> {
    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    return try {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (display == EGL14.EGL_NO_DISPLAY) return false to false
      val version = IntArray(2)
      if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false to false

      val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
      val supportsP3Passthrough = extensions.contains(EGL_EXT_DISPLAY_P3_PASSTHROUGH)

      val tenBitAttribs =
        intArrayOf(
          EGL14.EGL_RED_SIZE, 10,
          EGL14.EGL_GREEN_SIZE, 10,
          EGL14.EGL_BLUE_SIZE, 10,
          EGL14.EGL_ALPHA_SIZE, 2,
          EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
          EGLExt.EGL_RECORDABLE_ANDROID, 1,
          EGL14.EGL_NONE,
        )
      val configs = arrayOfNulls<EGLConfig>(1)
      val numConfigs = IntArray(1)
      val has10Bit =
        EGL14.eglChooseConfig(display, tenBitAttribs, 0, configs, 0, 1, numConfigs, 0) &&
          numConfigs[0] > 0 &&
          configs[0] != null

      has10Bit to supportsP3Passthrough
    } catch (t: Throwable) {
      Log.w(TAG, "EGL color probe failed; defaulting to 8-bit RGBA_8888", t)
      false to false
    } finally {
      if (display != EGL14.EGL_NO_DISPLAY) {
        runCatching { EGL14.eglTerminate(display) }
      }
    }
  }

  fun inspectCodecCapabilities(
    codecInfo: MediaCodecInfo?,
    eglTenBitSupported: Boolean,
    eglP3PassthroughSupported: Boolean,
  ): HardwareColorCapabilities {
    val caps =
      runCatching { codecInfo?.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }
        .getOrNull()
        ?: return HardwareColorCapabilities(
          supportsMain10Profile = false,
          supportsHdr10Profile = false,
          supportsHdrEditing = false,
          supportsHlgEditing = false,
          supportsQpBounds = false,
          supports10BitEglSurface = false,
          supportsDisplayP3PassthroughEgl = false,
          bestChromaSubsampling = ChromaSubsampling.YUV_420,
          supports10BitYuvInput = false,
        )

    val profiles = caps.profileLevels?.map { it.profile }?.toSet().orEmpty()
    val formats = caps.colorFormats?.toSet().orEmpty()

    val supportsMain10 =
      profiles.contains(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) ||
        profiles.contains(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) ||
        profiles.contains(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
    val supportsHdr10 =
      profiles.contains(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) ||
        profiles.contains(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)

    val supportsHdrEditing =
      runCatching { caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing) }
        .getOrDefault(false)
    val supportsHlgEditing =
      runCatching { caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HlgEditing) }
        .getOrDefault(false)
    val supportsQpBounds =
      runCatching { caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds) }
        .getOrDefault(false)

    val bestChroma = detectBestChromaSubsampling(formats)
    val supports10BitYuv =
      formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010) ||
        formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP210) ||
        formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010)

    return HardwareColorCapabilities(
      supportsMain10Profile = supportsMain10,
      supportsHdr10Profile = supportsHdr10,
      supportsHdrEditing = supportsHdrEditing,
      supportsHlgEditing = supportsHlgEditing,
      supportsQpBounds = supportsQpBounds,
      supports10BitEglSurface = eglTenBitSupported,
      supportsDisplayP3PassthroughEgl = eglP3PassthroughSupported,
      bestChromaSubsampling = bestChroma,
      supports10BitYuvInput = supports10BitYuv,
    )
  }

  fun detectBestChromaSubsampling(colorFormats: Set<Int>): ChromaSubsampling {
    val supports444 =
      colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Flexible) ||
        colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Interleaved)
    if (supports444) return ChromaSubsampling.YUV_444

    @Suppress("DEPRECATION")
    val supports422 =
      colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP210) ||
        colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Flexible) ||
        colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422SemiPlanar) ||
        colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Planar)
    if (supports422) return ChromaSubsampling.YUV_422

    return ChromaSubsampling.YUV_420
  }

  /**
   * Produces ordered candidate ColorPipelinePlans from highest fidelity to safest universal
   * fallback so VideoEncoder can configure the best configuration accepted by the device HAL.
   */
  fun buildFallbackPlans(
    displayProfile: DisplayColorProfile,
    hwCaps: HardwareColorCapabilities,
    codecInfo: MediaCodecInfo?,
    targetBitrateBps: Int,
  ): List<ColorPipelinePlan> {
    val caps =
      runCatching { codecInfo?.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }
        .getOrNull()
    val profileLevels = caps?.profileLevels?.toList().orEmpty()

    val maxMainLevel =
      profileLevels
        .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
        .maxOfOrNull { it.level }
    val maxMain10Level =
      profileLevels
        .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
        .maxOfOrNull { it.level }
    val maxHdr10Level =
      profileLevels
        .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 }
        .maxOfOrNull { it.level }

    val canUse10Bit = hwCaps.supportsMain10Profile && hwCaps.supports10BitEglSurface
    val useQp = hwCaps.supportsQpBounds && targetBitrateBps >= 150_000_000

    val plans = mutableListOf<ColorPipelinePlan>()

    // 1. Wide-gamut / HDR 10-bit pipeline: preserves full Display P3 & HDR10/HLG gradients in
    //    BT.2020 10-bit (1024 levels/channel) without sRGB gamut clipping or 8-bit banding.
    if (canUse10Bit && (displayProfile.isWideColorGamut || displayProfile.isHdr)) {
      val (std, transfer, hdrProfile, hdrLevel) =
        when {
          displayProfile.isHdr &&
            displayProfile.supportsHdr10 &&
            (hwCaps.supportsHdrEditing || hwCaps.supportsHdr10Profile) ->
            Quad(
              MediaFormat.COLOR_STANDARD_BT2020,
              MediaFormat.COLOR_TRANSFER_ST2084,
              if (hwCaps.supportsHdr10Profile) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
              } else {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
              },
              maxHdr10Level ?: maxMain10Level,
            )
          displayProfile.isHdr && displayProfile.supportsHlg && hwCaps.supportsHlgEditing ->
            Quad(
              MediaFormat.COLOR_STANDARD_BT2020,
              MediaFormat.COLOR_TRANSFER_HLG,
              MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
              maxMain10Level,
            )
          else ->
            Quad(
              MediaFormat.COLOR_STANDARD_BT2020,
              MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
              MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
              maxMain10Level,
            )
        }

      plans +=
        ColorPipelinePlan(
          hevcProfile = hdrProfile,
          hevcLevel = hdrLevel,
          pixelFormatMode = PixelFormatMode.RGBA_1010102,
          expectedChroma = hwCaps.bestChromaSubsampling,
          colorStandard = std,
          colorTransfer = transfer,
          colorRange = ColorRangeMode.FULL,
          useDisplayP3PassthroughEgl = false,
          useQpBounds = useQp,
        )
      plans +=
        ColorPipelinePlan(
          hevcProfile = hdrProfile,
          hevcLevel = hdrLevel,
          pixelFormatMode = PixelFormatMode.RGBA_1010102,
          expectedChroma = hwCaps.bestChromaSubsampling,
          colorStandard = std,
          colorTransfer = transfer,
          colorRange = ColorRangeMode.LIMITED,
          useDisplayP3PassthroughEgl = false,
          useQpBounds = false,
        )
    }

    // 2. 10-bit BT.709 Full-Range SDR pipeline: 1024 tonal steps prevent gradient banding on
    //    dark UI themes and blurred backgrounds while maintaining universal BT.709 colorimetry.
    if (canUse10Bit) {
      plans +=
        ColorPipelinePlan(
          hevcProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
          hevcLevel = maxMain10Level,
          pixelFormatMode = PixelFormatMode.RGBA_1010102,
          expectedChroma = hwCaps.bestChromaSubsampling,
          colorStandard = MediaFormat.COLOR_STANDARD_BT709,
          colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
          colorRange = ColorRangeMode.FULL,
          useDisplayP3PassthroughEgl =
            displayProfile.sourceColorSpace == SourceColorSpace.DISPLAY_P3 &&
              hwCaps.supportsDisplayP3PassthroughEgl,
          useQpBounds = useQp,
        )
      plans +=
        ColorPipelinePlan(
          hevcProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
          hevcLevel = maxMain10Level,
          pixelFormatMode = PixelFormatMode.RGBA_1010102,
          expectedChroma = hwCaps.bestChromaSubsampling,
          colorStandard = MediaFormat.COLOR_STANDARD_BT709,
          colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
          colorRange = ColorRangeMode.LIMITED,
          useDisplayP3PassthroughEgl =
            displayProfile.sourceColorSpace == SourceColorSpace.DISPLAY_P3 &&
              hwCaps.supportsDisplayP3PassthroughEgl,
          useQpBounds = false,
        )
    }

    // 3. 8-bit RGBA_8888 BT.709 Full-Range HEVC Main pipeline (avoids 16-235 range compression).
    plans +=
      ColorPipelinePlan(
        hevcProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        hevcLevel = maxMainLevel,
        pixelFormatMode = PixelFormatMode.RGBA_8888,
        expectedChroma = hwCaps.bestChromaSubsampling,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorRange = ColorRangeMode.FULL,
        useDisplayP3PassthroughEgl =
          displayProfile.sourceColorSpace == SourceColorSpace.DISPLAY_P3 &&
            hwCaps.supportsDisplayP3PassthroughEgl,
        useQpBounds = useQp,
      )

    // 4. Universal baseline 8-bit BT.709 Limited-Range HEVC Main fallback.
    plans +=
      ColorPipelinePlan(
        hevcProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        hevcLevel = maxMainLevel,
        pixelFormatMode = PixelFormatMode.RGBA_8888,
        expectedChroma = hwCaps.bestChromaSubsampling,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorRange = ColorRangeMode.LIMITED,
        useDisplayP3PassthroughEgl =
          displayProfile.sourceColorSpace == SourceColorSpace.DISPLAY_P3 &&
            hwCaps.supportsDisplayP3PassthroughEgl,
        useQpBounds = false,
      )

    return plans.distinct()
  }

  /**
   * Computes the exact GPU shader color conversion required when a captured SurfaceTexture frame's
   * DataSpace differs from the encoder's configured ColorStandard / ColorTransfer. Returns
   * PASSTHROUGH whenever source and encoder colorimetry already match so 1:1 pixels are untouched.
   */
  fun resolveFrameShaderColorPlan(
    frameDataSpace: Int,
    encoderPlan: ColorPipelinePlan,
  ): FrameShaderColorPlan {
    if (frameDataSpace == DataSpace.DATASPACE_UNKNOWN) {
      return FrameShaderColorPlan(ShaderColorTransform.PASSTHROUGH, IDENTITY_MATRIX_COL_MAJOR)
    }

    val srcStandard = DataSpace.getStandard(frameDataSpace)
    val srcTransfer = DataSpace.getTransfer(frameDataSpace)
    val srcIsHdr =
      srcTransfer == DataSpace.TRANSFER_ST2084 || srcTransfer == DataSpace.TRANSFER_HLG
    val targetIsHdr =
      encoderPlan.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
        encoderPlan.colorTransfer == MediaFormat.COLOR_TRANSFER_HLG

    if (srcIsHdr && !targetIsHdr) {
      return FrameShaderColorPlan(
        ShaderColorTransform.HDR_TO_SDR_TONEMAP,
        BT2020_TO_BT709_COL_MAJOR,
      )
    }

    val targetIsBt2020 = encoderPlan.colorStandard == MediaFormat.COLOR_STANDARD_BT2020
    if (targetIsBt2020) {
      return when (srcStandard) {
        DataSpace.STANDARD_BT2020 ->
          FrameShaderColorPlan(ShaderColorTransform.PASSTHROUGH, IDENTITY_MATRIX_COL_MAJOR)
        DataSpace.STANDARD_DCI_P3 ->
          FrameShaderColorPlan(
            ShaderColorTransform.GAMUT_MATRIX_SRGB_TRANSFER,
            DISPLAY_P3_TO_BT2020_COL_MAJOR,
          )
        else ->
          FrameShaderColorPlan(
            ShaderColorTransform.GAMUT_MATRIX_SRGB_TRANSFER,
            BT709_TO_BT2020_COL_MAJOR,
          )
      }
    }

    // Target is BT.709 / sRGB
    if (encoderPlan.useDisplayP3PassthroughEgl && srcStandard == DataSpace.STANDARD_DCI_P3) {
      // EGL surface already tagged as Display P3 passthrough -> hardware CSC converts accurately.
      return FrameShaderColorPlan(ShaderColorTransform.PASSTHROUGH, IDENTITY_MATRIX_COL_MAJOR)
    }

    return when (srcStandard) {
      DataSpace.STANDARD_DCI_P3 ->
        FrameShaderColorPlan(
          ShaderColorTransform.GAMUT_MATRIX_SRGB_TRANSFER,
          DISPLAY_P3_TO_BT709_COL_MAJOR,
        )
      DataSpace.STANDARD_BT2020 ->
        FrameShaderColorPlan(
          ShaderColorTransform.GAMUT_MATRIX_SRGB_TRANSFER,
          BT2020_TO_BT709_COL_MAJOR,
        )
      else -> FrameShaderColorPlan(ShaderColorTransform.PASSTHROUGH, IDENTITY_MATRIX_COL_MAJOR)
    }
  }

  private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

  internal fun rowMajorToColumnMajor(vararg m: Float): FloatArray {
    require(m.size == 9)
    return floatArrayOf(
      m[0], m[3], m[6],
      m[1], m[4], m[7],
      m[2], m[5], m[8],
    )
  }

  val IDENTITY_MATRIX_COL_MAJOR: FloatArray =
    rowMajorToColumnMajor(
      1f, 0f, 0f,
      0f, 1f, 0f,
      0f, 0f, 1f,
    )

  // Display P3 (D65) -> sRGB / BT.709 (D65)
  val DISPLAY_P3_TO_BT709_COL_MAJOR: FloatArray =
    rowMajorToColumnMajor(
      1.2249401f, -0.2249401f, 0.0000000f,
      -0.0420569f, 1.0420569f, 0.0000000f,
      -0.0196376f, -0.0786361f, 1.0982737f,
    )

  // Display P3 (D65) -> BT.2020 (D65)
  val DISPLAY_P3_TO_BT2020_COL_MAJOR: FloatArray =
    rowMajorToColumnMajor(
      0.7538330f, 0.1985974f, 0.0475696f,
      0.0457438f, 0.9417772f, 0.0124790f,
      -0.0012103f, 0.0176017f, 0.9836086f,
    )

  // sRGB / BT.709 (D65) -> BT.2020 (D65) (ITU-R BT.2087)
  val BT709_TO_BT2020_COL_MAJOR: FloatArray =
    rowMajorToColumnMajor(
      0.6274039f, 0.3292830f, 0.0433131f,
      0.0690973f, 0.9195404f, 0.0113623f,
      0.0163914f, 0.0880133f, 0.8955953f,
    )

  // BT.2020 (D65) -> BT.709 (D65) (inverse ITU-R BT.2087)
  val BT2020_TO_BT709_COL_MAJOR: FloatArray =
    rowMajorToColumnMajor(
      1.6604910f, -0.5876411f, -0.0728499f,
      -0.1245505f, 1.1328999f, -0.0083494f,
      -0.0181508f, -0.1005789f, 1.1187297f,
    )
}
