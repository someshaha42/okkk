import type { ReactNode } from 'react';
import {
  AudioLines,
  Circle,
  Crop,
  Eye,
  EyeOff,
  Film,
  Gauge,
  Maximize,
  Mic,
  Monitor,
  Palette,
  Pause,
  Play,
  SlidersHorizontal,
  Smartphone,
  Square,
} from 'lucide-react';
import type {
  AudioSourceOption,
  ColorModeOption,
  OrientationMode,
  PixelRegion,
  RecordingMode,
  RecordingPhase,
  RecordingSessionState,
  RecordingSettings,
  RegionAnimationOption,
  VideoBitrateOption,
} from '../types/recorder';
import {
  COLOR_MODE_OPTIONS,
  VIDEO_BITRATE_OPTIONS,
} from '../types/recorder';
import { clamp, formatElapsedTimer } from '../utils/regionMath';
import { ColorPipelinePreview } from './ColorPipelinePreview';
import { RegionBorderCanvas } from './RegionBorderCanvas';

interface MainScreenProps {
  settings: RecordingSettings;
  session: RecordingSessionState;
  canvasWidthPx: number;
  canvasHeightPx: number;
  selectedPixelRegion: PixelRegion;
  recordingsCount: number;
  liveSourceVideo: HTMLVideoElement | null;
  onStartRecordClick: () => void;
  onPauseResumeClick: () => void;
  onStopClick: () => void;
  onOpenRecordings: () => void;
  onSelectMode: (mode: RecordingMode) => void;
  onSelectOrientationMode: (mode: OrientationMode) => void;
  onSelectAudioSource: (source: AudioSourceOption) => void;
  onSelectColorMode: (colorMode: ColorModeOption) => void;
  onSelectBitrate: (bitrate: VideoBitrateOption) => void;
  onSelectRegionAnimation: (option: RegionAnimationOption) => void;
  onEditRegion: () => void;
  onDismissMessages: () => void;
}

