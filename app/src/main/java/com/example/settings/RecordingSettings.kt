package com.example.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "framecut_recording_settings"
)

enum class ColorModeOption(
    val shaderModeId: Int,
    val label: String,
    val shortLabel: String,
    val subtitle: String,
    val badge: String
) {
    ORIGINAL_COLOR(
        shaderModeId = 0,
        label = "Original Color",
        shortLabel = "Original",
        subtitle = "1:1 native display color passthrough via Vulkan compute",
        badge = "Default"
    ),
    FULL_RGB(
        shaderModeId = 1,
        label = "Full RGB",
        shortLabel = "Full RGB",
        subtitle = "Full 0–255 dynamic range without 16–235 studio compression",
        badge = "0–255"
    ),
    DEEP_COLOR_10BIT(
        shaderModeId = 2,
        label = "Deep Color (10-bit)",
        shortLabel = "Deep 10-bit",
        subtitle = "1024-step wide gamut headroom with 10-bit IGN micro-dither",
        badge = "10-Bit"
    );

    companion object {
        fun fromName(value: String?): ColorModeOption =
            entries.firstOrNull { it.name == value } ?: ORIGINAL_COLOR
    }
}

enum class AudioSourceOption(val label: String, val subtitle: String) {
    NONE("No Audio", "Mute audio track"),
    INTERNAL("Internal Audio", "App & system playback audio"),
    MICROPHONE("Microphone", "Voice & external mic input");

    companion object {
        fun fromName(value: String?): AudioSourceOption =
            entries.firstOrNull { it.name == value } ?: INTERNAL
    }
}

enum class FrameRateOption(val fps: Int, val label: String) {
    FPS_30(30, "30 FPS"),
    FPS_60(60, "60 FPS");

    companion object {
        fun fromFps(fps: Int?): FrameRateOption =
            entries.firstOrNull { it.fps == fps } ?: FPS_60
    }
}

enum class BitratePreset(val mbps: Int, val label: String) {
    MBPS_8(8, "8 Mbps"),
    MBPS_12(12, "12 Mbps"),
    MBPS_20(20, "20 Mbps"),
    MBPS_32(32, "32 Mbps");

    val bitsPerSecond: Int get() = mbps * 1_000_000

    companion object {
        fun fromMbps(mbps: Int?): BitratePreset =
            entries.firstOrNull { it.mbps == mbps } ?: MBPS_12
    }
}

enum class ResolutionScale(val factor: Float, val label: String) {
    NATIVE(1.0f, "100% Native"),
    THREE_QUARTER(0.75f, "75% Balanced"),
    HALF(0.5f, "50% Compact");

    companion object {
        fun fromName(value: String?): ResolutionScale =
            entries.firstOrNull { it.name == value } ?: NATIVE
    }
}

