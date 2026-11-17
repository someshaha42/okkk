package com.example

import android.content.Context
import android.hardware.DataSpace
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import com.example.color.ChromaSubsampling
import com.example.color.ColorPipelinePlan
import com.example.color.ColorPipelinePlanner
import com.example.color.ColorRangeMode
import com.example.color.DisplayColorProfile
import com.example.color.HardwareColorCapabilities
import com.example.color.PixelFormatMode
import com.example.color.ShaderColorTransform
import com.example.color.SourceColorSpace
import com.example.encoder.ChipsetFamily
import com.example.encoder.VideoEncoder
import com.example.settings.VideoBitrateOption
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ColorPipelineTest {

  @Test
  fun `color conversion matrices preserve D65 white point in column-major layout`() {
    val matrices =
      listOf(
        ColorPipelinePlanner.IDENTITY_MATRIX_COL_MAJOR,
        ColorPipelinePlanner.DISPLAY_P3_TO_BT709_COL_MAJOR,
        ColorPipelinePlanner.DISPLAY_P3_TO_BT2020_COL_MAJOR,
        ColorPipelinePlanner.BT709_TO_BT2020_COL_MAJOR,
        ColorPipelinePlanner.BT2020_TO_BT709_COL_MAJOR,
      )

    for (m in matrices) {
      assertEquals(9, m.size)
      // OpenGL mat3 is column-major: row r comes from indices r, r+3, r+6.
      for (row in 0..2) {
        val whiteOutput = m[row] + m[3 + row] + m[6 + row]
        assertTrue(
          "Expected white-point preservation on row $row, got $whiteOutput",
          abs(whiteOutput - 1f) < 1e-4f,
        )
      }
    }
  }

  @Test
  fun `detectBestChromaSubsampling selects highest hardware supported chroma format`() {
    assertEquals(
      ChromaSubsampling.YUV_444,
      ColorPipelinePlanner.detectBestChromaSubsampling(
        setOf(
          MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
          MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
          MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Flexible,
        ),
      ),
    )

    assertEquals(
      ChromaSubsampling.YUV_422,
      ColorPipelinePlanner.detectBestChromaSubsampling(
        setOf(
          MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
          MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP210,
        ),
      ),
    )

    assertEquals(
      ChromaSubsampling.YUV_420,
      ColorPipelinePlanner.detectBestChromaSubsampling(
        setOf(
          MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
          MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        ),
      ),
    )
  }

  @Test
  fun `buildFallbackPlans prioritizes 10bit HDR and WCG full range down to 8bit BT709 limited`() {
    val hdrWcgDisplay =
      DisplayColorProfile(
        sourceColorSpace = SourceColorSpace.DISPLAY_P3,
        isWideColorGamut = true,
        isHdr = true,
        supportsHdr10 = true,
        supportsHlg = false,
      )
    val highEndCaps =
      HardwareColorCapabilities(
        supportsMain10Profile = true,
        supportsHdr10Profile = true,
        supportsHdrEditing = true,
        supportsHlgEditing = false,
        supportsQpBounds = true,
        supports10BitEglSurface = true,
        supportsDisplayP3PassthroughEgl = true,
        bestChromaSubsampling = ChromaSubsampling.YUV_422,
        supports10BitYuvInput = true,
      )

    val plans =
      ColorPipelinePlanner.buildFallbackPlans(
        displayProfile = hdrWcgDisplay,
        hwCaps = highEndCaps,
        codecInfo = null,
        targetBitrateBps = 150_000_000,
      )

    assertTrue(plans.size >= 4)

    val primary = plans.first()
    assertEquals(PixelFormatMode.RGBA_1010102, primary.pixelFormatMode)
    assertEquals(MediaFormat.COLOR_STANDARD_BT2020, primary.colorStandard)
    assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, primary.colorTransfer)
    assertEquals(ColorRangeMode.FULL, primary.colorRange)
    assertEquals(ChromaSubsampling.YUV_422, primary.expectedChroma)
    assertTrue(primary.useQpBounds)

    val fallback = plans.last()
    assertEquals(PixelFormatMode.RGBA_8888, fallback.pixelFormatMode)
    assertEquals(MediaFormat.COLOR_STANDARD_BT709, fallback.colorStandard)
    assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, fallback.colorTransfer)
    assertEquals(ColorRangeMode.LIMITED, fallback.colorRange)
    assertFalse(fallback.useQpBounds)
  }

  @Test
  fun `buildFallbackPlans defaults to 8bit full then limited when 10bit EGL is unavailable`() {
    val sdrDisplay = ColorPipelinePlanner.defaultSdrDisplayProfile()
    val capsNo10BitEgl =
      HardwareColorCapabilities(
        supportsMain10Profile = true,
        supportsHdr10Profile = false,
        supportsHdrEditing = false,
        supportsHlgEditing = false,
        supportsQpBounds = true,
        supports10BitEglSurface = false,
        supportsDisplayP3PassthroughEgl = false,
        bestChromaSubsampling = ChromaSubsampling.YUV_420,
        supports10BitYuvInput = false,
      )

    val plans =
      ColorPipelinePlanner.buildFallbackPlans(
        displayProfile = sdrDisplay,
        hwCaps = capsNo10BitEgl,
        codecInfo = null,
        targetBitrateBps = 150_000_000,
      )

    assertEquals(2, plans.size)
    assertEquals(PixelFormatMode.RGBA_8888, plans[0].pixelFormatMode)
    assertEquals(ColorRangeMode.FULL, plans[0].colorRange)
    assertEquals(PixelFormatMode.RGBA_8888, plans[1].pixelFormatMode)
    assertEquals(ColorRangeMode.LIMITED, plans[1].colorRange)
  }

  @Test
  fun `resolveFrameShaderColorPlan matches gamut and HDR transforms accurately`() {
    val bt709EncoderPlan =
      ColorPipelinePlan(
        hevcProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        hevcLevel = null,
        pixelFormatMode = PixelFormatMode.RGBA_8888,
        expectedChroma = ChromaSubsampling.YUV_420,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorRange = ColorRangeMode.FULL,
        useDisplayP3PassthroughEgl = false,
        useQpBounds = false,
      )

    // Matching sRGB / BT.709 -> bit-exact passthrough
    val srgbPass =
      ColorPipelinePlanner.resolveFrameShaderColorPlan(
        DataSpace.DATASPACE_SRGB,
        bt709EncoderPlan,
      )
    assertEquals(ShaderColorTransform.PASSTHROUGH, srgbPass.transformMode)

    // Display P3 source without EGL passthrough -> accurate P3->BT.709 linear matrix
    val p3ToBt709 =
      ColorPipelinePlanner.resolveFrameShaderColorPlan(
        DataSpace.DATASPACE_DISPLAY_P3,
        bt709EncoderPlan,
      )
    assertEquals(ShaderColorTransform.GAMUT_MATRIX_SRGB_TRANSFER, p3ToBt709.transformMode)
    assertTrue(
      p3ToBt709.columnMajorMatrix.contentEquals(ColorPipelinePlanner.DISPLAY_P3_TO_BT709_COL_MAJOR),
    )

    // Display P3 source WITH EGL passthrough -> GPU lets hardware CSC convert via P3 tag
    val p3WithEglPass =
      ColorPipelinePlanner.resolveFrameShaderColorPlan(
        DataSpace.DATASPACE_DISPLAY_P3,
        bt709EncoderPlan.copy(useDisplayP3PassthroughEgl = true),
      )
    assertEquals(ShaderColorTransform.PASSTHROUGH, p3WithEglPass.transformMode)

    // HDR PQ source into SDR BT.709 encoder -> HDR-to-SDR tone map + BT.2020->BT.709 gamut
    val hdrToSdr =
      ColorPipelinePlanner.resolveFrameShaderColorPlan(
        DataSpace.DATASPACE_BT2020_PQ,
        bt709EncoderPlan,
      )
    assertEquals(ShaderColorTransform.HDR_TO_SDR_TONEMAP, hdrToSdr.transformMode)
    assertTrue(
      hdrToSdr.columnMajorMatrix.contentEquals(ColorPipelinePlanner.BT2020_TO_BT709_COL_MAJOR),
    )

    // Display P3 source into BT.2020 10-bit encoder -> P3->BT.2020 gamut expansion
    val bt2020EncoderPlan =
      bt709EncoderPlan.copy(
        pixelFormatMode = PixelFormatMode.RGBA_1010102,
        colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
      )
    val p3ToBt2020 =
      ColorPipelinePlanner.resolveFrameShaderColorPlan(
        DataSpace.DATASPACE_DISPLAY_P3,
        bt2020EncoderPlan,
      )
    assertEquals(ShaderColorTransform.GAMUT_MATRIX_SRGB_TRANSFER, p3ToBt2020.transformMode)
    assertTrue(
      p3ToBt2020.columnMajorMatrix.contentEquals(ColorPipelinePlanner.DISPLAY_P3_TO_BT2020_COL_MAJOR),
    )
  }

  @Test
  fun `detectDisplayColorProfile and selectBestEncoder resolve valid color pipeline`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val displayProfile = ColorPipelinePlanner.detectDisplayColorProfile(context)
    val selection =
      VideoEncoder.selectBestEncoder(
        requestedWidth = 1080,
        requestedHeight = 1920,
        bitrateOption = VideoBitrateOption.MBPS_150,
        availableCodecs = emptyList(),
        chipsetFamily = ChipsetFamily.SNAPDRAGON,
        displayColorProfile = displayProfile,
        eglColorSupport = false to false,
      )

    assertEquals(1080, selection.alignedWidth)
    assertEquals(1920, selection.alignedHeight)
    assertEquals(PixelFormatMode.RGBA_8888, selection.colorPlan.pixelFormatMode)
    assertEquals(ColorRangeMode.FULL, selection.colorPlan.colorRange)
  }
}
