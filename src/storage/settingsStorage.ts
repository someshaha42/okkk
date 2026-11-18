import type {
  AudioSourceOption,
  ColorModeOption,
  OrientationMode,
  RecordingMode,
  RecordingSettings,
  RegionAnimationOption,
  VideoBitrateOption,
} from '../types/recorder';
import { DEFAULT_NORMALIZED_REGION, normalizeRegion } from '../utils/regionMath';

const STORAGE_KEY = 'framecut_recording_settings';

export const DEFAULT_RECORDING_SETTINGS: RecordingSettings = {
  recordingMode: 'FULL_SCREEN',
  orientationMode: 'PORTRAIT',
  audioSource: 'INTERNAL',
  colorMode: 'ORIGINAL',
  videoBitrate: 'MBPS_150',
  regionAnimation: 'SHOW',
  selectedRegion: DEFAULT_NORMALIZED_REGION,
};

function isRecordingMode(v: unknown): v is RecordingMode {
  return v === 'FULL_SCREEN' || v === 'REGION';
}

function isOrientationMode(v: unknown): v is OrientationMode {
  return v === 'PORTRAIT' || v === 'LANDSCAPE';
}

function isAudioSource(v: unknown): v is AudioSourceOption {
  return v === 'INTERNAL' || v === 'MICROPHONE';
}

function isColorMode(v: unknown): v is ColorModeOption {
  return v === 'ORIGINAL' || v === 'FULL_RGB' || v === 'DEEP_COLOR_10BIT';
}

function isVideoBitrate(v: unknown): v is VideoBitrateOption {
  return (
    v === 'MBPS_100' ||
    v === 'MBPS_150' ||
    v === 'MBPS_200' ||
    v === 'MBPS_250' ||
    v === 'MBPS_300' ||
    v === 'MBPS_350'
  );
}

function isRegionAnimation(v: unknown): v is RegionAnimationOption {
  return v === 'SHOW' || v === 'HIDE';
}

export function loadRecordingSettings(): RecordingSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULT_RECORDING_SETTINGS;
    const parsed = JSON.parse(raw) as Partial<RecordingSettings>;

    const rawRegion = parsed.selectedRegion;
    const selectedRegion =
      rawRegion &&
      typeof rawRegion.left === 'number' &&
      typeof rawRegion.top === 'number' &&
      typeof rawRegion.right === 'number' &&
      typeof rawRegion.bottom === 'number'
        ? normalizeRegion(rawRegion)
        : DEFAULT_NORMALIZED_REGION;

    return {
      recordingMode: isRecordingMode(parsed.recordingMode)
        ? parsed.recordingMode
        : DEFAULT_RECORDING_SETTINGS.recordingMode,
      orientationMode: isOrientationMode(parsed.orientationMode)
        ? parsed.orientationMode
        : DEFAULT_RECORDING_SETTINGS.orientationMode,
      audioSource: isAudioSource(parsed.audioSource)
        ? parsed.audioSource
        : DEFAULT_RECORDING_SETTINGS.audioSource,
      colorMode: isColorMode(parsed.colorMode)
        ? parsed.colorMode
        : DEFAULT_RECORDING_SETTINGS.colorMode,
      videoBitrate: isVideoBitrate(parsed.videoBitrate)
        ? parsed.videoBitrate
        : DEFAULT_RECORDING_SETTINGS.videoBitrate,
      regionAnimation: isRegionAnimation(parsed.regionAnimation)
        ? parsed.regionAnimation
        : DEFAULT_RECORDING_SETTINGS.regionAnimation,
      selectedRegion,
    };
  } catch {
    return DEFAULT_RECORDING_SETTINGS;
  }
}

export function saveRecordingSettings(settings: RecordingSettings): void {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        ...settings,
        selectedRegion: normalizeRegion(settings.selectedRegion),
      }),
    );
  } catch {
    // Ignore storage quota errors
  }
}
