package com.example.vulkan

import android.view.Surface
import com.example.settings.ColorModeOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared singleton controller owning the VulkanColorProcessor so that:
 * - Idle Color Calibration Preview
 * - Live Recording Preview
 * - MediaCodec Encoder Input Surface
 * all execute through the exact same native Vulkan compute shader pipeline.
 */
object VulkanPipelineCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processor = VulkanColorProcessor()

    private val _deviceInfo = MutableStateFlow(
        VulkanDeviceInfo(
            initialized = false,
            gpuName = "Initializing Vulkan...",
            apiVersion = "1.1",
            zeroCopyAhbSupported = false,
            tenBitTargetSupported = false
        )
    )
    val deviceInfo: StateFlow<VulkanDeviceInfo> = _deviceInfo.asStateFlow()

    private val currentColorMode = AtomicReference(ColorModeOption.ORIGINAL_COLOR)
    private val isRecordingActive = AtomicBoolean(false)
    private val idleFrameIndex = AtomicLong(0L)

    @Volatile
    private var previewWidth: Int = 512

    @Volatile
    private var previewHeight: Int = 288

    @Volatile
    private var hasPreviewSurface: Boolean = false

    private var idlePreviewJob: Job? = null
    private val calibrationPattern by lazy {
        VulkanColorProcessor.buildCalibrationPattern(512, 288)
    }

    fun ensureInitialized(): VulkanColorProcessor {
        if (!_deviceInfo.value.initialized) {
            val info = processor.initialize()
            _deviceInfo.value = info
        }
        return processor
    }

    fun setColorMode(mode: ColorModeOption) {
        currentColorMode.set(mode)
    }

    fun getColorMode(): ColorModeOption = currentColorMode.get()

    fun attachPreviewSurface(surface: Surface?, width: Int, height: Int, colorMode: ColorModeOption) {
        ensureInitialized()
        currentColorMode.set(colorMode)
        if (surface != null && width > 0 && height > 0) {
            previewWidth = width.coerceAtLeast(64)
            previewHeight = height.coerceAtLeast(64)
            hasPreviewSurface = true
            processor.setPreviewSurface(
                surface,
                previewWidth,
                previewHeight,
                colorMode == ColorModeOption.DEEP_COLOR_10BIT
            )
            startIdlePreviewLoopIfNeeded()
        } else {
            hasPreviewSurface = false
            idlePreviewJob?.cancel()
            idlePreviewJob = null
            processor.setPreviewSurface(null, 0, 0, false)
        }
    }

    fun setRecordingActive(active: Boolean) {
        isRecordingActive.set(active)
        if (!active && hasPreviewSurface) {
            startIdlePreviewLoopIfNeeded()
        }
    }

    private fun startIdlePreviewLoopIfNeeded() {
        if (idlePreviewJob?.isActive == true) return
        idlePreviewJob = scope.launch {
            while (isActive && hasPreviewSurface) {
                if (!isRecordingActive.get()) {
                    val mode = currentColorMode.get()
                    processor.processRgbaBuffer(
                        rgbaBytes = calibrationPattern,
                        srcWidth = 512,
                        srcHeight = 288,
                        dstWidth = previewWidth,
                        dstHeight = previewHeight,
                        colorMode = mode,
                        frameIndex = idleFrameIndex.incrementAndGet()
                    )
                    delay(33L)
                } else {
                    delay(120L)
                }
            }
        }
    }

    fun attachEncoderSurface(surface: Surface?, width: Int, height: Int, prefer10Bit: Boolean) {
        ensureInitialized()
        processor.setEncoderSurface(surface, width, height, prefer10Bit)
    }
}
