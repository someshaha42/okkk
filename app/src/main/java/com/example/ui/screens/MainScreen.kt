package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.service.RecordingPhase
import com.example.settings.AudioSourceOption
import com.example.settings.BitratePreset
import com.example.settings.ColorModeOption
import com.example.settings.FrameRateOption
import com.example.settings.ResolutionScale
import com.example.ui.RecorderUiState
import com.example.ui.components.RegionSelectorOverlay
import com.example.ui.components.VulkanPreviewSurface
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioRecordRed
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: RecorderUiState,
    screenWidthPx: Int,
    screenHeightPx: Int,
    onSelectColorMode: (ColorModeOption) -> Unit,
    onSelectAudioSource: (AudioSourceOption) -> Unit,
    onSelectFrameRate: (FrameRateOption) -> Unit,
    onSelectBitrate: (BitratePreset) -> Unit,
    onSelectResolutionScale: (ResolutionScale) -> Unit,
    onToggleRegionMode: (Boolean) -> Unit,
    onOpenRegionEditor: (Boolean) -> Unit,
    onApplyRegion: (com.example.settings.RegionRectPx) -> Unit,
    onToggleBorderOverlay: (Boolean) -> Unit,
    onSelectCountdown: (Int) -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onNavigateToRecordings: () -> Unit
) {
    val phase = state.session.phase
    val isBusy = phase != RecordingPhase.IDLE
    val isWide = LocalConfiguration.current.screenWidthDp >= 720

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (phase) {
                                            RecordingPhase.RECORDING -> StudioRecordRed
                                            RecordingPhase.PAUSED -> StudioAmber
                                            RecordingPhase.COUNTDOWN -> StudioAmber
                                            else -> StudioEmerald
                                        }
                                    )
                            )
                            Text(
                                text = "FrameCut",
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { onToggleDarkTheme(!state.settings.useDarkTheme) }) {
                            Icon(
                                imageVector = if (state.settings.useDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = "Toggle theme"
                            )
                        }
                        IconButton(onClick = onNavigateToRecordings) {
                            Icon(
                                imageVector = Icons.Rounded.VideoLibrary,
                                contentDescription = "Recordings"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            if (isWide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.05f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PreviewAndTransportCard(
                            state = state,
                            onStartRecording = onStartRecording,
                            onPauseRecording = onPauseRecording,
                            onResumeRecording = onResumeRecording,
                            onStopRecording = onStopRecording
                        )
                        CaptureScopeCard(
                            state = state,
                            isBusy = isBusy,
                            onToggleRegionMode = onToggleRegionMode,
                            onOpenRegionEditor = { onOpenRegionEditor(true) },
                            onToggleBorderOverlay = onToggleBorderOverlay
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ColorPipelineCard(
                            state = state,
                            isBusy = isBusy,
                            onSelectColorMode = onSelectColorMode
                        )
                        AudioAndEncoderCard(
                            state = state,
                            isBusy = isBusy,
                            onSelectAudioSource = onSelectAudioSource,
                            onSelectFrameRate = onSelectFrameRate,
                            onSelectBitrate = onSelectBitrate,
                            onSelectResolutionScale = onSelectResolutionScale,
                            onSelectCountdown = onSelectCountdown
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PreviewAndTransportCard(
                        state = state,
                        onStartRecording = onStartRecording,
                        onPauseRecording = onPauseRecording,
                        onResumeRecording = onResumeRecording,
                        onStopRecording = onStopRecording
                    )
                    ColorPipelineCard(
                        state = state,
                        isBusy = isBusy,
                        onSelectColorMode = onSelectColorMode
                    )
                    CaptureScopeCard(
                        state = state,
                        isBusy = isBusy,
                        onToggleRegionMode = onToggleRegionMode,
                        onOpenRegionEditor = { onOpenRegionEditor(true) },
                        onToggleBorderOverlay = onToggleBorderOverlay
                    )
                    AudioAndEncoderCard(
                        state = state,
                        isBusy = isBusy,
                        onSelectAudioSource = onSelectAudioSource,
                        onSelectFrameRate = onSelectFrameRate,
                        onSelectBitrate = onSelectBitrate,
                        onSelectResolutionScale = onSelectResolutionScale,
                        onSelectCountdown = onSelectCountdown
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        if (state.isRegionEditorOpen) {
            RegionSelectorOverlay(
                screenWidth = screenWidthPx,
                screenHeight = screenHeightPx,
                initialRegion = state.settings.selectedRegion,
                onApply = { rect ->
                    onApplyRegion(rect)
                    onToggleRegionMode(true)
                    onOpenRegionEditor(false)
                },
                onDismiss = { onOpenRegionEditor(false) }
            )
        }
    }
}

@Composable
private fun PreviewAndTransportCard(
    state: RecorderUiState,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val phase = state.session.phase
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Unified Vulkan + MediaCodec Preview",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = state.plannedColorPipeline.summaryLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatElapsed(state.session.elapsedMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (phase) {
                        RecordingPhase.RECORDING -> StudioRecordRed
                        RecordingPhase.PAUSED -> StudioAmber
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                VulkanPreviewSurface(
                    colorMode = state.settings.colorMode,
                    modifier = Modifier.fillMaxSize()
                )
                if (phase == RecordingPhase.COUNTDOWN) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${state.session.countdownRemaining}",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White
                        )
                    }
                }
            }

            state.session.errorMessage?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioRecordRed
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (phase) {
                    RecordingPhase.IDLE -> {
                        Button(
                            onClick = onStartRecording,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioRecordRed),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.FiberManualRecord, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Recording", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    RecordingPhase.COUNTDOWN, RecordingPhase.FINALIZING -> {
                        OutlinedButton(
                            onClick = onStopRecording,
                            enabled = phase == RecordingPhase.COUNTDOWN,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (phase == RecordingPhase.FINALIZING) "Saving MP4..." else "Cancel")
                        }
                    }

                    RecordingPhase.RECORDING -> {
                        OutlinedButton(
                            onClick = onPauseRecording,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pause")
                        }
                        Button(
                            onClick = onStopRecording,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioRecordRed),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop")
                        }
                    }

                    RecordingPhase.PAUSED -> {
                        Button(
                            onClick = onResumeRecording,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioEmerald),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Resume")
                        }
                        Button(
                            onClick = onStopRecording,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioRecordRed),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorPipelineCard(
    state: RecorderUiState,
    isBusy: Boolean,
    onSelectColorMode: (ColorModeOption) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Palette,
                    contentDescription = null,
                    tint = StudioRecordRed
                )
                Column {
                    Text(
                        text = "Vulkan Color Processing Mode",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Shared GPU compute pipeline for preview & MediaCodec output",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ColorModeOption.entries.forEach { mode ->
                val selected = state.settings.colorMode == mode
                val enabled = !isBusy || mode != ColorModeOption.DEEP_COLOR_10BIT ||
                    state.session.activeColorPlan?.colorMode == ColorModeOption.DEEP_COLOR_10BIT
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { onSelectColorMode(mode) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    },
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) StudioRecordRed else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = mode.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) StudioRecordRed else MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = mode.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureScopeCard(
    state: RecorderUiState,
    isBusy: Boolean,
    onToggleRegionMode: (Boolean) -> Unit,
    onOpenRegionEditor: () -> Unit,
    onToggleBorderOverlay: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Capture Area",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = !state.settings.isRegionMode,
                    enabled = !isBusy,
                    onClick = { onToggleRegionMode(false) },
                    leadingIcon = { Icon(Icons.Rounded.Fullscreen, contentDescription = null) },
                    label = { Text("Full Screen") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = state.settings.isRegionMode,
                    enabled = !isBusy,
                    onClick = {
                        onToggleRegionMode(true)
                        if (state.settings.selectedRegion == null) {
                            onOpenRegionEditor()
                        }
                    },
                    leadingIcon = { Icon(Icons.Rounded.Crop, contentDescription = null) },
                    label = { Text("Region Crop") },
                    modifier = Modifier.weight(1f)
                )
            }

            if (state.settings.isRegionMode) {
                val region = state.settings.selectedRegion
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (region != null) {
                            "${region.width} × ${region.height} px @ (${region.left}, ${region.top})"
                        } else {
                            "No region selected yet"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        enabled = !isBusy,
                        onClick = onOpenRegionEditor
                    ) {
                        Text("Edit Crop")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Floating region border while recording",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Switch(
                        checked = state.settings.showBorderOverlay,
                        enabled = !isBusy,
                        onCheckedChange = onToggleBorderOverlay
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioAndEncoderCard(
    state: RecorderUiState,
    isBusy: Boolean,
    onSelectAudioSource: (AudioSourceOption) -> Unit,
    onSelectFrameRate: (FrameRateOption) -> Unit,
    onSelectBitrate: (BitratePreset) -> Unit,
    onSelectResolutionScale: (ResolutionScale) -> Unit,
    onSelectCountdown: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "MediaCodec & Audio Settings",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Audio Source",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioSourceOption.entries.forEach { src ->
                    FilterChip(
                        selected = state.settings.audioSource == src,
                        enabled = !isBusy,
                        onClick = { onSelectAudioSource(src) },
                        leadingIcon = {
                            Icon(
                                imageVector = when (src) {
                                    AudioSourceOption.NONE -> Icons.Rounded.MicOff
                                    AudioSourceOption.INTERNAL -> Icons.Rounded.VolumeUp
                                    AudioSourceOption.MICROPHONE -> Icons.Rounded.Mic
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                text = when (src) {
                                    AudioSourceOption.NONE -> "Mute"
                                    AudioSourceOption.INTERNAL -> "Internal"
                                    AudioSourceOption.MICROPHONE -> "Mic"
                                },
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = "Frame Rate & Bitrate",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FrameRateOption.entries.forEach { fps ->
                    FilterChip(
                        selected = state.settings.frameRate == fps,
                        enabled = !isBusy,
                        onClick = { onSelectFrameRate(fps) },
                        label = { Text(fps.label) }
                    )
                }
                BitratePreset.entries.forEach { br ->
                    FilterChip(
                        selected = state.settings.bitratePreset == br,
                        enabled = !isBusy,
                        onClick = { onSelectBitrate(br) },
                        label = { Text("${br.mbps}M") }
                    )
                }
            }

            Text(
                text = "Resolution Scale",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ResolutionScale.entries.forEach { scale ->
                    FilterChip(
                        selected = state.settings.resolutionScale == scale,
                        enabled = !isBusy,
                        onClick = { onSelectResolutionScale(scale) },
                        label = { Text(scale.label, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = "Start Countdown",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 3, 5).forEach { sec ->
                    FilterChip(
                        selected = state.settings.countdownSeconds == sec,
                        enabled = !isBusy,
                        onClick = { onSelectCountdown(sec) },
                        label = { Text(if (sec == 0) "Instant" else "${sec}s") }
                    )
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
