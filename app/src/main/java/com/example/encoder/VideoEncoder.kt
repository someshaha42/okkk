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
)

/**
 * Hardware-first H.264/AVC MediaCodec encoder with chipset detection (Snapdragon c2.qti.avc.encoder,
 * MediaTek c2.mtk.avc.encoder), automatic capability clamping, and dedicated surface input.
 */
class VideoEncoder(
  private val requestedWidth: Int,
  private val requestedHeight: Int,
  private val bitrateOption: VideoBitrateOption,
  private val muxerManager: MediaMuxerManager,
) {
  private var mediaCodec: MediaCodec? = null
  private var inputSurface: Surface? = null
  private var drainThread: Thread? = null
  private val isRunning = AtomicBoolean(false)
  private var selection: EncoderSelection? = null

  fun prepare(): EncoderSelection {
    val selected = selectBestEncoder(requestedWidth, requestedHeight, bitrateOption)
    selection = selected

    val format =
      MediaFormat.createVideoFormat(
          InternalRecordingConfig.VIDEO_MIME_TYPE,
          selected.alignedWidth,
          selected.alignedHeight,
        )
        .apply {
          setInteger(MediaFormat.KEY_COLOR_FORMAT, InternalRecordingConfig.SURFACE_COLOR_FORMAT)
          setInteger(MediaFormat.KEY_BIT_RATE, selected.effectiveBitrateBps)
          setInteger(MediaFormat.KEY_FRAME_RATE, InternalRecordingConfig.TARGET_FPS)
          setInteger(MediaFormat.KEY_CAPTURE_RATE, InternalRecordingConfig.TARGET_FPS)
          setInteger(
            MediaFormat.KEY_I_FRAME_INTERVAL,
            InternalRecordingConfig.I_FRAME_INTERVAL_SECONDS,
          )
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
          }
          configureBitrateModeAndProfile(this, selected)
        }

    val codec =
      runCatching { MediaCodec.createByCodecName(selected.codecName) }
        .getOrElse {
          Log.w(TAG, "Preferred codec ${selected.codecName} failed to instantiate, using default", it)
          MediaCodec.createEncoderByType(InternalRecordingConfig.VIDEO_MIME_TYPE)
        }

    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    } catch (e: Exception) {
      Log.w(TAG, "Primary format config rejected; retrying safe baseline config", e)
      val safeFormat =
        MediaFormat.createVideoFormat(
            InternalRecordingConfig.VIDEO_MIME_TYPE,
            selected.alignedWidth,
            selected.alignedHeight,
          )
          .apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, InternalRecordingConfig.SURFACE_COLOR_FORMAT)
            setInteger(
              MediaFormat.KEY_BIT_RATE,
              selected.effectiveBitrateBps.coerceAtMost(50_000_000),
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, InternalRecordingConfig.TARGET_FPS)
            setInteger(
              MediaFormat.KEY_I_FRAME_INTERVAL,
              InternalRecordingConfig.I_FRAME_INTERVAL_SECONDS,
            )
          }
      codec.configure(safeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

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
      "VideoEncoder started: codec=${selected.codecName}, hw=${selected.isHardwareAccelerated}, " +
        "chipset=${selected.chipsetFamily}, size=${selected.alignedWidth}x${selected.alignedHeight}, " +
        "bitrate=${selected.effectiveBitrateBps / 1_000_000}Mbps",
    )
    return selected
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
    val caps = runCatching { info.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }.getOrNull()
      ?: return

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
      val highProfile =
        caps.profileLevels?.filter {
          it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        }?.maxByOrNull { it.level }
      if (highProfile != null) {
        format.setInteger(MediaFormat.KEY_PROFILE, highProfile.profile)
        format.setInteger(MediaFormat.KEY_LEVEL, highProfile.level)
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
      bitrateOption: VideoBitrateOption,
      availableCodecs: List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList(),
      chipsetFamily: ChipsetFamily = detectChipsetFamily(),
    ): EncoderSelection {
      val avcEncoders =
        availableCodecs.filter { info ->
          info.isEncoder &&
            info.supportedTypes.any { it.equals(InternalRecordingConfig.VIDEO_MIME_TYPE, true) } &&
            supportsSurfaceInput(info)
        }

      val hardwareEncoders = avcEncoders.filter { isHardwareCodec(it) }
      val pool = hardwareEncoders.ifEmpty { avcEncoders }

      val preferredNames =
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

      val chosenInfo =
        preferredNames.firstNotNullOfOrNull { targetName ->
          pool.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
        }
          ?: pool.firstOrNull { it.name.startsWith("c2.", ignoreCase = true) }
          ?: pool.firstOrNull()

      if (chosenInfo == null) {
        val evenW = alignTo(requestedWidth.coerceAtLeast(128), 2)
        val evenH = alignTo(requestedHeight.coerceAtLeast(128), 2)
        return EncoderSelection(
          codecName = "default-avc",
          isHardwareAccelerated = false,
          chipsetFamily = chipsetFamily,
          effectiveBitrateBps = bitrateOption.targetBitsPerSecond.coerceAtMost(50_000_000),
          alignedWidth = evenW,
          alignedHeight = evenH,
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

      return EncoderSelection(
        codecName = chosenInfo.name,
        isHardwareAccelerated = isHardwareCodec(chosenInfo),
        chipsetFamily = chipsetFamily,
        effectiveBitrateBps = effectiveBitrate,
        alignedWidth = alignedW,
        alignedHeight = alignedH,
      )
    }

    private fun supportsSurfaceInput(info: MediaCodecInfo): Boolean {
      val caps =
        runCatching { info.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }
          .getOrNull() ?: return false
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
