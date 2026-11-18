package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.compat.PlatformCompat
import com.example.settings.AudioSourceOption
import com.example.ui.RecorderViewModel
import com.example.ui.screens.MainScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.theme.FrameCutTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bounds = windowManager.currentWindowMetrics.bounds
        val screenWidthPx = bounds.width().coerceAtLeast(720)
        val screenHeightPx = bounds.height().coerceAtLeast(1280)

        setContent {
            val state by viewModel.uiState.collectAsState()
            val navController = rememberNavController()
            val context = LocalContext.current

            val projectionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val data = result.data
                if (result.resultCode == RESULT_OK && data != null) {
                    viewModel.startRecordingService(result.resultCode, data)
                }
            }

            fun launchMediaProjectionPrompt() {
                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }

            val permissionsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                launchMediaProjectionPrompt()
            }

            fun requestPermissionsAndRecord() {
                val needed = mutableListOf<String>()
                if (state.settings.audioSource != AudioSourceOption.NONE &&
                    !PlatformCompat.hasAudioPermission(context)
                ) {
                    needed += Manifest.permission.RECORD_AUDIO
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !PlatformCompat.hasNotificationPermission(context)
                ) {
                    needed += Manifest.permission.POST_NOTIFICATIONS
                }

                if (state.settings.isRegionMode &&
                    state.settings.showBorderOverlay &&
                    !PlatformCompat.canDrawOverlays(context)
                ) {
                    val overlayIntent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching { context.startActivity(overlayIntent) }
                }

                if (needed.isNotEmpty()) {
                    permissionsLauncher.launch(needed.toTypedArray())
                } else {
                    launchMediaProjectionPrompt()
                }
            }

            FrameCutTheme(darkTheme = state.settings.useDarkTheme) {
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            state = state,
                            screenWidthPx = screenWidthPx,
                            screenHeightPx = screenHeightPx,
                            onSelectColorMode = viewModel::updateColorMode,
                            onSelectAudioSource = viewModel::updateAudioSource,
                            onSelectFrameRate = viewModel::updateFrameRate,
                            onSelectBitrate = viewModel::updateBitrate,
                            onSelectResolutionScale = viewModel::updateResolutionScale,
                            onToggleRegionMode = viewModel::updateRegionMode,
                            onOpenRegionEditor = viewModel::openRegionEditor,
                            onApplyRegion = viewModel::updateSelectedRegion,
                            onToggleBorderOverlay = viewModel::updateShowBorderOverlay,
                            onSelectCountdown = viewModel::updateCountdownSeconds,
                            onToggleDarkTheme = viewModel::updateUseDarkTheme,
                            onStartRecording = ::requestPermissionsAndRecord,
                            onPauseRecording = viewModel::pauseRecording,
                            onResumeRecording = viewModel::resumeRecording,
                            onStopRecording = viewModel::stopRecording,
                            onNavigateToRecordings = {
                                viewModel.refreshRecordings()
                                navController.navigate("recordings")
                            }
                        )
                    }
                    composable("recordings") {
                        RecordingsScreen(
                            recordings = state.recordings,
                            onBack = { navController.popBackStack() },
                            onShare = { item ->
                                context.startActivity(viewModel.buildShareIntent(item))
                            },
                            onDelete = { item ->
                                viewModel.deleteRecording(item.uri)
                            }
                        )
                    }
                }
            }
        }
    }
}
