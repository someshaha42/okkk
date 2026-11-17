package com.example

import com.example.region.RegionSelectionManager
import com.example.settings.AudioSourceOption
import com.example.settings.NormalizedRegion
import com.example.settings.OrientationMode
import com.example.settings.RecordingMode
import com.example.settings.RecordingSettings
import com.example.settings.RegionAnimationOption
import com.example.settings.VideoBitrateOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderCoreUnitTest {

  @Test
  fun `default settings match specification`() {
    val defaults = RecordingSettings()
    assertEquals(RecordingMode.FULL_SCREEN, defaults.recordingMode)
    assertEquals(OrientationMode.NATIVE, defaults.orientationMode)
    assertEquals(AudioSourceOption.INTERNAL, defaults.audioSource)
    assertEquals(VideoBitrateOption.LOSSLESS, defaults.videoBitrate)
    assertEquals(RegionAnimationOption.SHOW, defaults.regionAnimation)
  }

  @Test
  fun `orientation mode resolves native, landscape fixed, and portrait fixed dimensions cleanly`() {
    val nativePortrait =
      RegionSelectionManager.resolveFullScreenDimensions(1081, 2401, OrientationMode.NATIVE)
    assertEquals(1080 to 2400, nativePortrait)

    val nativeLandscape =
      RegionSelectionManager.resolveFullScreenDimensions(2400, 1080, OrientationMode.NATIVE)
    assertEquals(2400 to 1080, nativeLandscape)

    val landscapeFromPortrait =
      RegionSelectionManager.resolveFullScreenDimensions(
        1080,
        2400,
        OrientationMode.LANDSCAPE_FIXED,
      )
    assertEquals(2400 to 1080, landscapeFromPortrait)

    val portraitFromLandscape =
      RegionSelectionManager.resolveFullScreenDimensions(
        2400,
        1080,
        OrientationMode.PORTRAIT_FIXED,
      )
    assertEquals(1080 to 2400, portraitFromLandscape)
  }

  @Test
  fun `pixel region produces even H264 dimensions within screen bounds`() {
    val region = NormalizedRegion(left = 0.133f, top = 0.217f, right = 0.871f, bottom = 0.779f)
    val pixel = RegionSelectionManager.toPixelRegion(region, screenWidth = 1081, screenHeight = 2401)

    assertEquals(0, pixel.width % 2)
    assertEquals(0, pixel.height % 2)
    assertTrue(pixel.left >= 0)
    assertTrue(pixel.top >= 0)
    assertTrue(pixel.right <= pixel.screenWidth)
    assertTrue(pixel.bottom <= pixel.screenHeight)
    assertTrue(pixel.width >= 128)
    assertTrue(pixel.height >= 128)
  }

  @Test
  fun `normalized region clamps invalid coordinates safely`() {
    val invalid = NormalizedRegion(left = -0.5f, top = 0.95f, right = 0.0f, bottom = 1.4f)
    val clean = invalid.normalized()

    assertTrue(clean.left >= 0f)
    assertTrue(clean.top >= 0f)
    assertTrue(clean.right <= 1f)
    assertTrue(clean.bottom <= 1f)
    assertTrue(clean.right - clean.left >= 0.119f)
    assertTrue(clean.bottom - clean.top >= 0.119f)
  }
}
