package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun RegionBorderCanvas(
    normalizedRect: Rect,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val left = normalizedRect.left * size.width
        val top = normalizedRect.top * size.height
        val width = (normalizedRect.width * size.width).coerceAtLeast(16f)
        val height = (normalizedRect.height * size.height).coerceAtLeast(16f)

        // Dim outside region
        val scrim = Color.Black.copy(alpha = 0.52f)
        drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(scrim, topLeft = Offset(0f, top + height), size = Size(size.width, size.height - (top + height)))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, height))
        drawRect(scrim, topLeft = Offset(left + width, top), size = Size(size.width - (left + width), height))

        // Dashed capture frame
        drawRect(
            color = borderColor,
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(
                width = 2.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), 0f)
            )
        )

        // Rule-of-thirds subtle guides
        val guideColor = Color.White.copy(alpha = 0.18f)
        val strokeThin = 1.dp.toPx()
        drawLine(
            color = guideColor,
            start = Offset(left + width / 3f, top),
            end = Offset(left + width / 3f, top + height),
            strokeWidth = strokeThin
        )
        drawLine(
            color = guideColor,
            start = Offset(left + width * 2f / 3f, top),
            end = Offset(left + width * 2f / 3f, top + height),
            strokeWidth = strokeThin
        )
        drawLine(
            color = guideColor,
            start = Offset(left, top + height / 3f),
            end = Offset(left + width, top + height / 3f),
            strokeWidth = strokeThin
        )
        drawLine(
            color = guideColor,
            start = Offset(left, top + height * 2f / 3f),
            end = Offset(left + width, top + height * 2f / 3f),
            strokeWidth = strokeThin
        )

        // Corner & midpoint resize handles
        val handleRadius = 7.dp.toPx()
        val handles = listOf(
            Offset(left, top),
            Offset(left + width / 2f, top),
            Offset(left + width, top),
            Offset(left + width, top + height / 2f),
            Offset(left + width, top + height),
            Offset(left + width / 2f, top + height),
            Offset(left, top + height),
            Offset(left, top + height / 2f)
        )
        for (pt in handles) {
            drawCircle(color = borderColor, radius = handleRadius, center = pt)
            drawCircle(color = Color.White, radius = handleRadius * 0.55f, center = pt)
        }
    }
}