data class RegionRectPx(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

data class RecordingSettings(
    val colorMode: ColorModeOption = ColorModeOption.ORIGINAL_COLOR,
    val audioSource: AudioSourceOption = AudioSourceOption.INTERNAL,
    val frameRate: FrameRateOption = FrameRateOption.FPS_60,
    val bitratePreset: BitratePreset = BitratePreset.MBPS_12,
    val resolutionScale: ResolutionScale = ResolutionScale.NATIVE,
    val isRegionMode: Boolean = false,
    val selectedRegion: RegionRectPx? = null,
    val showBorderOverlay: Boolean = true,
    val countdownSeconds: Int = 3,
    val useDarkTheme: Boolean = true
)

class RecordingSettingsRepository(private val context: Context) {

    private object Keys {
        val COLOR_MODE = stringPreferencesKey("color_mode")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val FRAME_RATE = intPreferencesKey("frame_rate")
        val BITRATE_MBPS = intPreferencesKey("bitrate_mbps")
        val RESOLUTION_SCALE = stringPreferencesKey("resolution_scale")
        val IS_REGION_MODE = booleanPreferencesKey("is_region_mode")
        val REGION_LEFT = intPreferencesKey("region_left")
        val REGION_TOP = intPreferencesKey("region_top")
        val REGION_WIDTH = intPreferencesKey("region_width")
        val REGION_HEIGHT = intPreferencesKey("region_height")
        val SHOW_BORDER_OVERLAY = booleanPreferencesKey("show_border_overlay")
        val COUNTDOWN_SECONDS = intPreferencesKey("countdown_seconds")
        val USE_DARK_THEME = booleanPreferencesKey("use_dark_theme")
    }

    val settingsFlow: Flow<RecordingSettings> = context.settingsDataStore.data.map { prefs ->
        val regionWidth = prefs[Keys.REGION_WIDTH] ?: 0
        val regionHeight = prefs[Keys.REGION_HEIGHT] ?: 0
        val savedRegion = if (regionWidth >= 64 && regionHeight >= 64) {
            RegionRectPx(
                left = prefs[Keys.REGION_LEFT] ?: 0,
                top = prefs[Keys.REGION_TOP] ?: 0,
                width = regionWidth,
                height = regionHeight
            )
        } else {
            null
        }

        RecordingSettings(
            colorMode = ColorModeOption.fromName(prefs[Keys.COLOR_MODE]),
            audioSource = AudioSourceOption.fromName(prefs[Keys.AUDIO_SOURCE]),
            frameRate = FrameRateOption.fromFps(prefs[Keys.FRAME_RATE]),
            bitratePreset = BitratePreset.fromMbps(prefs[Keys.BITRATE_MBPS]),
            resolutionScale = ResolutionScale.fromName(prefs[Keys.RESOLUTION_SCALE]),
            isRegionMode = prefs[Keys.IS_REGION_MODE] ?: false,
            selectedRegion = savedRegion,
            showBorderOverlay = prefs[Keys.SHOW_BORDER_OVERLAY] ?: true,
            countdownSeconds = prefs[Keys.COUNTDOWN_SECONDS] ?: 3,
            useDarkTheme = prefs[Keys.USE_DARK_THEME] ?: true
        )
    }

    suspend fun updateColorMode(mode: ColorModeOption) {
        context.settingsDataStore.edit { it[Keys.COLOR_MODE] = mode.name }
    }

    suspend fun updateAudioSource(source: AudioSourceOption) {
        context.settingsDataStore.edit { it[Keys.AUDIO_SOURCE] = source.name }
    }

    suspend fun updateFrameRate(frameRate: FrameRateOption) {
        context.settingsDataStore.edit { it[Keys.FRAME_RATE] = frameRate.fps }
    }

    suspend fun updateBitrate(bitrate: BitratePreset) {
        context.settingsDataStore.edit { it[Keys.BITRATE_MBPS] = bitrate.mbps }
    }

    suspend fun updateResolutionScale(scale: ResolutionScale) {
        context.settingsDataStore.edit { it[Keys.RESOLUTION_SCALE] = scale.name }
    }

    suspend fun updateRegionMode(isRegionMode: Boolean) {
        context.settingsDataStore.edit { it[Keys.IS_REGION_MODE] = isRegionMode }
    }

    suspend fun updateSelectedRegion(region: RegionRectPx?) {
        context.settingsDataStore.edit { prefs ->
            if (region == null) {
                prefs.remove(Keys.REGION_LEFT)
                prefs.remove(Keys.REGION_TOP)
                prefs.remove(Keys.REGION_WIDTH)
                prefs.remove(Keys.REGION_HEIGHT)
            } else {
                prefs[Keys.REGION_LEFT] = region.left
                prefs[Keys.REGION_TOP] = region.top
                prefs[Keys.REGION_WIDTH] = region.width
                prefs[Keys.REGION_HEIGHT] = region.height
            }
        }
    }

    suspend fun updateShowBorderOverlay(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_BORDER_OVERLAY] = show }
    }

    suspend fun updateCountdownSeconds(seconds: Int) {
        context.settingsDataStore.edit { it[Keys.COUNTDOWN_SECONDS] = seconds.coerceIn(0, 5) }
    }

    suspend fun updateUseDarkTheme(useDark: Boolean) {
        context.settingsDataStore.edit { it[Keys.USE_DARK_THEME] = useDark }
    }
}
