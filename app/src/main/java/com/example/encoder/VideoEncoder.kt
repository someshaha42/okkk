package com.example.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.example.color.ColorPipelinePlan
import com.example.muxer.MediaMuxerManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val colorPlan: ColorPipelinePlan,
    private val muxerManager: MediaMuxerManager
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val pausedOffsetUs = AtomicLong(0L)
    private var pauseStartNs: Long = 0L
    private var firstPtsUs: Long = -1L

    var activePlan: ColorPipelinePlan = colorPlan
        private set

    fun prepare(): Surface {
        val primaryPlan = colorPlan
        val codecAndSurface = tryConfigureCodec(primaryPlan) ?: run {
            // Safe hardware fallback to 8-bit AVC if HEVC Main10 or HEVC fails on current device
            val fallbackPlan = primaryPlan.copy(
                mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
                use10BitPipeline = false,
                codecProfile = null,
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                bitDepth = 8,
                encoderFallbackUsed = true
            )
            activePlan = fallbackPlan
            tryConfigureCodec(fallbackPlan)
                ?: throw IllegalStateException("Unable to configure MediaCodec video encoder")
        }

        codec = codecAndSurface.first
        inputSurface = codecAndSurface.second
        return codecAndSurface.second
    }

    private fun tryConfigureCodec(plan: ColorPipelinePlan): Pair<MediaCodec, Surface>? {
        return runCatching {
            val format = MediaFormat.createVideoFormat(plan.mimeType, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, plan.colorStandard)
                setInteger(MediaFormat.KEY_COLOR_RANGE, plan.colorRange)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, plan.colorTransfer)
                plan.codecProfile?.let { profile ->
                    setInteger(MediaFormat.KEY_PROFILE, profile)
                }
            }
            val encoder = MediaCodec.createEncoderByType(plan.mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder to surface
        }.onFailure {
            Log.w(TAG, "Codec configuration failed for ${plan.mimeType} (${plan.bitDepth}-bit): ${it.message}")
        }.getOrNull()
    }

    fun start() {
        val activeCodec = codec ?: return
        running.set(true)
        firstPtsUs = -1L
        pausedOffsetUs.set(0L)
        activeCodec.start()

        drainThread = Thread({
            drainLoop(activeCodec)
        }, "FrameCut-VideoDrain").apply { start() }
    }

    fun setPaused(isPaused: Boolean) {
        if (paused.compareAndSet(!isPaused, isPaused)) {
            if (isPaused) {
                pauseStartNs = System.nanoTime()
            } else if (pauseStartNs > 0L) {
                val deltaUs = (System.nanoTime() - pauseStartNs) / 1000L
                if (deltaUs > 0L) {
                    pausedOffsetUs.addAndGet(deltaUs)
                }
                pauseStartNs = 0L
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { codec?.signalEndOfInputStream() }
        runCatching { drainThread?.join(2500L) }
        drainThread = null

        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null
        inputSurface = null
    }

    private fun drainLoop(activeCodec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val timeoutUs = if (running.get()) 10_000L else 5_000L
            val outputIndex = try {
                activeCodec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            } catch (t: Throwable) {
                Log.w(TAG, "dequeueOutputBuffer error: ${t.message}")
                break
            }

            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerManager.addVideoTrack(activeCodec.outputFormat)
                }

                outputIndex >= 0 -> {
                    val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        if (firstPtsUs < 0L) {
                            firstPtsUs = bufferInfo.presentationTimeUs
                        }
                        val normalizedPtsUs = (
                            bufferInfo.presentationTimeUs - firstPtsUs - pausedOffsetUs.get()
                            ).coerceAtLeast(0L)

                        val normalizedInfo = MediaCodec.BufferInfo().apply {
                            set(bufferInfo.offset, bufferInfo.size, normalizedPtsUs, bufferInfo.flags)
                        }
                        muxerManager.writeVideoSample(outputBuffer, normalizedInfo)
                    }
                    activeCodec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!running.get()) {
                        // Continue draining until EOS or timeout
                        Thread.sleep(5L)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}
