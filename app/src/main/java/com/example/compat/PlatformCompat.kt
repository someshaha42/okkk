package com.example.compat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

data class EncoderCapabilities(
    val supportsHevc8Bit: Boolean,
    val supportsHevc10Bit: Boolean,
    val supportsAvc: Boolean,
    val supportsVulkanHardware: Boolean
)

object PlatformCompat {

    fun supportsInternalAudioCapture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun requiresNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(context: Context): Boolean {
        if (!requiresNotificationPermission()) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun queryEncoderCapabilities(context: Context): EncoderCapabilities {
        val supportsVulkanHardware = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
        ) || context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
        )

        var hevc8Bit = false
        var hevc10Bit = false
        var avc = false

        runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                        avc = true
                    }
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                        hevc8Bit = true
                        val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                        val hasMain10 = caps?.profileLevels?.any { pl ->
                            pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                        } == true
                        if (hasMain10 && !info.isSoftwareOnly) {
                            hevc10Bit = true
                        }
                    }
                }
            }
        }

        return EncoderCapabilities(
            supportsHevc8Bit = hevc8Bit,
            supportsHevc10Bit = hevc10Bit,
            supportsAvc = avc,
            supportsVulkanHardware = supportsVulkanHardware
        )
    }
}
