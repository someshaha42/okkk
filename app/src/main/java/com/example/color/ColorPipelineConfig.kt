package com.example.color

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.example.settings.ColorModeOption

data class ColorPipelinePlan(
    val colorMode: ColorModeOption,
    val shaderModeIndex: Int,
    val mimeType: String,
    val use10BitPipeline: Boolean,
    val codecProfile: Int?,
    val colorStandard: Int,
    val colorRange: Int,
    val colorTransfer: Int,
    val bitDepth: Int,
    val previewParityTag: String,
    val encoderFallbackUsed: Boolean
) {
    val summaryLabel: String
        get() = when (colorMode) {
            ColorModeOption.ORIGINAL_COLOR -> "Vulkan 1:1 Passthrough • BT.709 8-bit"
            ColorModeOption.FULL_RGB -> "Vulkan Full RGB (0–255) • BT.709 Full Range"
            ColorModeOption.DEEP_COLOR_10BIT -> if (use10BitPipeline) {
                "Vulkan Deep Color • HEVC Main10 10-bit"
            } else {
                "Vulkan Deep Color • 10-bit Dithered 8-bit Fallback"
            }
        }
}

object ColorPipelinePlanner {

    fun buildPlan(
        colorMode: ColorModeOption,
        supportsHevc10Bit: Boolean,
        supportsHevc8Bit: Boolean = true
    ): ColorPipelinePlan {
        return when (colorMode) {
            ColorModeOption.ORIGINAL_COLOR -> ColorPipelinePlan(
                colorMode = colorMode,
                shaderModeIndex = colorMode.shaderModeId,
                mimeType = if (supportsHevc8Bit) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
                use10BitPipeline = false,
                codecProfile = null,
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                bitDepth = 8,
                previewParityTag = "Vulkan Compute -> Preview & MediaCodec (Original 1:1)",
                encoderFallbackUsed = false
            )

            ColorModeOption.FULL_RGB -> ColorPipelinePlan(
                colorMode = colorMode,
                shaderModeIndex = colorMode.shaderModeId,
                mimeType = if (supportsHevc8Bit) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
                use10BitPipeline = false,
                codecProfile = null,
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                bitDepth = 8,
                previewParityTag = "Vulkan Compute -> Preview & MediaCodec (Full RGB 0-255)",
                encoderFallbackUsed = false
            )

            ColorModeOption.DEEP_COLOR_10BIT -> {
                if (supportsHevc10Bit) {
                    ColorPipelinePlan(
                        colorMode = colorMode,
                        shaderModeIndex = colorMode.shaderModeId,
                        mimeType = MediaFormat.MIMETYPE_VIDEO_HEVC,
                        use10BitPipeline = true,
                        codecProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                        colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                        colorRange = MediaFormat.COLOR_RANGE_FULL,
                        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                        bitDepth = 10,
                        previewParityTag = "Vulkan Compute 16F -> Preview & HEVC Main10 (10-bit)",
                        encoderFallbackUsed = false
                    )
                } else {
                    ColorPipelinePlan(
                        colorMode = colorMode,
                        shaderModeIndex = colorMode.shaderModeId,
                        mimeType = if (supportsHevc8Bit) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
                        use10BitPipeline = false,
                        codecProfile = null,
                        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                        colorRange = MediaFormat.COLOR_RANGE_FULL,
                        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                        bitDepth = 8,
                        previewParityTag = "Vulkan Compute 16F + IGN Dither -> Preview & MediaCodec",
                        encoderFallbackUsed = true
                    )
                }
            }
        }
    }
}
