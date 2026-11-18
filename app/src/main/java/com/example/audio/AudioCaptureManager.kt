package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.example.muxer.MediaMuxerManager
import com.example.settings.AudioSourceOption
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureManager(
    private val context: Context,
    private val sourceOption: AudioSourceOption,
    private val mediaProjection: MediaProjection?,
    private val muxerManager: MediaMuxerManager
) {
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var captureThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (sourceOption == AudioSourceOption.NONE) return false

        val sampleRate = 48_000
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            .coerceAtLeast(4096) * 2

        val recorder = try {
            if (sourceOption == AudioSourceOption.INTERNAL &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                mediaProjection != null
            ) {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .build()
            } else {
                val mic = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    encoding,
                    minBuf
                )
                preferExternalMicrophone(mic)
                mic
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord creation failed: ${t.message}")
            null
        }

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            muxerManager.forceStartIfVideoReady()
            return false
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            2
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf)
        }

        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AAC MediaCodec configure failed: ${t.message}")
            runCatching { recorder.release() }
            muxerManager.forceStartIfVideoReady()
            return false
        }

        audioRecord = recorder
        audioCodec = encoder
        running.set(true)
        recorder.startRecording()

        captureThread = Thread({
            encodeLoop(recorder, encoder, minBuf)
        }, "FrameCut-AudioCapture").apply { start() }
        return true
    }

    private fun preferExternalMicrophone(recorder: AudioRecord) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val external = inputs.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (external != null) {
            runCatching { recorder.setPreferredDevice(external) }
        }
    }

    fun setPaused(isPaused: Boolean) {
        paused.set(isPaused)
    }

    fun stop() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        runCatching { captureThread?.join(1500L) }
        captureThread = null
        runCatching { audioRecord?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioRecord = null
        audioCodec = null
    }

    private fun encodeLoop(
        recorder: AudioRecord,
        encoder: MediaCodec,
        bufferSize: Int
    ) {
        val pcmBuffer = ByteArray(bufferSize / 2)
        val bufferInfo = MediaCodec.BufferInfo()
        val startNs = System.nanoTime()
        var totalFramesWritten = 0L

        while (running.get()) {
            val readBytes = recorder.read(pcmBuffer, 0, pcmBuffer.size)
            if (readBytes <= 0) continue

            if (paused.get()) {
                continue
            }

            val inputIdx = encoder.dequeueInputBuffer(10_000L)
            if (inputIdx >= 0) {
                val inputBuf = encoder.getInputBuffer(inputIdx)
                if (inputBuf != null) {
                    inputBuf.clear()
                    val toPut = minOf(readBytes, inputBuf.remaining())
                    inputBuf.put(pcmBuffer, 0, toPut)
                    val ptsUs = (totalFramesWritten * 1_000_000L) / 48_000L
                    totalFramesWritten += (toPut / 4) // stereo 16-bit = 4 bytes/frame
                    val fallbackPtsUs = (System.nanoTime() - startNs) / 1000L
                    encoder.queueInputBuffer(
                        inputIdx,
                        0,
                        toPut,
                        maxOf(ptsUs, fallbackPtsUs.coerceAtMost(ptsUs + 50_000L)),
                        0
                    )
                }
            }

            drainAudioOutput(encoder, bufferInfo)
        }

        // Send EOS
        val eosIdx = runCatching { encoder.dequeueInputBuffer(10_000L) }.getOrDefault(-1)
        if (eosIdx >= 0) {
            runCatching {
                encoder.queueInputBuffer(eosIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        drainAudioOutput(encoder, bufferInfo, drainingEnd = true)
    }

    private fun drainAudioOutput(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        drainingEnd: Boolean = false
    ) {
        var iterations = 0
        while (iterations < 32) {
            iterations++
            val outIdx = runCatching { encoder.dequeueOutputBuffer(bufferInfo, 2_000L) }.getOrDefault(-1)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerManager.addAudioTrack(encoder.outputFormat)
                }
                outIdx >= 0 -> {
                    val outBuf = encoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        muxerManager.writeAudioSample(outBuf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!drainingEnd) return
                }
                else -> return
            }
        }
    }

    companion object {
        private const val TAG = "AudioCaptureManager"
    }
}
