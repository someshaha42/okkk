package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.region.AspectRatioPreset
import com.example.region.RegionSelectionManager
import com.example.settings.RegionRectPx
import com.example.ui.theme.StudioRecordRed
import kotlin.math.hypot

private enum class DragHandle {
    NONE, BODY, TL, TM, TR, MR, BR, BM, BL, ML
}

@Composable
fun RegionSelectorOverlay(
    screenWidth: Int,
    screenHeight: Int,
    initialRegion: RegionRectPx?,
    onApply: (RegionRectPx) -> Unit,
    onDismiss: () -> Unit
) {
    val startRegion = remember(initialRegion, screenWidth, screenHeight) {
        initialRegion ?: RegionSelectionManager.defaultRegion(
            screenWidth,
            screenHeight,
            AspectRatioPreset.FREE
        )
    }

    var normRect by remember {
        mutableStateOf(
            Rect(
                left = (startRegion.left.toFloat() / screenWidth).coerceIn(0f, 0.9f),
                top = (startRegion.top.toFloat() / screenHeight).coerceIn(0f, 0.9f),
                right = (startRegion.right.toFloat() / screenWidth).coerceIn(0.1f, 1f),
                bottom = (startRegion.bottom.toFloat() / screenHeight).coerceIn(0.1f, 1f)
            )
        )
    }
    var selectedPreset by remember { mutableStateOf(AspectRatioPreset.FREE) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var activeHandle by remember { mutableStateOf(DragHandle.NONE) }

    val alignedPx = remember(normRect, screenWidth, screenHeight) {
        RegionSelectionManager.fromNormalizedBounds(
            normLeft = normRect.left,
            normTop = normRect.top,
            normRight = normRect.right,
            normBottom = normRect.bottom,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Crop Region",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "${alignedPx.width} × ${alignedPx.height} px  (x:${alignedPx.left}, y:${alignedPx.top})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onApply(alignedPx) }) { Text("Apply") }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AspectRatioPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            val def = RegionSelectionManager.defaultRegion(
                                screenWidth,
                                screenHeight,
                                preset
                            )
                            normRect = Rect(
                                left = def.left.toFloat() / screenWidth,
                                top = def.top.toFloat() / screenHeight,
                                right = def.right.toFloat() / screenWidth,
                                bottom = def.bottom.toFloat() / screenHeight
                            )
                        },
                        label = { Text(preset.label) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                val nx = offset.x / w
                                val ny = offset.y / h
                                activeHandle = pickHandle(nx, ny, normRect)
                            },
                            onDragEnd = { activeHandle = DragHandle.NONE },
                            onDragCancel = { activeHandle = DragHandle.NONE },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                val dx = dragAmount.x / w
                                val dy = dragAmount.y / h
                                normRect = updateRectForDrag(normRect, activeHandle, dx, dy)
                            }
                        )
                    }
            ) {
                RegionBorderCanvas(
                    normalizedRect = normRect,
                    borderColor = StudioRecordRed,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun pickHandle(nx: Float, ny: Float, r: Rect): DragHandle {
    val tol = 0.065f
    val candidates = listOf(
        DragHandle.TL to (r.left to r.top),
        DragHandle.TM to ((r.left + r.right) / 2f to r.top),
        DragHandle.TR to (r.right to r.top),
        DragHandle.MR to (r.right to (r.top + r.bottom) / 2f),
        DragHandle.BR to (r.right to r.bottom),
        DragHandle.BM to ((r.left + r.right) / 2f to r.bottom),
        DragHandle.BL to (r.left to r.bottom),
        DragHandle.ML to (r.left to (r.top + r.bottom) / 2f)
    )
    val best = candidates.minByOrNull { hypot(nx - it.second.first, ny - it.second.second) }
    if (best != null && hypot(nx - best.second.first, ny - best.second.second) <= tol) {
        return best.first
    }
    return if (nx in r.left..r.right && ny in r.top..r.bottom) DragHandle.BODY else DragHandle.NONE
}

private fun updateRectForDrag(r: Rect, handle: DragHandle, dx: Float, dy: Float): Rect {
    val minSize = 0.12f
    var l = r.left
    var t = r.top
    var ri = r.right
    var b = r.bottom

    when (handle) {
        DragHandle.BODY -> {
            val w = ri - l
            val h = b - t
            l = (l + dx).coerceIn(0f, 1f - w)
            t = (t + dy).coerceIn(0f, 1f - h)
            ri = l + w
            b = t + h
        }
        DragHandle.TL -> {
            l = (l + dx).coerceIn(0f, ri - minSize)
            t = (t + dy).coerceIn(0f, b - minSize)
        }
        DragHandle.TM -> {
            t = (t + dy).coerceIn(0f, b - minSize)
        }
        DragHandle.TR -> {
            ri = (ri + dx).coerceIn(l + minSize, 1f)
            t = (t + dy).coerceIn(0f, b - minSize)
        }
        DragHandle.MR -> {
            ri = (ri + dx).coerceIn(l + minSize, 1f)
        }
        DragHandle.BR -> {
            ri = (ri + dx).coerceIn(l + minSize, 1f)
            b = (b + dy).coerceIn(t + minSize, 1f)
        }
        DragHandle.BM -> {
            b = (b + dy).coerceIn(t + minSize, 1f)
        }
        DragHandle.BL -> {
            l = (l + dx).coerceIn(0f, ri - minSize)
            b = (b + dy).coerceIn(t + minSize, 1f)
        }
        DragHandle.ML -> {
            l = (l + dx).coerceIn(0f, ri - minSize)
        }
        DragHandle.NONE -> Unit
    }
    return Rect(l, t, ri, b)
}
