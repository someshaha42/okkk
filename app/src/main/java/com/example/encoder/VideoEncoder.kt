package com.example.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.example.color.ChromaSubsampling
import com.example.color.ColorPipelinePlan
import com.example.color.ColorPipelinePlanner
import com.example.color.ColorRangeMode
import com.example.color.DisplayColorProfile
import com.example.color.HardwareColorCapabilities
import com.example.color.PixelFormatMode
import com.example.muxer.MediaMuxerManager
import com.example.settings.InternalRecordingConfig
import com.example.settings.VideoBitrateOption
import java.util.concurrent.atomic.AtomicBoolean

enum class ChipsetFamily {
  SNAPDRAGON,
  MEDIATEK,
  OTHER,
}

data class EncoderSelection(
  val codecName: String,
  val isHardwareAccelerated: Boolean,
  val chipsetFamily: ChipsetFamily,
  val effectiveBitrateBps: Int,
  val alignedWidth: Int,
  val alignedHeight: Int,
  val colorPlan: ColorPipelinePlan,
  val hardwareColorCapabilities: HardwareColorCapabilities,
)

/**
 * Hardware-first fixed H.265/HEVC (30 FPS) MediaCodec encoder with chipset detection (Snapdragon
 * c2.qti.hevc.encoder, MediaTek c2.mtk.hevc.encoder), automatic capability clamping, adaptive
 * 10-bit/wide-gamut/full-range color configuration, and ordered HAL fallback.
 */
