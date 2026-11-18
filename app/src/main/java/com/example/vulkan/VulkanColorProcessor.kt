package com.example.vulkan

import android.hardware.HardwareBuffer
import android.view.Surface
import com.example.settings.ColorModeOption
import com.example.settings.RegionRectPx
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class VulkanDeviceInfo(
    val initialized: Boolean,
    val gpuName: String,
    val apiVersion: String,
    val zeroCopyAhbSupported: Boolean,
    val tenBitTargetSupported: Boolean
)

/**
 * Permanent Vulkan Graphics & Compute pipeline bridge.
 *
 * Executes texture sampling, region cropping, scaling, and the three color modes
 * (Original Color, Full RGB, Deep Color 10-bit) via a single GLSL 450 Vulkan compute shader,
 * outputting simultaneously to both the Live Preview Surface and the MediaCodec input Surface.
 */
class VulkanColorProcessor {

    private var nativeHandle: Long = 0L

    @Synchronized
    fun initialize(): VulkanDeviceInfo {
        if (nativeHandle == 0L) {
            nativeHandle = nativeCreate()
        }
        val rawInfo = if (nativeHandle != 0L) nativeGetInfo(nativeHandle) else "UNAVAILABLE|0.0|0|0"
        val parts = rawInfo.split("|")
        return VulkanDeviceInfo(
            initialized = nativeHandle != 0L && parts.getOrNull(0) != "UNAVAILABLE",
            gpuName = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Software Fallback",
            apiVersion = parts.getOrNull(1) ?: "1.1",
            zeroCopyAhbSupported = parts.getOrNull(2) == "1",
            tenBitTargetSupported = parts.getOrNull(3) == "1"
        )
    }

    @Synchronized
    fun setPreviewSurface(surface: Surface?, width: Int, height: Int, prefer10Bit: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetPreviewSurface(nativeHandle, surface, width, height, prefer10Bit)
    }

    @Synchronized
    fun setEncoderSurface(surface: Surface?, width: Int, height: Int, prefer10Bit: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetEncoderSurface(nativeHandle, surface, width, height, prefer10Bit)
    }

    @Synchronized
    fun processHardwareBuffer(
        buffer: HardwareBuffer,
        srcWidth: Int,
        srcHeight: Int,
        cropRect: RegionRectPx,
        dstWidth: Int,
        dstHeight: Int,
        colorMode: ColorModeOption,
        frameIndex: Long
    ): Boolean {
        if (nativeHandle == 0L) return false
        val u0 = (cropRect.left.toFloat() / srcWidth.toFloat()).coerceIn(0f, 1f)
        val v0 = (cropRect.top.toFloat() / srcHeight.toFloat()).coerceIn(0f, 1f)
        val uw = (cropRect.width.toFloat() / srcWidth.toFloat()).coerceIn(0.01f, 1f - u0)
        val vh = (cropRect.height.toFloat() / srcHeight.toFloat()).coerceIn(0.01f, 1f - v0)

        return nativeProcessHardwareBuffer(
            nativeHandle,
            buffer,
            u0,
            v0,
            uw,
            vh,
            dstWidth,
            dstHeight,
            colorMode.shaderModeId,
            frameIndex.toInt()
        )
    }

    @Synchronized
    fun processRgbaBuffer(
        rgbaBytes: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        colorMode: ColorModeOption,
        frameIndex: Long
    ): Boolean {
        if (nativeHandle == 0L) return false
        return nativeProcessRgbaBuffer(
            nativeHandle,
            rgbaBytes,
            srcWidth,
            srcHeight,
            0f,
            0f,
            1f,
            1f,
            dstWidth,
            dstHeight,
            colorMode.shaderModeId,
            frameIndex.toInt()
        )
    }

    @Synchronized
    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("framecut_vulkan")
        }

        /**
         * Generates an SMPTE-inspired + smooth 10-bit gradient + skin-tone calibration frame
         * fed into the exact same Vulkan compute shader when the live preview is idle.
         */
        fun buildCalibrationPattern(width: Int = 512, height: Int = 288): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            val barColors = arrayOf(
                intArrayOf(235, 235, 235),
                intArrayOf(225, 200, 36),
                intArrayOf(38, 208, 212),
                intArrayOf(56, 198, 72),
                intArrayOf(202, 54, 198),
                intArrayOf(220, 42, 42),
                intArrayOf(44, 72, 222)
            )
            for (y in 0 until height) {
                val fy = y.toFloat() / height.toFloat()
                for (x in 0 until width) {
                    val fx = x.toFloat() / width.toFloat()
                    val r: Int
                    val g: Int
                    val b: Int
                    when {
                        fy < 0.56f -> {
                            val barIdx = (fx * barColors.size).toInt().coerceIn(0, barColors.lastIndex)
                            r = barColors[barIdx][0]
                            g = barColors[barIdx][1]
                            b = barColors[barIdx][2]
                        }
                        fy < 0.78f -> {
                            // Smooth full-range gradient band to demonstrate 8-bit vs 10-bit banding & Full RGB shadows
                            val v = (fx * 255f).toInt().coerceIn(0, 255)
                            r = v
                            g = (v * 0.92f).toInt()
                            b = (v * 0.85f).toInt()
                        }
                        else -> {
                            // Studio dark floor + saturated crimson/emerald/cobalt swatches
                            when {
                                fx < 0.25f -> { r = 12; g = 14; b = 20 }
                                fx < 0.50f -> { r = 218; g = 152; b = 124 }
                                fx < 0.75f -> { r = 16; g = 185; b = 129 }
                                else -> { r = 220; g = 38; b = 38 }
                            }
                        }
                    }
                    buf.put(r.toByte())
                    buf.put(g.toByte())
                    buf.put(b.toByte())
                    buf.put(0xFF.toByte())
                }
            }
            buf.flip()
            return buf
        }

        @JvmStatic
        private external fun nativeCreate(): Long

        @JvmStatic
        private external fun nativeGetInfo(handle: Long): String

        @JvmStatic
        private external fun nativeSetPreviewSurface(
            handle: Long,
            surface: Surface?,
            width: Int,
            height: Int,
            prefer10Bit: Boolean
        )

        @JvmStatic
        private external fun nativeSetEncoderSurface(
            handle: Long,
            surface: Surface?,
            width: Int,
            height: Int,
            prefer10Bit: Boolean
        )

        @JvmStatic
        private external fun nativeProcessHardwareBuffer(
            handle: Long,
            hardwareBuffer: HardwareBuffer,
            u0: Float,
            v0: Float,
            uw: Float,
            vh: Float,
            dstWidth: Int,
            dstHeight: Int,
            colorMode: Int,
            frameIndex: Int
        ): Boolean

        @JvmStatic
        private external fun nativeProcessRgbaBuffer(
            handle: Long,
            rgbaBytes: ByteBuffer,
            srcWidth: Int,
            srcHeight: Int,
            u0: Float,
            v0: Float,
            uw: Float,
            vh: Float,
            dstWidth: Int,
            dstHeight: Int,
            colorMode: Int,
            frameIndex: Int
        ): Boolean

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}
