package com.example

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.example.color.ColorPipelinePlanner
import com.example.settings.ColorModeOption
import com.example.settings.RecordingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPipelineTest {

    @Test
    fun defaultColorModeIsOriginalColor() {
        val defaults = RecordingSettings()
        assertEquals(ColorModeOption.ORIGINAL_COLOR, defaults.colorMode)
        assertEquals(0, defaults.colorMode.shaderModeId)
    }

    @Test
    fun originalColorPlanUses1To1Passthrough() {
        val plan = ColorPipelinePlanner.buildPlan(
            colorMode = ColorModeOption.ORIGINAL_COLOR,
            supportsHevc10Bit = true,
            supportsHevc8Bit = true
        )
        assertEquals(0, plan.shaderModeIndex)
        assertEquals(8, plan.bitDepth)
        assertFalse(plan.use10BitPipeline)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, plan.colorStandard)
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, plan.colorRange)
    }

    @Test
    fun fullRgbPlanEnforcesFullColorRange() {
        val plan = ColorPipelinePlanner.buildPlan(
            colorMode = ColorModeOption.FULL_RGB,
            supportsHevc10Bit = true,
            supportsHevc8Bit = true
        )
        assertEquals(1, plan.shaderModeIndex)
        assertEquals(8, plan.bitDepth)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, plan.colorRange)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, plan.colorStandard)
    }

    @Test
    fun deepColor10BitUsesHevcMain10WhenSupported() {
        val plan = ColorPipelinePlanner.buildPlan(
            colorMode = ColorModeOption.DEEP_COLOR_10BIT,
            supportsHevc10Bit = true,
            supportsHevc8Bit = true
        )
        assertEquals(2, plan.shaderModeIndex)
        assertEquals(10, plan.bitDepth)
        assertTrue(plan.use10BitPipeline)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, plan.mimeType)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.codecProfile)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, plan.colorStandard)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, plan.colorRange)
        assertFalse(plan.encoderFallbackUsed)
    }

    @Test
    fun deepColor10BitFallsBackCleanlyWhenMain10Unavailable() {
        val plan = ColorPipelinePlanner.buildPlan(
            colorMode = ColorModeOption.DEEP_COLOR_10BIT,
            supportsHevc10Bit = false,
            supportsHevc8Bit = false
        )
        assertEquals(2, plan.shaderModeIndex)
        assertEquals(8, plan.bitDepth)
        assertFalse(plan.use10BitPipeline)
        assertNull(plan.codecProfile)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, plan.mimeType)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, plan.colorRange)
        assertTrue(plan.encoderFallbackUsed)
    }
}
