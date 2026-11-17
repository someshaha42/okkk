package com.example.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.example.muxer.MediaMuxerManager
import com.example.settings.InternalRecordingConfig
import com.example.settings.VideoBitrateOption
import com.example.settings.VideoCodecOption
import com.example.settings.VideoFpsOption
import java.util.concurrent.atomic.AtomicBoolean

enum class ChipsetFamily {
  SNAPDRAGON,
  MEDIATEK,
  OTHER,
}

data class EncoderSelection(
  val codecName: String,
  val effectiveCodec: VideoCodecOption,
  val isHardwareAccelerated: Boolean,
  val chipsetFamily: ChipsetFamily,
  val effectiveBitrateBps: Int,
  val effectiveFps: Int,
  val alignedWidth: Int,
  val alignedHeight: Int,
)

/**
 * Hardware-first H.265/HEVC & H.264/AVC MediaCodec encoder with chipset detection (Snapdragon
 * c2.qti.hevc/avc.encoder, MediaTek c2.mtk.hevc/avc.encoder), FPS configuration (30/60/90/120),
 * automatic capability clamping, and dedicated surface input.
 */
class VideoEncoder(
  private val requestedWidth: Int,
  private val requestedHeight: Int,
  private val codecOption: VideoCodecOption,
  private val fpsOption: VideoFpsOption,
  private val bitrateOption: VideoBitrateOption,
  private val muxerManager: MediaMuxerManager,
) {
  private var mediaCodec: MediaCodec? = null
  private var inputSurface: Surface? = null
  private var drainThread: Thread? = null
  private val isRunning = AtomicBoolean(false)
  private var selection: EncoderSelection? = null

  fun prepare(): EncoderSelection {
    var selected =
      selectBestEncoder(
        requestedWidth = requestedWidth,
        requestedHeight = requestedHeight,
        codecOption = codecOption,
        fpsOption = fpsOption,
        bitrateOption = bitrateOption,
      )

    val codec =
      runCatching { MediaCodec.createByCodecName(selected.codecName) }
        .getOrElse {
          Log.w(TAG, "Preferred codec ${selected.codecName} failed to instantiate, using default", it)
          MediaCodec.createEncoderByType(selected.effectiveCodec.mimeType)
        }

    val primaryFormat = buildFormat(selected, includeAdvanced = true)
    try {
      codec.configure(primaryFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    } catch (e: Exception) {
      Log.w(TAG, "Primary format config rejected; retrying safe baseline config", e)
      val safeFps = selected.effectiveFps.coerceAtMost(60)
      val safeBitrate = selected.effectiveBitrateBps.coerceAtMost(50_000_000)
      selected = selected.copy(effectiveFps = safeFps, effectiveBitrateBps = safeBitrate)
      val safeFormat = buildFormat(selected, includeAdvanced = false)
      codec.configure(safeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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
      "VideoEncoder started: codec=${selected.codecName} (${selected.effectiveCodec.label}), " +
        "fps=${selected.effectiveFps}, hw=${selected.isHardwareAccelerated}, " +
        "chipset=${selected.chipsetFamily}, size=${selected.alignedWidth}x${selected.alignedHeight}, " +
        "bitrate=${selected.effectiveBitrateBps / 1_000_000}Mbps",
    )
    return selected
  }

  private fun buildFormat(selected: EncoderSelection, includeAdvanced: Boolean): MediaFormat {
    return MediaFormat.createVideoFormat(
        selected.effectiveCodec.mimeType,
        selected.alignedWidth,
        selected.alignedHeight,
      )
      .apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, InternalRecordingConfig.SURFACE_COLOR_FORMAT)
        setInteger(MediaFormat.KEY_BIT_RATE, selected.effectiveBitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, selected.effectiveFps)
        setInteger(
          MediaFormat.KEY_I_FRAME_INTERVAL,
          InternalRecordingConfig.I_FRAME_INTERVAL_SECONDS,
        )
        if (includeAdvanced) {
          setInteger(MediaFormat.KEY_CAPTURE_RATE, selected.effectiveFps)
          if (selected.effectiveFps > 60) {
            setInteger(MediaFormat.KEY_OPERATING_RATE, selected.effectiveFps)
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
          }
          configureBitrateModeAndProfile(this, selected)
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
            // Drain continues until EOS or codec stops producing
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

  private fun configureBitrateModeAndProfile(format: MediaFormat, selected: EncoderSelection) {
    val info = findCodecInfo(selected.codecName) ?: return
    val mime = selected.effectiveCodec.mimeType
    val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return

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

    if (bitrateOption == VideoBitrateOption.LOSSLESS) {
      val targetProfile =
        when (selected.effectiveCodec) {
          VideoCodecOption.H265 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
          VideoCodecOption.H264 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        }
      val matchedProfile =
        caps.profileLevels?.filter { it.profile == targetProfile }?.maxByOrNull { it.level }
      if (matchedProfile != null) {
        format.setInteger(MediaFormat.KEY_PROFILE, matchedProfile.profile)
        format.setInteger(MediaFormat.KEY_LEVEL, matchedProfile.level)
      }
    }
  }

  companion object {
    private const val TAG = "VideoEncoder"

    fun detectChipsetFamily(): ChipsetFamily {
      val socManufacturer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          Build.SOC_MANUFACTURER.orEmpty()
        } else {
          ""
        }
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
      codecOption: VideoCodecOption = VideoCodecOption.H265,
      fpsOption: VideoFpsOption = VideoFpsOption.FPS_60,
      bitrateOption: VideoBitrateOption = VideoBitrateOption.LOSSLESS,
      availableCodecs: List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList(),
      chipsetFamily: ChipsetFamily = detectChipsetFamily(),
    ): EncoderSelection {
      var effectiveCodec = codecOption
      var chosenInfo = findPreferredCodecInfo(effectiveCodec, availableCodecs, chipsetFamily)

      if (chosenInfo == null && effectiveCodec == VideoCodecOption.H265) {
        Log.w(TAG, "No H.265 surface encoder found; falling back to H.264")
        effectiveCodec = VideoCodecOption.H264
        chosenInfo = findPreferredCodecInfo(effectiveCodec, availableCodecs, chipsetFamily)
      }

      if (chosenInfo == null) {
        val evenW = alignTo(requestedWidth.coerceAtLeast(128), 2)
        val evenH = alignTo(requestedHeight.coerceAtLeast(128), 2)
        return EncoderSelection(
          codecName =
            if (effectiveCodec == VideoCodecOption.H265) "default-hevc" else "default-avc",
          effectiveCodec = effectiveCodec,
          isHardwareAccelerated = false,
          chipsetFamily = chipsetFamily,
          effectiveBitrateBps = bitrateOption.targetBitsPerSecond.coerceAtMost(50_000_000),
          effectiveFps = fpsOption.fps.coerceAtMost(60),
          alignedWidth = evenW,
          alignedHeight = evenH,
        )
      }

      val caps =
        runCatching { chosenInfo.getCapabilitiesForType(effectiveCodec.mimeType) }.getOrNull()
      val videoCaps = caps?.videoCapabilities

      val wAlign = (videoCaps?.widthAlignment ?: 2).coerceAtLeast(2)
      val hAlign = (videoCaps?.heightAlignment ?: 2).coerceAtLeast(2)
      var alignedW = alignTo(requestedWidth.coerceAtLeast(128), wAlign)
      var alignedH = alignTo(requestedHeight.coerceAtLeast(128), hAlign)

      if (videoCaps != null) {
        alignedW =
          alignedW.coerceIn(
            videoCaps.supportedWidths.lower,
            videoCaps.supportedWidths.upper,
          )
        alignedH =
          alignedH.coerceIn(
            videoCaps.supportedHeights.lower,
            videoCaps.supportedHeights.upper,
          )
        alignedW = alignTo(alignedW, wAlign)
        alignedH = alignTo(alignedH, hAlign)
      }

      val effectiveBitrate =
        if (videoCaps != null) {
          val range = videoCaps.bitrateRange
          if (bitrateOption == VideoBitrateOption.LOSSLESS) {
            range.upper.coerceIn(range.lower, 250_000_000)
          } else {
            bitrateOption.targetBitsPerSecond.coerceIn(range.lower, range.upper)
          }
        } else {
          bitrateOption.targetBitsPerSecond
        }

      val effectiveFps =
        if (videoCaps != null) {
          val fpsRange =
            runCatching { videoCaps.getSupportedFrameRatesFor(alignedW, alignedH) }
              .getOrNull()
              ?.let { it.lower.toInt().coerceAtLeast(1)..it.upper.toInt().coerceAtLeast(30) }
              ?: (videoCaps.supportedFrameRates.lower.coerceAtLeast(1)..videoCaps.supportedFrameRates.upper.coerceAtLeast(30))
          fpsOption.fps.coerceIn(fpsRange.first, fpsRange.last)
        } else {
          fpsOption.fps
        }

      return EncoderSelection(
        codecName = chosenInfo.name,
        effectiveCodec = effectiveCodec,
        isHardwareAccelerated = isHardwareCodec(chosenInfo),
        chipsetFamily = chipsetFamily,
        effectiveBitrateBps = effectiveBitrate,
        effectiveFps = effectiveFps,
        alignedWidth = alignedW,
        alignedHeight = alignedH,
      )
    }

    private fun findPreferredCodecInfo(
      codecOption: VideoCodecOption,
      availableCodecs: List<MediaCodecInfo>,
      chipsetFamily: ChipsetFamily,
    ): MediaCodecInfo? {
      val matchingEncoders =
        availableCodecs.filter { info ->
          info.isEncoder &&
            info.supportedTypes.any { it.equals(codecOption.mimeType, true) } &&
            supportsSurfaceInput(info, codecOption.mimeType)
        }
      if (matchingEncoders.isEmpty()) return null

      val hardwareEncoders = matchingEncoders.filter { isHardwareCodec(it) }
      val pool = hardwareEncoders.ifEmpty { matchingEncoders }

      val preferredNames =
        when (codecOption) {
          VideoCodecOption.H265 ->
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
          VideoCodecOption.H264 ->
            when (chipsetFamily) {
              ChipsetFamily.SNAPDRAGON ->
                listOf(
                  InternalRecordingConfig.SNAPDRAGON_AVC_ENCODER,
                  InternalRecordingConfig.SNAPDRAGON_LEGACY_AVC_ENCODER,
                )
              ChipsetFamily.MEDIATEK ->
                listOf(
                  InternalRecordingConfig.MEDIATEK_AVC_ENCODER,
                  InternalRecordingConfig.MEDIATEK_LEGACY_AVC_ENCODER,
                )
              ChipsetFamily.OTHER ->
                listOf(
                  InternalRecordingConfig.SNAPDRAGON_AVC_ENCODER,
                  InternalRecordingConfig.MEDIATEK_AVC_ENCODER,
                )
            }
        }

      return preferredNames.firstNotNullOfOrNull { targetName ->
        pool.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
      }
        ?: pool.firstOrNull { it.name.startsWith("c2.", ignoreCase = true) }
        ?: pool.firstOrNull()
    }

    private fun supportsSurfaceInput(info: MediaCodecInfo, mimeType: String): Boolean {
      val caps = runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull() ?: return false
      return caps.colorFormats.contains(InternalRecordingConfig.SURFACE_COLOR_FORMAT)
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return !info.isSoftwareOnly
      }
      val name = info.name.lowercase()
      return !name.startsWith("omx.google.") &&
        !name.startsWith("c2.android.") &&
        !name.contains("sw")
    }

    private fun findCodecInfo(name: String): MediaCodecInfo? {
      return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull {
        it.name.equals(name, ignoreCase = true)
      }
    }

    private fun alignTo(value: Int, alignment: Int): Int {
      val rem = value % alignment
      return if (rem == 0) value else (value - rem).coerceAtLeast(alignment)
    }
  }
}