export function MainScreen({
  settings,
  session,
  canvasWidthPx,
  canvasHeightPx,
  selectedPixelRegion,
  recordingsCount,
  liveSourceVideo,
  onStartRecordClick,
  onPauseResumeClick,
  onStopClick,
  onOpenRecordings,
  onSelectMode,
  onSelectOrientationMode,
  onSelectAudioSource,
  onSelectColorMode,
  onSelectBitrate,
  onSelectRegionAnimation,
  onEditRegion,
  onDismissMessages,
}: MainScreenProps) {
  const isIdle = session.phase === 'IDLE';
  const bannerText = session.errorMessage || session.warningMessage;
  const isErrorBanner = Boolean(session.errorMessage);

  return (
    <div className="min-h-screen bg-[var(--studio-bg)] text-[var(--studio-on-surface)]">
      {/* Top Bar */}
      <header className="sticky top-0 z-30 border-b border-[var(--studio-outline-variant)] bg-[var(--studio-bg)]/95 backdrop-blur-sm">
        <div className="mx-auto flex h-16 max-w-[1280px] items-center justify-between gap-8 px-4 sm:px-6">
          <div className="flex items-center gap-2.5 whitespace-nowrap shrink-0">
            <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-[var(--studio-record-container)]">
              <Circle className="h-4 w-4 fill-[var(--studio-record-accent)] text-[var(--studio-record-accent)]" />
            </span>
            <span className="font-display text-2xl font-bold tracking-tight text-[var(--studio-on-surface)]">
              FrameCut
            </span>
          </div>

          <button
            type="button"
            onClick={onOpenRecordings}
            aria-label="Open recorded videos"
            className="relative flex h-10 items-center gap-2 whitespace-nowrap shrink-0 rounded-full border border-[var(--studio-outline-variant)] bg-[var(--studio-surface)] px-3.5 text-sm font-medium text-[var(--studio-on-surface)] transition-colors hover:border-[var(--studio-outline)] hover:bg-[var(--studio-surface-variant)] focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
          >
            <Film className="h-4 w-4 text-[var(--studio-on-surface-variant)]" />
            <span className="hidden sm:inline">Recordings</span>
            {recordingsCount > 0 && (
              <span className="font-mono-tabular inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-[var(--studio-record-accent)] px-1.5 text-[11px] font-semibold text-white">
                {recordingsCount}
              </span>
            )}
          </button>
        </div>
      </header>

      {/* Main 2-Pane Studio Layout */}
      <main className="mx-auto max-w-[1280px] px-4 py-5 sm:px-6 lg:py-6">
        <div className="flex flex-col gap-5 lg:flex-row lg:items-start lg:gap-5">
          {/* Left Anchored Console Pane */}
          <div className="flex w-full flex-col gap-3.5 lg:sticky lg:top-22 lg:w-[38%] lg:min-w-[310px] lg:max-w-[400px] lg:shrink-0">
            {bannerText && (
              <div
                role="alert"
                className={`flex items-center justify-between gap-3 rounded-[14px] px-3.5 py-2.5 text-sm ${
                  isErrorBanner
                    ? 'bg-[var(--studio-error-container)] text-[var(--studio-error)]'
                    : 'bg-[var(--studio-paused-container)] text-[var(--studio-paused-accent)]'
                }`}
              >
                <p className="flex-1 leading-snug">{bannerText}</p>
                <button
                  type="button"
                  onClick={onDismissMessages}
                  className="whitespace-nowrap shrink-0 rounded-lg px-2.5 py-1 text-xs font-semibold hover:bg-black/10"
                >
                  Close
                </button>
              </div>
            )}

            <RecordingConsoleCard
              settings={settings}
              session={session}
              canvasWidthPx={canvasWidthPx}
              canvasHeightPx={canvasHeightPx}
              selectedPixelRegion={selectedPixelRegion}
              liveSourceVideo={liveSourceVideo}
              onStartRecordClick={onStartRecordClick}
              onPauseResumeClick={onPauseResumeClick}
              onStopClick={onStopClick}
            />

            {isIdle && session.lastSavedName && (
              <button
                type="button"
                onClick={onOpenRecordings}
                className="flex w-full items-center justify-between gap-3 rounded-[14px] bg-[var(--studio-ready-container)] px-3.5 py-3 text-left transition-opacity hover:opacity-90 focus-visible:outline-2 focus-visible:outline-[var(--studio-ready-accent)]"
              >
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-semibold text-[var(--studio-ready-accent)]">
                    Saved to Recorded Videos
                  </p>
                  <p className="truncate text-sm text-[var(--studio-on-surface)]">
                    {session.lastSavedName}
                  </p>
                </div>
                <span className="whitespace-nowrap shrink-0 text-sm font-semibold text-[var(--studio-ready-accent)]">
                  Play
                </span>
              </button>
            )}

            <button
              type="button"
              onClick={onOpenRecordings}
              className="flex h-[52px] w-full items-center justify-center gap-2 whitespace-nowrap rounded-[14px] border border-[var(--studio-outline)] bg-transparent px-4 text-sm font-semibold text-[var(--studio-on-surface)] transition-colors hover:bg-[var(--studio-surface-variant)]/55 focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
            >
              <Film className="h-4.5 w-4.5" />
              <span>
                {recordingsCount === 0
                  ? 'Recorded Videos'
                  : `Recorded Videos (${recordingsCount})`}
              </span>
            </button>
          </div>

          {/* Right Settings Pane */}
          <div className="flex-1">
            <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-2 xl:items-start">
              {/* Column 1 */}
              <div className="flex flex-col gap-3.5">
                <div className={isIdle ? '' : 'pointer-events-none opacity-60'}>
                  <SettingsSectionCard
                    title="Recording Mode"
                    icon={<Maximize className="h-4.5 w-4.5" />}
                  >
                    <div className="grid grid-cols-2 gap-2.5">
                      <OptionTile
                        title="Full Screen"
                        subtitle="Entire display capture"
                        icon={<Maximize className="h-4 w-4" />}
                        selected={settings.recordingMode === 'FULL_SCREEN'}
                        enabled={isIdle}
                        onClick={() => onSelectMode('FULL_SCREEN')}
                      />
                      <OptionTile
                        title="Region / Area"
                        subtitle="GPU-cropped screen rectangle"
                        icon={<Crop className="h-4 w-4" />}
                        selected={settings.recordingMode === 'REGION'}
                        enabled={isIdle}
                        onClick={() => onSelectMode('REGION')}
                      />
                    </div>
                  </SettingsSectionCard>
                </div>

                {settings.recordingMode === 'REGION' && (
                  <div className={isIdle ? '' : 'pointer-events-none opacity-60'}>
                    <RegionPreviewCard
                      settings={settings}
                      phase={session.phase}
                      canvasWidthPx={canvasWidthPx}
                      canvasHeightPx={canvasHeightPx}
                      selectedPixelRegion={selectedPixelRegion}
                      enabled={isIdle}
                      onEditRegion={onEditRegion}
                    />
                  </div>
                )}

                <div className={isIdle ? '' : 'pointer-events-none opacity-60'}>
                  <SettingsSectionCard
                    title="Orientation"
                    icon={<Smartphone className="h-4.5 w-4.5" />}
                  >
                    <div className="grid grid-cols-2 gap-2.5">
                      <OptionTile
                        title="Portrait"
                        subtitle="Vertical for Full Screen & Region"
                        icon={<Smartphone className="h-4 w-4" />}
                        selected={settings.orientationMode === 'PORTRAIT'}
                        enabled={isIdle}
                        onClick={() => onSelectOrientationMode('PORTRAIT')}
                      />
                      <OptionTile
                        title="Landscape"
                        subtitle="Horizontal for Full Screen & Region"
                        icon={<Monitor className="h-4 w-4" />}
                        selected={settings.orientationMode === 'LANDSCAPE'}
                        enabled={isIdle}
                        onClick={() => onSelectOrientationMode('LANDSCAPE')}
                      />
                    </div>
                  </SettingsSectionCard>
                </div>

                {/* Region Border Animation stays live even while recording */}
                <SettingsSectionCard
                  title="Region Border Animation"
                  icon={<Eye className="h-4.5 w-4.5" />}
                >
                  <div className="grid grid-cols-2 gap-2.5">
                    <OptionTile
                      title="Show"
                      subtitle="Animated red dashed frame"
                      icon={<Eye className="h-4 w-4" />}
                      selected={settings.regionAnimation === 'SHOW'}
                      enabled={true}
                      onClick={() => onSelectRegionAnimation('SHOW')}
                    />
                    <OptionTile
                      title="Hide"
                      subtitle="Border animation disabled"
                      icon={<EyeOff className="h-4 w-4" />}
                      selected={settings.regionAnimation === 'HIDE'}
                      enabled={true}
                      onClick={() => onSelectRegionAnimation('HIDE')}
                    />
                  </div>
                </SettingsSectionCard>
              </div>

              {/* Column 2 */}
              <div className={`flex flex-col gap-3.5 ${isIdle ? '' : 'pointer-events-none opacity-60'}`}>
                <SettingsSectionCard
                  title="Color Mode"
                  icon={<Palette className="h-4.5 w-4.5" />}
                >
                  <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3 xl:grid-cols-1">
                    {COLOR_MODE_OPTIONS.map((option) => (
                      <OptionTile
                        key={option.value}
                        title={option.label}
                        subtitle={option.subtitle}
                        icon={<Palette className="h-4 w-4" />}
                        selected={settings.colorMode === option.value}
                        enabled={isIdle}
                        onClick={() => onSelectColorMode(option.value)}
                      />
                    ))}
                  </div>
                </SettingsSectionCard>

                <SettingsSectionCard
                  title="Audio Source"
                  icon={<AudioLines className="h-4.5 w-4.5" />}
                >
                  <div className="grid grid-cols-2 gap-2.5">
                    <OptionTile
                      title="Internal Audio"
                      subtitle="System & tab playback"
                      icon={<AudioLines className="h-4 w-4" />}
                      selected={settings.audioSource === 'INTERNAL'}
                      enabled={isIdle}
                      onClick={() => onSelectAudioSource('INTERNAL')}
                    />
                    <OptionTile
                      title="Microphone"
                      subtitle="External mic prioritized automatically"
                      icon={<Mic className="h-4 w-4" />}
                      selected={settings.audioSource === 'MICROPHONE'}
                      enabled={isIdle}
                      onClick={() => onSelectAudioSource('MICROPHONE')}
                    />
                  </div>
                </SettingsSectionCard>

                <SettingsSectionCard
                  title="Video Bitrate"
                  icon={<Gauge className="h-4.5 w-4.5" />}
                >
                  <div className="flex flex-wrap gap-2">
                    {VIDEO_BITRATE_OPTIONS.map((option) => {
                      const isSelected = option.value === settings.videoBitrate;
                      return (
                        <button
                          key={option.value}
                          type="button"
                          disabled={!isIdle}
                          onClick={() => onSelectBitrate(option.value)}
                          className={`min-h-10 whitespace-nowrap shrink-0 rounded-lg border px-3.5 py-2 font-mono-tabular text-xs font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)] ${
                            isSelected
                              ? 'border-[var(--studio-record-accent)] bg-[var(--studio-record-container)] text-[var(--studio-record-accent)]'
                              : 'border-[var(--studio-outline)] bg-transparent text-[var(--studio-on-surface-variant)] hover:border-[var(--studio-outline)] hover:bg-[var(--studio-surface-variant)]/55 hover:text-[var(--studio-on-surface)]'
                          }`}
                        >
                          {option.label}
                        </button>
                      );
                    })}
                  </div>
                </SettingsSectionCard>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

interface RecordingConsoleCardProps {
  settings: RecordingSettings;
  session: RecordingSessionState;
  canvasWidthPx: number;
  canvasHeightPx: number;
  selectedPixelRegion: PixelRegion;
  liveSourceVideo: HTMLVideoElement | null;
  onStartRecordClick: () => void;
  onPauseResumeClick: () => void;
  onStopClick: () => void;
}

function RecordingConsoleCard({
  settings,
  session,
  canvasWidthPx,
  canvasHeightPx,
  selectedPixelRegion,
  liveSourceVideo,
  onStartRecordClick,
  onPauseResumeClick,
  onStopClick,
}: RecordingConsoleCardProps) {
  const { phase } = session;

  const statusMeta = (() => {
    switch (phase) {
      case 'RECORDING':
        return {
          label: 'Recording',
          color: 'var(--studio-record-accent)',
          container: 'var(--studio-record-container)',
          gradientTop: 'rgba(220, 38, 38, 0.14)',
        };
      case 'PAUSED':
        return {
          label: 'Paused',
          color: 'var(--studio-paused-accent)',
          container: 'var(--studio-paused-container)',
          gradientTop: 'rgba(217, 119, 6, 0.14)',
        };
      case 'FINALIZING':
        return {
          label: 'Finalizing Video…',
          color: 'var(--studio-secondary)',
          container: 'var(--studio-secondary-container)',
          gradientTop: 'rgba(96, 165, 250, 0.14)',
        };
      default:
        return {
          label: 'Ready to record',
          color: 'var(--studio-ready-accent)',
          container: 'var(--studio-ready-container)',
          gradientTop: 'rgba(16, 185, 129, 0.14)',
        };
    }
  })();

  const orientationSummary =
    settings.orientationMode === 'PORTRAIT' ? 'Portrait' : 'Landscape';

  const activeColorMeta =
    COLOR_MODE_OPTIONS.find((c) => c.value === (phase === 'IDLE' ? settings.colorMode : session.activeColorMode)) ??
    COLOR_MODE_OPTIONS[0];

  const subtitleText =
    phase === 'IDLE'
      ? `${
          settings.recordingMode === 'FULL_SCREEN'
            ? `Full Screen (${canvasWidthPx}×${canvasHeightPx})`
            : `Region / Area (${selectedPixelRegion.width}×${selectedPixelRegion.height})`
        } • ${orientationSummary} • ${
          settings.audioSource === 'INTERNAL' ? 'Internal Audio' : 'Microphone'
        } • ${activeColorMeta.shortLabel}`
      : [
          session.activeResolutionLabel,
          orientationSummary,
          session.activeAudioLabel,
          activeColorMeta.shortLabel,
          session.activeEncoderName,
        ]
          .filter(Boolean)
          .join(' • ');

  return (
    <section
      style={{
        backgroundImage: `linear-gradient(to bottom, ${statusMeta.gradientTop}, var(--studio-surface) 70%)`,
      }}
      className="rounded-3xl border border-[var(--studio-outline-variant)] bg-[var(--studio-surface)] p-5"
    >
      <div className="flex flex-col items-center gap-3.5 text-center">
        {/* Status Indicator */}
        <div
          style={{
            backgroundColor: statusMeta.container,
            color: statusMeta.color,
          }}
          className="inline-flex items-center gap-2 rounded-full px-3.5 py-1.5 text-sm font-semibold"
        >
          <span
            style={{ backgroundColor: statusMeta.color }}
            className={`h-2 w-2 rounded-full ${
              phase === 'RECORDING' ? 'animate-pulse' : ''
            }`}
          />
          <span>{statusMeta.label}</span>
        </div>

        {/* Recording Timer */}
        <div className="font-mono-tabular text-[40px] leading-[46px] font-semibold tracking-[-0.5px] text-[var(--studio-on-surface)]">
          {formatElapsedTimer(session.elapsedMillis)}
        </div>

        {/* Session Readout */}
        <p className="font-mono-tabular text-xs font-medium leading-relaxed text-[var(--studio-on-surface-variant)]">
          {subtitleText}
        </p>

        {/* Unified GPU Color Pipeline Preview (shares exact shader & color mode with recording) */}
        <ColorPipelinePreview
          colorMode={phase === 'IDLE' ? settings.colorMode : session.activeColorMode}
          recordingMode={settings.recordingMode}
          regionAnimation={settings.regionAnimation}
          phase={phase}
          canvasWidthPx={canvasWidthPx}
          canvasHeightPx={canvasHeightPx}
          normalizedRegion={settings.selectedRegion}
          selectedPixelRegion={selectedPixelRegion}
          liveSourceVideo={liveSourceVideo}
        />

        {/* Primary Action Controls */}
        {phase === 'IDLE' && (
          <button
            type="button"
            onClick={onStartRecordClick}
            className="flex h-14 w-full items-center justify-center gap-2.5 whitespace-nowrap rounded-2xl bg-[var(--studio-record-accent)] px-6 text-base font-semibold text-white transition-opacity hover:opacity-95 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--studio-record-accent)]"
          >
            <Circle className="h-4.5 w-4.5 fill-white text-white" />
            <span>Record</span>
          </button>
        )}

        {(phase === 'RECORDING' || phase === 'PAUSED') && (
          <div className="grid w-full grid-cols-2 gap-3">
            <button
              type="button"
              onClick={onPauseResumeClick}
              className="flex h-[54px] items-center justify-center gap-2 whitespace-nowrap rounded-2xl border border-[var(--studio-outline)] bg-[var(--studio-surface-variant)] px-4 text-sm font-semibold text-[var(--studio-on-surface)] transition-colors hover:bg-[var(--studio-outline)]/50 focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
            >
              {phase === 'PAUSED' ? (
                <>
                  <Play className="h-4.5 w-4.5" />
                  <span>Resume</span>
                </>
              ) : (
                <>
                  <Pause className="h-4.5 w-4.5" />
                  <span>Pause</span>
                </>
              )}
            </button>
            <button
              type="button"
              onClick={onStopClick}
              className="flex h-[54px] items-center justify-center gap-2 whitespace-nowrap rounded-2xl bg-[var(--studio-record-accent)] px-4 text-sm font-semibold text-white transition-opacity hover:opacity-95 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--studio-record-accent)]"
            >
              <Square className="h-4 w-4 fill-white text-white" />
              <span>Stop</span>
            </button>
          </div>
        )}

        {phase === 'FINALIZING' && (
          <button
            type="button"
            disabled
            className="flex h-[54px] w-full items-center justify-center whitespace-nowrap rounded-2xl bg-[var(--studio-surface-variant)] px-6 text-sm font-semibold text-[var(--studio-on-surface-variant)] opacity-75"
          >
            Finalizing Video…
          </button>
        )}
      </div>
    </section>
  );
}

interface RegionPreviewCardProps {
  settings: RecordingSettings;
  phase: RecordingPhase;
  canvasWidthPx: number;
  canvasHeightPx: number;
  selectedPixelRegion: PixelRegion;
  enabled: boolean;
  onEditRegion: () => void;
}

function RegionPreviewCard({
  settings,
  phase,
  canvasWidthPx,
  canvasHeightPx,
  selectedPixelRegion,
  enabled,
  onEditRegion,
}: RegionPreviewCardProps) {
  const norm = settings.selectedRegion;
  const aspect = clamp(canvasWidthPx / Math.max(1, canvasHeightPx), 0.35, 2.5);
  const showAnim = settings.regionAnimation === 'SHOW';
  const isRecordingActive = phase === 'RECORDING';

  return (
    <SettingsSectionCard
      title="Selected Region"
      icon={<Crop className="h-4.5 w-4.5" />}
    >
      <div className="flex items-center gap-3.5">
        <div
          style={{ aspectRatio: String(aspect) }}
          className="w-[74px] shrink-0 overflow-hidden rounded-[10px] bg-[var(--studio-surface-variant)]"
        >
          <RegionBorderCanvas
            leftFraction={norm.left}
            topFraction={norm.top}
            rightFraction={norm.right}
            bottomFraction={norm.bottom}
            animate={showAnim && isRecordingActive}
            showBorder={showAnim}
            strokeWidth={2}
            scrimColor="rgba(0, 0, 0, 0.35)"
            neutralGuideColor="var(--studio-outline)"
            accentColor="var(--studio-record-accent)"
          />
        </div>

        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <p className="font-display font-mono-tabular text-base font-semibold text-[var(--studio-on-surface)]">
            {selectedPixelRegion.width} × {selectedPixelRegion.height} px
          </p>
          <p className="font-mono-tabular text-xs text-[var(--studio-on-surface-variant)]">
            Position ({selectedPixelRegion.left}, {selectedPixelRegion.top})
          </p>
          <button
            type="button"
            disabled={!enabled}
            onClick={onEditRegion}
            className="mt-1 flex h-10 w-full items-center justify-center gap-1.5 whitespace-nowrap rounded-xl bg-[var(--studio-surface-variant)] px-3 text-sm font-semibold text-[var(--studio-on-surface)] transition-colors hover:bg-[var(--studio-outline)]/60 focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
          >
            <SlidersHorizontal className="h-4 w-4" />
            <span>Adjust Region</span>
          </button>
        </div>
      </div>
    </SettingsSectionCard>
  );
}

interface SettingsSectionCardProps {
  title: string;
  icon: ReactNode;
  children: ReactNode;
}

function SettingsSectionCard({ title, icon, children }: SettingsSectionCardProps) {
  return (
    <section className="rounded-[18px] border border-[var(--studio-outline-variant)] bg-[var(--studio-surface)] p-4">
      <div className="flex flex-col gap-3">
        <div className="flex items-center gap-2">
          <span className="text-[var(--studio-on-surface-variant)]">{icon}</span>
          <h2 className="font-display text-base font-semibold text-[var(--studio-on-surface)]">
            {title}
          </h2>
        </div>
        {children}
      </div>
    </section>
  );
}

interface OptionTileProps {
  title: string;
  subtitle: string;
  icon: ReactNode;
  selected: boolean;
  enabled: boolean;
  onClick: () => void;
}

function OptionTile({
  title,
  subtitle,
  icon,
  selected,
  enabled,
  onClick,
}: OptionTileProps) {
  return (
    <button
      type="button"
      disabled={!enabled}
      onClick={onClick}
      className={`flex flex-col items-start gap-1 rounded-[14px] border p-3 text-left transition-colors focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)] ${
        selected
          ? 'border-[var(--studio-record-accent)] bg-[var(--studio-record-container)]'
          : 'border-[var(--studio-outline-variant)] bg-[var(--studio-surface-variant)]/55 hover:border-[var(--studio-outline)] hover:bg-[var(--studio-surface-variant)]/85'
      }`}
    >
      <div className="flex w-full items-center gap-1.5">
        <span
          className={
            selected
              ? 'text-[var(--studio-record-accent)]'
              : 'text-[var(--studio-on-surface)]'
          }
        >
          {icon}
        </span>
        <span
          className={`truncate text-sm ${
            selected
              ? 'font-bold text-[var(--studio-record-accent)]'
              : 'font-semibold text-[var(--studio-on-surface)]'
          }`}
        >
          {title}
        </span>
      </div>
      <p className="line-clamp-2 text-xs leading-snug text-[var(--studio-on-surface-variant)]">
        {subtitle}
      </p>
    </button>
  );
}
