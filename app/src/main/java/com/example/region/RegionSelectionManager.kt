package com.example.region

import com.example.settings.RegionRectPx
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AspectRatioPreset(val label: String, val ratio: Float?) {
    FREE("Free", null),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_1_1("1:1", 1f)
}

object RegionSelectionManager {
    private const val MIN_REGION_SIZE_PX = 128

    fun enforceEvenDimensions(
        rawLeft: Int,
        rawTop: Int,
        rawWidth: Int,
        rawHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): RegionRectPx {
        val maxW = makeEvenDown(max(MIN_REGION_SIZE_PX, screenWidth))
        val maxH = makeEvenDown(max(MIN_REGION_SIZE_PX, screenHeight))

        val clampedW = makeEvenDown(rawWidth.coerceIn(MIN_REGION_SIZE_PX, maxW))
        val clampedH = makeEvenDown(rawHeight.coerceIn(MIN_REGION_SIZE_PX, maxH))

        val maxLeft = max(0, screenWidth - clampedW)
        val maxTop = max(0, screenHeight - clampedH)

        val alignedLeft = makeEvenDown(rawLeft.coerceIn(0, maxLeft))
        val alignedTop = makeEvenDown(rawTop.coerceIn(0, maxTop))

        return RegionRectPx(
            left = alignedLeft,
            top = alignedTop,
            width = clampedW,
            height = clampedH
        )
    }

    fun fromNormalizedBounds(
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        screenWidth: Int,
        screenHeight: Int
    ): RegionRectPx {
        val left = (min(normLeft, normRight).coerceIn(0f, 1f) * screenWidth).roundToInt()
        val top = (min(normTop, normBottom).coerceIn(0f, 1f) * screenHeight).roundToInt()
        val right = (max(normLeft, normRight).coerceIn(0f, 1f) * screenWidth).roundToInt()
        val bottom = (max(normTop, normBottom).coerceIn(0f, 1f) * screenHeight).roundToInt()
        return enforceEvenDimensions(
            rawLeft = left,
            rawTop = top,
            rawWidth = right - left,
            rawHeight = bottom - top,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }

    fun defaultRegion(screenWidth: Int, screenHeight: Int, preset: AspectRatioPreset): RegionRectPx {
        val ratio = preset.ratio
        return if (ratio == null) {
            enforceEvenDimensions(
                rawLeft = (screenWidth * 0.1f).roundToInt(),
                rawTop = (screenHeight * 0.18f).roundToInt(),
                rawWidth = (screenWidth * 0.8f).roundToInt(),
                rawHeight = (screenHeight * 0.56f).roundToInt(),
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        } else {
            val targetMaxW = screenWidth * 0.82f
            val targetMaxH = screenHeight * 0.72f
            val width: Float
            val height: Float
            if (targetMaxW / targetMaxH > ratio) {
                height = targetMaxH
                width = height * ratio
            } else {
                width = targetMaxW
                height = width / ratio
            }
            val wInt = width.roundToInt()
            val hInt = height.roundToInt()
            enforceEvenDimensions(
                rawLeft = (screenWidth - wInt) / 2,
                rawTop = (screenHeight - hInt) / 2,
                rawWidth = wInt,
                rawHeight = hInt,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
    }

    fun computeOutputDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        scaleFactor: Float
    ): Pair<Int, Int> {
        val scaledW = makeEvenDown((sourceWidth * scaleFactor).roundToInt().coerceAtLeast(MIN_REGION_SIZE_PX))
        val scaledH = makeEvenDown((sourceHeight * scaleFactor).roundToInt().coerceAtLeast(MIN_REGION_SIZE_PX))
        return scaledW to scaledH
    }

    fun makeEvenDown(value: Int): Int {
        val even = value and 1.inv()
        return max(2, even)
    }
}
