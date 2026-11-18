package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.color.ColorPipelinePlan
import com.example.color.ColorPipelinePlanner
import com.example.compat.EncoderCapabilities
import com.example.compat.PlatformCompat
import com.example.recordings.RecordingItem
import com.example.recordings.RecordingsRepository
import com.example.service.RecordingPhase
import com.example.service.RecordingService
import com.example.service.RecordingSessionState
import com.example.settings.AudioSourceOption
import com.example.settings.BitratePreset
import com.example.settings.ColorModeOption
import com.example.settings.FrameRateOption
import com.example.settings.RecordingSettings
import com.example.settings.RecordingSettingsRepository
import com.example.settings.RegionRectPx
import com.example.settings.ResolutionScale
import com.example.vulkan.VulkanDeviceInfo
import com.example.vulkan.VulkanPipelineCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecorderUiState(
    val settings: RecordingSettings = RecordingSettings(),
    val session: RecordingSessionState = RecordingSessionState(),
    val encoderCapabilities: EncoderCapabilities = EncoderCapabilities(
        supportsHevc8Bit = true,
        supportsHevc10Bit = false,
        supportsAvc = true,
        supportsVulkanHardware = true
    ),
    val plannedColorPipeline: ColorPipelinePlan = ColorPipelinePlanner.buildPlan(
        colorMode = ColorModeOption.ORIGINAL_COLOR,
        supportsHevc10Bit = false
    ),
    val vulkanInfo: VulkanDeviceInfo = VulkanDeviceInfo(
        initialized = false,
        gpuName = "Initializing Vulkan...",
        apiVersion = "1.1",
        zeroCopyAhbSupported = false,
        tenBitTargetSupported = false
    ),
    val recordings: List<RecordingItem> = emptyList(),
    val isRegionEditorOpen: Boolean = false
)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = RecordingSettingsRepository(application)
    private val recordingsRepo = RecordingsRepository(application)
    private val encoderCaps = PlatformCompat.queryEncoderCapabilities(application)

    private val _recordings = MutableStateFlow<List<RecordingItem>>(emptyList())
    private val _isRegionEditorOpen = MutableStateFlow(false)

    val uiState: StateFlow<RecorderUiState> = combine(
        settingsRepo.settingsFlow,
        RecordingService.sessionState,
        VulkanPipelineCoordinator.deviceInfo,
        _recordings,
        _isRegionEditorOpen
    ) { settings, session, vulkanInfo, recordings, regionEditorOpen ->
        VulkanPipelineCoordinator.setColorMode(settings.colorMode)
        val plan = ColorPipelinePlanner.buildPlan(
            colorMode = settings.colorMode,
            supportsHevc10Bit = encoderCaps.supportsHevc10Bit,
            supportsHevc8Bit = encoderCaps.supportsHevc8Bit
        )
        RecorderUiState(
            settings = settings,
            session = session,
            encoderCapabilities = encoderCaps,
            plannedColorPipeline = plan,
            vulkanInfo = vulkanInfo,
            recordings = recordings,
            isRegionEditorOpen = regionEditorOpen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RecorderUiState(encoderCapabilities = encoderCaps)
    )

    init {
        VulkanPipelineCoordinator.ensureInitialized()
        refreshRecordings()
        viewModelScope.launch {
            var previousPhase = RecordingPhase.IDLE
            RecordingService.sessionState.collect { session ->
                if (previousPhase != RecordingPhase.IDLE && session.phase == RecordingPhase.IDLE) {
                    refreshRecordings()
                }
                previousPhase = session.phase
            }
        }
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            _recordings.value = recordingsRepo.loadRecordings()
        }
    }

    fun updateColorMode(mode: ColorModeOption) {
        VulkanPipelineCoordinator.setColorMode(mode)
        viewModelScope.launch {
            settingsRepo.updateColorMode(mode)
        }
        val app = getApplication<Application>()
        if (uiState.value.session.phase == RecordingPhase.RECORDING ||
            uiState.value.session.phase == RecordingPhase.PAUSED
        ) {
            val intent = Intent(app, RecordingService::class.java).apply {
                action = RecordingService.ACTION_UPDATE_COLOR_MODE
                putExtra(RecordingService.EXTRA_COLOR_MODE, mode.name)
            }
            app.startService(intent)
        }
    }

    fun updateAudioSource(source: AudioSourceOption) {
        viewModelScope.launch { settingsRepo.updateAudioSource(source) }
    }

    fun updateFrameRate(frameRate: FrameRateOption) {
        viewModelScope.launch { settingsRepo.updateFrameRate(frameRate) }
    }

    fun updateBitrate(bitrate: BitratePreset) {
        viewModelScope.launch { settingsRepo.updateBitrate(bitrate) }
    }

    fun updateResolutionScale(scale: ResolutionScale) {
        viewModelScope.launch { settingsRepo.updateResolutionScale(scale) }
    }

    fun updateRegionMode(isRegion: Boolean) {
        viewModelScope.launch { settingsRepo.updateRegionMode(isRegion) }
    }

    fun updateSelectedRegion(region: RegionRectPx?) {
        viewModelScope.launch { settingsRepo.updateSelectedRegion(region) }
    }

    fun updateShowBorderOverlay(show: Boolean) {
        viewModelScope.launch { settingsRepo.updateShowBorderOverlay(show) }
    }

    fun updateCountdownSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepo.updateCountdownSeconds(seconds) }
    }

    fun updateUseDarkTheme(useDark: Boolean) {
        viewModelScope.launch { settingsRepo.updateUseDarkTheme(useDark) }
    }

    fun openRegionEditor(open: Boolean) {
        _isRegionEditorOpen.value = open
    }

    fun startRecordingService(resultCode: Int, projectionData: Intent) {
        val app = getApplication<Application>()
        val intent = Intent(app, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordingService.EXTRA_RESULT_DATA, projectionData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun pauseRecording() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, RecordingService::class.java).apply {
                action = RecordingService.ACTION_PAUSE
            }
        )
    }

    fun resumeRecording() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, RecordingService::class.java).apply {
                action = RecordingService.ACTION_RESUME
            }
        )
    }

    fun stopRecording() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
        )
    }

    fun deleteRecording(uri: Uri) {
        viewModelScope.launch {
            recordingsRepo.deleteRecording(uri)
            refreshRecordings()
        }
    }

    fun buildShareIntent(item: RecordingItem): Intent =
        recordingsRepo.buildShareIntent(item.uri, item.displayName)
}