class VideoEncoder(
  private val requestedWidth: Int,
  private val requestedHeight: Int,
  private val bitrateOption: VideoBitrateOption,
  private val muxerManager: MediaMuxerManager,
  private val displayColorProfile: DisplayColorProfile = ColorPipelinePlanner.defaultSdrDisplayProfile(),
) {
  private var mediaCodec: MediaCodec? = null
  private var inputSurface: Surface? = null
  private var drainThread: Thread? = null
  private val isRunning = AtomicBoolean(false)
  private var selection: EncoderSelection? = null

  fun prepare(): EncoderSelection {
    val eglColorSupport = ColorPipelinePlanner.probeEglColorSupport()
    var selected =
      selectBestEncoder(
        requestedWidth = requestedWidth,
        requestedHeight = requestedHeight,
        bitrateOption = bitrateOption,
        displayColorProfile = displayColorProfile,
        eglColorSupport = eglColorSupport,
      )

    val codec =
      runCatching { MediaCodec.createByCodecName(selected.codecName) }
        .getOrElse {
          Log.w(TAG, "Preferred codec ${selected.codecName} failed to instantiate, using default", it)
          MediaCodec.createEncoderByType(InternalRecordingConfig.VIDEO_MIME_TYPE)
        }

    val actualCodecInfo = codec.codecInfo
    val actualHwCaps =
      ColorPipelinePlanner.inspectCodecCapabilities(
        codecInfo = actualCodecInfo,
        eglTenBitSupported = eglColorSupport.first,
        eglP3PassthroughSupported = eglColorSupport.second,
      )
    val candidatePlans =
      ColorPipelinePlanner.buildFallbackPlans(
        displayProfile = displayColorProfile,
        hwCaps = actualHwCaps,
        codecInfo = actualCodecInfo,
        targetBitrateBps = selected.effectiveBitrateBps,
      )

    var configured = false
    for (plan in candidatePlans) {
      val candidateSelection =
        selected.copy(
          codecName = actualCodecInfo.name ?: selected.codecName,
          colorPlan = plan,
          hardwareColorCapabilities = actualHwCaps,
        )
      val format =
        buildFormat(
          codecInfo = actualCodecInfo,
          selected = candidateSelection,
          plan = plan,
          includeAdvanced = true,
          includeColorMetadata = true,
        )
      try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        selected = candidateSelection
        configured = true
        break
      } catch (e: Exception) {
        Log.w(TAG, "Encoder plan rejected [${plan.summaryLabel()}], trying next fallback", e)
        runCatching { codec.reset() }
      }
    }

    if (!configured) {
      val safeBitrate = selected.effectiveBitrateBps.coerceAtMost(50_000_000)
      val safePlan =
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
      selected =
        selected.copy(
          effectiveBitrateBps = safeBitrate,
          colorPlan = safePlan,
          hardwareColorCapabilities = actualHwCaps,
        )
      try {
        val safeMetadataFormat =
          buildFormat(
            codecInfo = actualCodecInfo,
            selected = selected,
            plan = safePlan,
            includeAdvanced = false,
            includeColorMetadata = true,
          )
        codec.configure(safeMetadataFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      } catch (e: Exception) {
        Log.w(TAG, "Baseline metadata config rejected; configuring minimal HEVC surface format", e)
        runCatching { codec.reset() }
        val minimalFormat =
          buildFormat(
            codecInfo = actualCodecInfo,
            selected = selected,
            plan = safePlan,
            includeAdvanced = false,
            includeColorMetadata = false,
          )
        codec.configure(minimalFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      }
    }

    selection = selected

    inputSurface = codec.createInputSurface()
    codec.start()
    mediaCodec = codec

    isRunning.set(true)
    drainThread =
      Thread(::drainLoop, "FrameCut-VideoDrain").apply {
        priority = Thread.MAX_PRIORITY
        start()
      }

    Log.i(
      TAG,
      "VideoEncoder started: codec=${selected.codecName} (H.265), " +
        "fps=${InternalRecordingConfig.TARGET_FPS}, hw=${selected.isHardwareAccelerated}, " +
        "chipset=${selected.chipsetFamily}, size=${selected.alignedWidth}x${selected.alignedHeight}, " +
        "bitrate=${selected.effectiveBitrateBps / 1_000_000}Mbps, " +
        "color=${selected.colorPlan.summaryLabel()}, " +
        "qpBounds=${selected.colorPlan.useQpBounds}, " +
        "p3Egl=${selected.colorPlan.useDisplayP3PassthroughEgl}",
    )
    return selected
  }

  private fun buildFormat(
    codecInfo: MediaCodecInfo,
    selected: EncoderSelection,
    plan: ColorPipelinePlan,
    includeAdvanced: Boolean,
    includeColorMetadata: Boolean,
  ): MediaFormat {
    return MediaFormat.createVideoFormat(
        InternalRecordingConfig.VIDEO_MIME_TYPE,
        selected.alignedWidth,
        selected.alignedHeight,
      )
      .apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, InternalRecordingConfig.SURFACE_COLOR_FORMAT)
        setInteger(MediaFormat.KEY_BIT_RATE, selected.effectiveBitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, InternalRecordingConfig.TARGET_FPS)
        setInteger(
          MediaFormat.KEY_I_FRAME_INTERVAL,
          InternalRecordingConfig.I_FRAME_INTERVAL_SECONDS,
        )
        if (includeColorMetadata) {
          setInteger(MediaFormat.KEY_COLOR_STANDARD, plan.colorStandard)
          setInteger(MediaFormat.KEY_COLOR_TRANSFER, plan.colorTransfer)
          setInteger(MediaFormat.KEY_COLOR_RANGE, plan.colorRange.mediaFormatValue)
        }
        if (includeAdvanced) {
          setInteger(MediaFormat.KEY_CAPTURE_RATE, InternalRecordingConfig.TARGET_FPS)
          setInteger(MediaFormat.KEY_PRIORITY, 0)
          setInteger(MediaFormat.KEY_OPERATING_RATE, InternalRecordingConfig.TARGET_FPS)
          setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
          configureBitrateModeAndProfile(this, codecInfo, plan)
          if (plan.useQpBounds) {
            setInteger(MediaFormat.KEY_VIDEO_QP_I_MIN, QP_I_MIN_HIGH_FIDELITY)
            setInteger(MediaFormat.KEY_VIDEO_QP_I_MAX, QP_I_MAX_HIGH_FIDELITY)
            setInteger(MediaFormat.KEY_VIDEO_QP_P_MIN, QP_P_MIN_HIGH_FIDELITY)
            setInteger(MediaFormat.KEY_VIDEO_QP_P_MAX, QP_P_MAX_HIGH_FIDELITY)
          }
        }
      }
  }

  fun getInputSurface(): Surface =
    inputSurface ?: throw IllegalStateException("VideoEncoder has not been prepared")

  fun getSelection(): EncoderSelection? = selection

  fun stopAndRelease() {
    if (!isRunning.getAndSet(false)) {
      releaseResources()
      return
    }
    try {
      mediaCodec?.signalEndOfInputStream()
    } catch (e: Exception) {
      Log.w(TAG, "signalEndOfInputStream failed", e)
    }
    try {
      drainThread?.join(2500L)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    releaseResources()
  }

  private fun drainLoop() {
    val codec = mediaCodec ?: return
    val bufferInfo = MediaCodec.BufferInfo()
    var formatAdded = false

    while (true) {
      val timeoutUs = if (isRunning.get()) 10_000L else 5_000L
      val index =
        try {
          codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
        } catch (e: Exception) {
          Log.e(TAG, "dequeueOutputBuffer error", e)
          break
        }

      when {
        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (!isRunning.get()) {
            break
          }
        }
        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          if (!formatAdded) {
            muxerManager.addVideoTrack(codec.outputFormat)
            formatAdded = true
          }
        }
        index >= 0 -> {
          val encodedData = codec.getOutputBuffer(index)
          if (
            encodedData != null &&
              bufferInfo.size > 0 &&
              (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
          ) {
            if (muxerManager.isStarted() || muxerManager.awaitStarted(600L)) {
              muxerManager.writeVideoSample(encodedData, bufferInfo)
            }
          }
          codec.releaseOutputBuffer(index, false)
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            break
          }
        }
      }
    }
  }

  private fun releaseResources() {
    runCatching { mediaCodec?.stop() }
    runCatching { mediaCodec?.release() }
    mediaCodec = null
    runCatching { inputSurface?.release() }
    inputSurface = null
    drainThread = null
  }

  private fun configureBitrateModeAndProfile(
    format: MediaFormat,
    info: MediaCodecInfo,
    plan: ColorPipelinePlan,
  ) {
    val caps =
      runCatching { info.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }
        .getOrNull() ?: return

    val encoderCaps = caps.encoderCapabilities
    if (encoderCaps != null) {
      when {
        encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
          format.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
          )
        encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
          format.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
          )
      }
    }

    val matchedLevel =
      plan.hevcLevel
        ?: caps.profileLevels
          ?.filter { it.profile == plan.hevcProfile }
          ?.maxOfOrNull { it.level }
    if (matchedLevel != null) {
      format.setInteger(MediaFormat.KEY_PROFILE, plan.hevcProfile)
      format.setInteger(MediaFormat.KEY_LEVEL, matchedLevel)
    } else if (plan.isTenBit) {
      format.setInteger(MediaFormat.KEY_PROFILE, plan.hevcProfile)
    }
  }

  companion object {
    private const val TAG = "VideoEncoder"
    private const val QP_I_MIN_HIGH_FIDELITY = 4
    private const val QP_I_MAX_HIGH_FIDELITY = 18
    private const val QP_P_MIN_HIGH_FIDELITY = 6
    private const val QP_P_MAX_HIGH_FIDELITY = 22

    fun detectChipsetFamily(): ChipsetFamily {
      val socManufacturer = Build.SOC_MANUFACTURER.orEmpty()
      val fingerprint =
        listOf(socManufacturer, Build.HARDWARE, Build.BOARD, Build.BRAND)
          .joinToString(" ")
          .lowercase()

      return when {
        fingerprint.contains("qcom") ||
          fingerprint.contains("qualcomm") ||
          fingerprint.contains("snapdragon") ||
          fingerprint.contains("qti") -> ChipsetFamily.SNAPDRAGON
        fingerprint.contains("mtk") ||
          fingerprint.contains("mediatek") ||
          fingerprint.contains("mt6") ||
          fingerprint.contains("mt8") -> ChipsetFamily.MEDIATEK
        else -> ChipsetFamily.OTHER
      }
    }

    fun selectBestEncoder(
      requestedWidth: Int,
      requestedHeight: Int,
      bitrateOption: VideoBitrateOption = VideoBitrateOption.MBPS_150,
      availableCodecs: List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList(),
      chipsetFamily: ChipsetFamily = detectChipsetFamily(),
      displayColorProfile: DisplayColorProfile = ColorPipelinePlanner.defaultSdrDisplayProfile(),
      eglColorSupport: Pair<Boolean, Boolean> = false to false,
    ): EncoderSelection {
      val hevcEncoders =
        availableCodecs.filter { info ->
          info.isEncoder &&
            info.supportedTypes.any {
              it.equals(InternalRecordingConfig.VIDEO_MIME_TYPE, ignoreCase = true)
            } &&
            supportsSurfaceInput(info)
        }

      val hardwareEncoders = hevcEncoders.filter { isHardwareCodec(it) }
      val pool = hardwareEncoders.ifEmpty { hevcEncoders }

      val preferredNames =
        when (chipsetFamily) {
          ChipsetFamily.SNAPDRAGON ->
            listOf(
              InternalRecordingConfig.SNAPDRAGON_HEVC_ENCODER,
              InternalRecordingConfig.SNAPDRAGON_LEGACY_HEVC_ENCODER,
            )
          ChipsetFamily.MEDIATEK ->
            listOf(
              InternalRecordingConfig.MEDIATEK_HEVC_ENCODER,
              InternalRecordingConfig.MEDIATEK_LEGACY_HEVC_ENCODER,
            )
          ChipsetFamily.OTHER ->
            listOf(
              InternalRecordingConfig.SNAPDRAGON_HEVC_ENCODER,
              InternalRecordingConfig.MEDIATEK_HEVC_ENCODER,
            )
        }

      val chosenInfo =
        preferredNames.firstNotNullOfOrNull { targetName ->
          pool.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
        }
          ?: pool.firstOrNull { it.name.startsWith("c2.", ignoreCase = true) }
          ?: pool.firstOrNull()

      if (chosenInfo == null) {
        val evenW = alignTo(requestedWidth.coerceAtLeast(128), 2)
        val evenH = alignTo(requestedHeight.coerceAtLeast(128), 2)
        val defaultCaps =
          HardwareColorCapabilities(
            supportsMain10Profile = false,
            supportsHdr10Profile = false,
            supportsHdrEditing = false,
            supportsHlgEditing = false,
            supportsQpBounds = false,
            supports10BitEglSurface = eglColorSupport.first,
            supportsDisplayP3PassthroughEgl = eglColorSupport.second,
            bestChromaSubsampling = ChromaSubsampling.YUV_420,
            supports10BitYuvInput = false,
          )
        val defaultPlan =
          ColorPipelinePlanner.buildFallbackPlans(
              displayProfile = displayColorProfile,
              hwCaps = defaultCaps,
              codecInfo = null,
              targetBitrateBps = bitrateOption.targetBitsPerSecond.coerceAtMost(50_000_000),
            )
            .first()
        return EncoderSelection(
          codecName = "default-hevc",
          isHardwareAccelerated = false,
          chipsetFamily = chipsetFamily,
          effectiveBitrateBps = bitrateOption.targetBitsPerSecond.coerceAtMost(50_000_000),
          alignedWidth = evenW,
          alignedHeight = evenH,
          colorPlan = defaultPlan,
          hardwareColorCapabilities = defaultCaps,
        )
      }

      val caps =
        runCatching { chosenInfo.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }
          .getOrNull()
      val videoCaps = caps?.videoCapabilities

      val wAlign = (videoCaps?.widthAlignment ?: 2).coerceAtLeast(2)
      val hAlign = (videoCaps?.heightAlignment ?: 2).coerceAtLeast(2)
      var alignedW = alignTo(requestedWidth.coerceAtLeast(128), wAlign)
      var alignedH = alignTo(requestedHeight.coerceAtLeast(128), hAlign)

      if (videoCaps != null) {
        val maxSupportedW = videoCaps.supportedWidths.upper
        val maxSupportedH = videoCaps.supportedHeights.upper
        if (alignedW > maxSupportedW || alignedH > maxSupportedH) {
          val clampScale = minOf(maxSupportedW.toFloat() / alignedW, maxSupportedH.toFloat() / alignedH)
          alignedW = (alignedW * clampScale).toInt()
          alignedH = (alignedH * clampScale).toInt()
        }
        alignedW =
          alignedW.coerceIn(
            videoCaps.supportedWidths.lower,
            maxSupportedW,
          )
        alignedH =
          alignedH.coerceIn(
            videoCaps.supportedHeights.lower,
            maxSupportedH,
          )
        alignedW = alignTo(alignedW, wAlign)
        alignedH = alignTo(alignedH, hAlign)

        // On 4K Android TVs or ultra-high-res tablets, step down proportionally if the hardware
        // encoder cannot sustain 30 FPS at the full native pixel area.
        val scales = floatArrayOf(0.85f, 0.75f, 0.667f, 0.5f, 0.375f)
        val baseW = alignedW
        val baseH = alignedH
        var idx = 0
        while (
          idx < scales.size &&
            !isSizeAndRateCompatible(
              videoCaps,
              alignedW,
              alignedH,
              InternalRecordingConfig.TARGET_FPS.toDouble(),
            )
        ) {
          val factor = scales[idx++]
          val candidateW =
            alignTo((baseW * factor).toInt().coerceAtLeast(128), wAlign).coerceIn(
              videoCaps.supportedWidths.lower,
              videoCaps.supportedWidths.upper,
            )
          val candidateH =
            alignTo((baseH * factor).toInt().coerceAtLeast(128), hAlign).coerceIn(
              videoCaps.supportedHeights.lower,
              videoCaps.supportedHeights.upper,
            )
          alignedW = alignTo(candidateW, wAlign)
          alignedH = alignTo(candidateH, hAlign)
        }
      }

      val effectiveBitrate =
        if (videoCaps != null) {
          val range = videoCaps.bitrateRange
          bitrateOption.targetBitsPerSecond.coerceIn(range.lower, range.upper)
        } else {
          bitrateOption.targetBitsPerSecond
        }

      val hwColorCaps =
        ColorPipelinePlanner.inspectCodecCapabilities(
          codecInfo = chosenInfo,
          eglTenBitSupported = eglColorSupport.first,
          eglP3PassthroughSupported = eglColorSupport.second,
        )
      val bestPlan =
        ColorPipelinePlanner.buildFallbackPlans(
            displayProfile = displayColorProfile,
            hwCaps = hwColorCaps,
            codecInfo = chosenInfo,
            targetBitrateBps = effectiveBitrate,
          )
          .first()

      return EncoderSelection(
        codecName = chosenInfo.name,
        isHardwareAccelerated = isHardwareCodec(chosenInfo),
        chipsetFamily = chipsetFamily,
        effectiveBitrateBps = effectiveBitrate,
        alignedWidth = alignedW,
        alignedHeight = alignedH,
        colorPlan = bestPlan,
        hardwareColorCapabilities = hwColorCaps,
      )
    }

    private fun supportsSurfaceInput(info: MediaCodecInfo): Boolean {
      val caps =
        runCatching { info.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }
          .getOrNull() ?: return false
      return caps.colorFormats.contains(InternalRecordingConfig.SURFACE_COLOR_FORMAT)
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
      return !info.isSoftwareOnly
    }

    private fun isSizeAndRateCompatible(
      videoCaps: MediaCodecInfo.VideoCapabilities,
      width: Int,
      height: Int,
      fps: Double,
    ): Boolean {
      if (!videoCaps.isSizeSupported(width, height)) return false
      return runCatching { videoCaps.areSizeAndRateSupported(width, height, fps) }
        .getOrDefault(true)
    }

    private fun alignTo(value: Int, alignment: Int): Int {
      val rem = value % alignment
      return if (rem == 0) value else (value - rem).coerceAtLeast(alignment)
    }
  }
}
