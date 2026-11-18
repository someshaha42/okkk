package com.example.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.settings.ColorModeOption
import com.example.vulkan.VulkanPipelineCoordinator

@Composable
fun VulkanPreviewSurface(
    colorMode: ColorModeOption,
    modifier: Modifier = Modifier
) {
    var activeHolder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var surfaceWidth by remember { mutableIntStateOf(0) }
    var surfaceHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(colorMode, activeHolder, surfaceWidth, surfaceHeight) {
        val holder = activeHolder
        if (holder != null && surfaceWidth > 0 && surfaceHeight > 0) {
            VulkanPipelineCoordinator.attachPreviewSurface(
                surface = holder.surface,
                width = surfaceWidth,
                height = surfaceHeight,
                colorMode = colorMode
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            VulkanPipelineCoordinator.attachPreviewSurface(null, 0, 0, colorMode)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        activeHolder = holder
                        surfaceWidth = width.coerceAtLeast(64)
                        surfaceHeight = height.coerceAtLeast(64)
                        VulkanPipelineCoordinator.attachPreviewSurface(
                            surface = holder.surface,
                            width = surfaceWidth,
                            height = surfaceHeight,
                            colorMode = colorMode
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        w: Int,
                        h: Int
                    ) {
                        activeHolder = holder
                        surfaceWidth = w.coerceAtLeast(64)
                        surfaceHeight = h.coerceAtLeast(64)
                        VulkanPipelineCoordinator.attachPreviewSurface(
                            surface = holder.surface,
                            width = surfaceWidth,
                            height = surfaceHeight,
                            colorMode = colorMode
                        )
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        activeHolder = null
                        VulkanPipelineCoordinator.attachPreviewSurface(null, 0, 0, colorMode)
                    }
                })
            }
        }
    )
}
