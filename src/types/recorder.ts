export type RecordingMode = 'FULL_SCREEN' | 'REGION';

export type OrientationMode = 'PORTRAIT' | 'LANDSCAPE';

export type AudioSourceOption = 'INTERNAL' | 'MICROPHONE';

export type ColorModeOption = 'ORIGINAL' | 'FULL_RGB' | 'DEEP_COLOR_10BIT';

export interface ColorModeMeta {
  value: ColorModeOption;
  label: string;
  subtitle: string;
  shortLabel: string;
}

export const COLOR_MODE_OPTIONS: ColorModeMeta[] = [
  {
    value: 'ORIGINAL',
    label: 'Original Color',
    subtitle: '1:1 native color passthrough',
    shortLabel: 'Original Color',
  },
  {
    value: 'FULL_RGB',
    label: 'Full RGB',
    subtitle: '0–255 full-range precision',
    shortLabel: 'Full RGB',
  },
  {
    value: 'DEEP_COLOR_10BIT',
    label: 'Deep Color (10-bit)',
    subtitle: '10-bit tonal depth & wide gamut',
    shortLabel: 'Deep Color 10-bit',
  },
];

export type VideoBitrateOption =
  | 'MBPS_100'
  | 'MBPS_150'
  | 'MBPS_200'
  | 'MBPS_250'
  | 'MBPS_300'
  | 'MBPS_350';

export interface VideoBitrateMeta {
  value: VideoBitrateOption;
  targetBitsPerSecond: number;
  label: string;
}

export const VIDEO_BITRATE_OPTIONS: VideoBitrateMeta[] = [
  { value: 'MBPS_100', targetBitsPerSecond: 100_000_000, label: '100 Mbps' },
  { value: 'MBPS_150', targetBitsPerSecond: 150_000_000, label: '150 Mbps' },
  { value: 'MBPS_200', targetBitsPerSecond: 200_000_000, label: '200 Mbps' },
  { value: 'MBPS_250', targetBitsPerSecond: 250_000_000, label: '250 Mbps' },
  { value: 'MBPS_300', targetBitsPerSecond: 300_000_000, label: '300 Mbps' },
  { value: 'MBPS_350', targetBitsPerSecond: 350_000_000, label: '350 Mbps' },
];

export type RegionAnimationOption = 'SHOW' | 'HIDE';

export interface NormalizedRegion {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface PixelRegion {
  left: number;
  top: number;
  right: number;
  bottom: number;
  screenWidth: number;
  screenHeight: number;
  width: number;
  height: number;
}

export interface RecordingSettings {
  recordingMode: RecordingMode;
  orientationMode: OrientationMode;
  audioSource: AudioSourceOption;
  colorMode: ColorModeOption;
  videoBitrate: VideoBitrateOption;
  regionAnimation: RegionAnimationOption;
  selectedRegion: NormalizedRegion;
}

export type RecordingPhase = 'IDLE' | 'RECORDING' | 'PAUSED' | 'FINALIZING';

export interface RecordingSessionState {
  phase: RecordingPhase;
  elapsedMillis: number;
  activeMode: RecordingMode;
  activeColorMode: ColorModeOption;
  activeGraphicsBackend: string;
  activeAudioLabel: string;
  activeEncoderName: string;
  activeBitrateMbps: number;
  activeResolutionLabel: string;
  activeRegion: PixelRegion | null;
  showRegionBorder: boolean;
  lastSavedId: string | null;
  lastSavedName: string | null;
  warningMessage: string | null;
  errorMessage: string | null;
}

export interface RecordedVideoItem {
  id: string;
  displayName: string;
  mimeType: string;
  durationMs: number;
  sizeBytes: number;
  dateAddedSec: number;
  width: number;
  height: number;
}
