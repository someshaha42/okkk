package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioCaptureManager
import com.example.capture.ScreenCaptureManager
import com.example.color.ColorPipelinePlan
import com.example.color.ColorPipelinePlanner
import com.example.compat.PlatformCompat
import com.example.encoder.VideoEncoder
import com.example.muxer.MediaMuxerManager
import com.example.region.RegionOverlayManager
import com.example.region.RegionSelectionManager
import com.example.settings.AudioSourceOption
import com.example.settings.ColorModeOption
import com.example.settings.RecordingSettings
import com.example.settings.RecordingSettingsRepository
import com.example.settings.RegionRectPx
import com.example.vulkan.VulkanPipelineCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class RecordingPhase {
    IDLE,
    COUNTDOWN,
    RECORDING,
    PAUSED,
    FINALIZING
}

data class RecordingSessionState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val countdownRemaining: Int = 0,
    val elapsedMillis: Long = 0L,
    val activeColorPlan: ColorPipelinePlan? = null,
    val lastSavedUri: Uri? = null,
    val errorMessage: String? = null
)

class RecordingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepo: RecordingSettingsRepository
    private lateinit var regionOverlayManager: RegionOverlayManager

    private var mediaProjection: MediaProjection? = null
    private var muxerManager: MediaMuxerManager? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var screenCaptureManager: ScreenCaptureManager? = null

    private var timerJob: Job? = null
    private var countdownJob: Job? = null
    private var activeStartWallMs: Long = 0L
    private var accumulatedElapsedMs: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecordingPipeline()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepo = RecordingSettingsRepository(applicationContext)
        regionOverlayManager = RegionOverlayManager(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != Int.MIN_VALUE && data != null) {
                    startWithCountdown(resultCode, data)
                } else {
                    stopSelf()
                }
            }
            ACTION_PAUSE -> togglePause(true)
            ACTION_RESUME -> togglePause(false)
            ACTION_STOP -> stopRecordingPipeline()
            ACTION_UPDATE_COLOR_MODE -> {
                val modeName = intent.getStringExtra(EXTRA_COLOR_MODE)
                val mode = ColorModeOption.fromName(modeName)
                VulkanPipelineCoordinator.setColorMode(mode)
            }
        }
        return START_NOT_STICKY
    }

    private fun startWithCountdown(resultCode: Int, projectionData: Intent) {
        if (_sessionState.value.phase != RecordingPhase.IDLE) return

        val notification = buildNotification(isPaused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            val settings = settingsRepo.settingsFlow.first()
            val countdown = settings.countdownSeconds.coerceIn(0, 5)
            for (sec in countdown downTo 1) {
                _sessionState.value = _sessionState.value.copy(
                    phase = RecordingPhase.COUNTDOWN,
                    countdownRemaining = sec,
                    errorMessage = null
                )
                delay(1000L)
            }
            beginCapture(resultCode, projectionData, settings)
        }
    }

    private fun beginCapture(
        resultCode: Int,
        projectionData: Intent,
        settings: RecordingSettings
    ) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, projectionData)
                ?: throw IllegalStateException("MediaProjection was null")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, null)

            val (screenW, screenH, densityDpi) = queryDisplayMetrics()
            val cropRegion = if (settings.isRegionMode && settings.selectedRegion != null) {
                RegionSelectionManager.enforceEvenDimensions(
                    rawLeft = settings.selectedRegion.left,
                    rawTop = settings.selectedRegion.top,
                    rawWidth = settings.selectedRegion.width,
                    rawHeight = settings.selectedRegion.height,
                    screenWidth = screenW,
                    screenHeight = screenH
                )
            } else {
                RegionRectPx(
                    left = 0,
                    top = 0,
                    width = RegionSelectionManager.makeEvenDown(screenW),
                    height = RegionSelectionManager.makeEvenDown(screenH)
                )
            }

            val (outW, outH) = RegionSelectionManager.computeOutputDimensions(
                sourceWidth = cropRegion.width,
                sourceHeight = cropRegion.height,
                scaleFactor = settings.resolutionScale.factor
            )

            val caps = PlatformCompat.queryEncoderCapabilities(applicationContext)
            val colorPlan = ColorPipelinePlanner.buildPlan(
                colorMode = settings.colorMode,
                supportsHevc10Bit = caps.supportsHevc10Bit,
                supportsHevc8Bit = caps.supportsHevc8Bit
            )

            val canRecordAudio = settings.audioSource != AudioSourceOption.NONE &&
                PlatformCompat.hasAudioPermission(applicationContext) &&
                (settings.audioSource != AudioSourceOption.INTERNAL || PlatformCompat.supportsInternalAudioCapture())

            val muxer = MediaMuxerManager(
                context = applicationContext,
                expectAudioTrack = canRecordAudio
            )
            muxer.prepare()
            muxerManager = muxer

            val encoder = VideoEncoder(
                width = outW,
                height = outH,
                fps = settings.frameRate.fps,
                bitrateBps = settings.bitratePreset.bitsPerSecond,
                colorPlan = colorPlan,
                muxerManager = muxer
            )
            val encoderInputSurface = encoder.prepare()
            videoEncoder = encoder
            encoder.start()

            if (canRecordAudio) {
                val audioMgr = AudioCaptureManager(
                    context = applicationContext,
                    sourceOption = settings.audioSource,
                    mediaProjection = projection,
                    muxerManager = muxer
                )
                audioCaptureManager = audioMgr
                audioMgr.start()
            }

            val captureMgr = ScreenCaptureManager(
                mediaProjection = projection,
                displayWidth = screenW,
                displayHeight = screenH,
                displayDensityDpi = densityDpi,
                cropRegion = cropRegion,
                encoderWidth = outW,
                encoderHeight = outH,
                encoderSurface = encoderInputSurface,
                targetFps = settings.frameRate.fps,
                initialColorMode = settings.colorMode
            )
            screenCaptureManager = captureMgr
            captureMgr.start()

            if (settings.isRegionMode && settings.showBorderOverlay) {
                regionOverlayManager.show(cropRegion, isPaused = false)
            }

            accumulatedElapsedMs = 0L
            activeStartWallMs = System.currentTimeMillis()
            _sessionState.value = RecordingSessionState(
                phase = RecordingPhase.RECORDING,
                countdownRemaining = 0,
                elapsedMillis = 0L,
                activeColorPlan = encoder.activePlan,
                lastSavedUri = _sessionState.value.lastSavedUri,
                errorMessage = null
            )
            updateNotification(isPaused = false)
            startElapsedTicker()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start recording pipeline", t)
            _sessionState.value = RecordingSessionState(
                phase = RecordingPhase.IDLE,
                errorMessage = t.message ?: "Failed to start recording"
            )
            cleanupResources(discardFile = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun togglePause(pause: Boolean) {
        val phase = _sessionState.value.phase
        if (pause && phase != RecordingPhase.RECORDING) return
        if (!pause && phase != RecordingPhase.PAUSED) return

        screenCaptureManager?.setPaused(pause)
        videoEncoder?.setPaused(pause)
        audioCaptureManager?.setPaused(pause)
        regionOverlayManager.setPaused(pause)

        if (pause) {
            accumulatedElapsedMs += (System.currentTimeMillis() - activeStartWallMs).coerceAtLeast(0L)
            timerJob?.cancel()
            _sessionState.value = _sessionState.value.copy(
                phase = RecordingPhase.PAUSED,
                elapsedMillis = accumulatedElapsedMs
            )
        } else {
            activeStartWallMs = System.currentTimeMillis()
            _sessionState.value = _sessionState.value.copy(phase = RecordingPhase.RECORDING)
            startElapsedTicker()
        }
        updateNotification(isPaused = pause)
    }

    private fun stopRecordingPipeline() {
        val currentPhase = _sessionState.value.phase
        if (currentPhase == RecordingPhase.IDLE || currentPhase == RecordingPhase.FINALIZING) {
            return
        }
        countdownJob?.cancel()
        timerJob?.cancel()

        _sessionState.value = _sessionState.value.copy(phase = RecordingPhase.FINALIZING)
        serviceScope.launch(Dispatchers.IO) {
            val savedUri = cleanupResources(discardFile = false)
            _sessionState.value = RecordingSessionState(
                phase = RecordingPhase.IDLE,
                countdownRemaining = 0,
                elapsedMillis = 0L,
                activeColorPlan = null,
                lastSavedUri = savedUri,
                errorMessage = null
            )
            serviceScope.launch(Dispatchers.Main.immediate) {
                regionOverlayManager.hide()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cleanupResources(discardFile: Boolean): Uri? {
        runCatching { screenCaptureManager?.stop() }
        screenCaptureManager = null

        runCatching { audioCaptureManager?.stop() }
        audioCaptureManager = null

        runCatching { videoEncoder?.stop() }
        videoEncoder = null

        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        val published = runCatching { muxerManager?.stopAndPublish() }.getOrNull()
        muxerManager = null
        if (discardFile && published != null) {
            runCatching { contentResolver.delete(published, null, null) }
            return null
        }
        return published
    }

    private fun startElapsedTicker() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (_sessionState.value.phase == RecordingPhase.RECORDING) {
                val nowElapsed = accumulatedElapsedMs + (System.currentTimeMillis() - activeStartWallMs)
                _sessionState.value = _sessionState.value.copy(elapsedMillis = nowElapsed)
                delay(200L)
            }
        }
    }

    private fun queryDisplayMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val densityDpi = resources.displayMetrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)
        val w = RegionSelectionManager.makeEvenDown(bounds.width().coerceAtLeast(128))
        val h = RegionSelectionManager.makeEvenDown(bounds.height().coerceAtLeast(128))
        return Triple(w, h, densityDpi)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification(isPaused: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(isPaused))
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleActionIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopActionIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPaused) {
            getString(R.string.notification_title_paused)
        } else {
            getString(R.string.notification_title_recording)
        }
        val toggleLabel = if (isPaused) {
            getString(R.string.notification_action_resume)
        } else {
            getString(R.string.notification_action_pause)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(title)
            .setContentText("Vulkan GPU + MediaCodec pipeline active")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, toggleLabel, toggleActionIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopActionIntent)
            .build()
    }

    override fun onDestroy() {
        regionOverlayManager.hide()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "framecut_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START_RECORDING"
        const val ACTION_PAUSE = "com.example.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.action.RESUME_RECORDING"
        const val ACTION_STOP = "com.example.action.STOP_RECORDING"
        const val ACTION_UPDATE_COLOR_MODE = "com.example.action.UPDATE_COLOR_MODE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_COLOR_MODE = "extra_color_mode"

        private val _sessionState = MutableStateFlow(RecordingSessionState())
        val sessionState: StateFlow<RecordingSessionState> = _sessionState.asStateFlow()
    }
}
