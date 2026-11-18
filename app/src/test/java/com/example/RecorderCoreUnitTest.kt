package com.example

import com.example.region.AspectRatioPreset
import com.example.region.RegionSelectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderCoreUnitTest {

    @Test
    fun enforceEvenDimensionsAlignsAllCoordinatesAndSizes() {
        val rect = RegionSelectionManager.enforceEvenDimensions(
            rawLeft = 101,
            rawTop = 203,
            rawWidth = 799,
            rawHeight = 451,
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertEquals(0, rect.left % 2)
        assertEquals(0, rect.top % 2)
        assertEquals(0, rect.width % 2)
        assertEquals(0, rect.height % 2)
        assertTrue(rect.right <= 1080)
        assertTrue(rect.bottom <= 2400)
    }

    @Test
    fun computeOutputDimensionsMaintainsEvenEncoderSizes() {
        val (w, h) = RegionSelectionManager.computeOutputDimensions(
            sourceWidth = 1080,
            sourceHeight = 2400,
            scaleFactor = 0.75f
        )
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        assertTrue(w >= 128 && h >= 128)
    }

    @Test
    fun defaultAspectRatioPresetsStayInsideScreenBounds() {
        AspectRatioPreset.entries.forEach { preset ->
            val rect = RegionSelectionManager.defaultRegion(1080, 2400, preset)
            assertTrue(rect.left >= 0 && rect.top >= 0)
            assertTrue(rect.right <= 1080 && rect.bottom <= 2400)
            assertEquals(0, rect.width % 2)
            assertEquals(0, rect.height % 2)
        }
    }
}
