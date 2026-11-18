package com.example.muxer

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaMuxerManager(
    private val context: Context,
    private val expectAudioTrack: Boolean
) {
    private val lock = Object()
    private var muxer: MediaMuxer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null

    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var isStarted: Boolean = false
    private var isReleased: Boolean = false

    private var lastVideoPtsUs: Long = -1L
    private var lastAudioPtsUs: Long = -1L

    fun prepare(displayNamePrefix: String = "FrameCut"): Uri {
        synchronized(lock) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${displayNamePrefix}_${timestamp}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/FrameCut"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Failed to create MediaStore output file")

            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Failed to open output file descriptor")

            outputUri = uri
            fileDescriptor = pfd
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            return uri
        }
    }

    fun addVideoTrack(format: MediaFormat): Int {
        synchronized(lock) {
            val activeMuxer = muxer ?: return -1
            if (videoTrackIndex == -1 && !isStarted) {
                videoTrackIndex = activeMuxer.addTrack(format)
                tryStartMuxerLocked()
            }
            return videoTrackIndex
        }
    }

    fun addAudioTrack(format: MediaFormat): Int {
        synchronized(lock) {
            val activeMuxer = muxer ?: return -1
            if (audioTrackIndex == -1 && !isStarted) {
                audioTrackIndex = activeMuxer.addTrack(format)
                tryStartMuxerLocked()
            }
            return audioTrackIndex
        }
    }

    private fun tryStartMuxerLocked() {
        val activeMuxer = muxer ?: return
        if (isStarted || videoTrackIndex == -1) return
        if (expectAudioTrack && audioTrackIndex == -1) return
        activeMuxer.start()
        isStarted = true
    }

    fun forceStartIfVideoReady() {
        synchronized(lock) {
            val activeMuxer = muxer ?: return
            if (!isStarted && videoTrackIndex != -1) {
                activeMuxer.start()
                isStarted = true
            }
        }
    }

    fun writeVideoSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!isStarted || isReleased || videoTrackIndex == -1) return
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
            if (info.size <= 0) return

            val pts = if (info.presentationTimeUs <= lastVideoPtsUs) {
                lastVideoPtsUs + 1L
            } else {
                info.presentationTimeUs
            }
            lastVideoPtsUs = pts

            val adjustedInfo = MediaCodec.BufferInfo().apply {
                set(info.offset, info.size, pts, info.flags)
            }
            data.position(adjustedInfo.offset)
            data.limit(adjustedInfo.offset + adjustedInfo.size)
            runCatching {
                muxer?.writeSampleData(videoTrackIndex, data, adjustedInfo)
            }.onFailure {
                Log.w(TAG, "writeVideoSample failed: ${it.message}")
            }
        }
    }

    fun writeAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!isStarted || isReleased || audioTrackIndex == -1) return
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
            if (info.size <= 0) return

            val pts = if (info.presentationTimeUs <= lastAudioPtsUs) {
                lastAudioPtsUs + 1L
            } else {
                info.presentationTimeUs
            }
            lastAudioPtsUs = pts

            val adjustedInfo = MediaCodec.BufferInfo().apply {
                set(info.offset, info.size, pts, info.flags)
            }
            data.position(adjustedInfo.offset)
            data.limit(adjustedInfo.offset + adjustedInfo.size)
            runCatching {
                muxer?.writeSampleData(audioTrackIndex, data, adjustedInfo)
            }.onFailure {
                Log.w(TAG, "writeAudioSample failed: ${it.message}")
            }
        }
    }

    fun stopAndPublish(): Uri? {
        synchronized(lock) {
            if (isReleased) return outputUri
            isReleased = true
            var validOutput = false

            runCatching {
                if (isStarted) {
                    muxer?.stop()
                    validOutput = lastVideoPtsUs >= 0L
                }
            }.onFailure {
                Log.e(TAG, "Muxer stop error: ${it.message}")
            }

            runCatching { muxer?.release() }
            runCatching { fileDescriptor?.close() }
            muxer = null
            fileDescriptor = null

            val uri = outputUri
            if (uri != null) {
                val resolver = context.contentResolver
                if (validOutput) {
                    val publish = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    runCatching { resolver.update(uri, publish, null, null) }
                } else {
                    runCatching { resolver.delete(uri, null, null) }
                    outputUri = null
                    return null
                }
            }
            return outputUri
        }
    }

    companion object {
        private const val TAG = "MediaMuxerManager"
    }
}
