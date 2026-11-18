import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {
  AudioSourceOption,
  ColorModeOption,
  NormalizedRegion,
  OrientationMode,
  RecordedVideoItem,
  RecordingMode,
  RecordingSessionState,
  RecordingSettings,
  RegionAnimationOption,
  VideoBitrateOption,
} from '../types/recorder';
import {
  loadRecordingSettings,
  saveRecordingSettings,
} from '../storage/settingsStorage';
import {
  deleteRecording as deleteRecordingFromDb,
  loadRecordings as loadRecordingsFromDb,
} from '../storage/recordingsDb';
import {
  resolveFullScreenDimensions,
  toPixelRegion,
} from '../utils/regionMath';
import { WebScreenRecorderSession } from '../services/screenRecorder';

const INITIAL_SESSION: RecordingSessionState = {
  phase: 'IDLE',
  elapsedMillis: 0,
  activeMode: 'FULL_SCREEN',
  activeColorMode: 'ORIGINAL',
  activeGraphicsBackend: '',
  activeAudioLabel: '',
  activeEncoderName: '',
  activeBitrateMbps: 0,
  activeResolutionLabel: '',
  activeRegion: null,
  showRegionBorder: false,
  lastSavedId: null,
  lastSavedName: null,
  warningMessage: null,
  errorMessage: null,
};

function queryScreenDims(): [number, number] {
  const dpr = Math.max(1, window.devicePixelRatio || 1);
  const w = Math.round(window.screen.width * dpr) || 1920;
  const h = Math.round(window.screen.height * dpr) || 1080;
  return [w, h];
}

export function useRecorder() {
  const [settings, setSettings] = useState<RecordingSettings>(() => loadRecordingSettings());
  const [session, setSession] = useState<RecordingSessionState>(INITIAL_SESSION);
  const [screenDims, setScreenDims] = useState<[number, number]>(() => queryScreenDims());
  const [isEditingRegion, setIsEditingRegion] = useState(false);
  const [recordings, setRecordings] = useState<RecordedVideoItem[]>([]);
  const [isLoadingRecordings, setIsLoadingRecordings] = useState(true);
  const [liveSourceVideo, setLiveSourceVideo] = useState<HTMLVideoElement | null>(null);

  const activeSessionRef = useRef<WebScreenRecorderSession | null>(null);

  const refreshRecordings = useCallback(async () => {
    setIsLoadingRecordings(true);
    const items = await loadRecordingsFromDb();
    setRecordings(items);
    setIsLoadingRecordings(false);
  }, []);

  useEffect(() => {
    void refreshRecordings();
    const handleResize = () => setScreenDims(queryScreenDims());
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('resize', handleResize);
    };
  }, [refreshRecordings]);

  const updateSettings = useCallback((updater: (prev: RecordingSettings) => RecordingSettings) => {
    setSettings((prev) => {
      const next = updater(prev);
      saveRecordingSettings(next);
      return next;
    });
  }, []);

  const [canvasWidthPx, canvasHeightPx] = useMemo(
    () => resolveFullScreenDimensions(screenDims[0], screenDims[1], settings.orientationMode),
    [screenDims, settings.orientationMode],
  );

  const selectedPixelRegion = useMemo(
    () => toPixelRegion(settings.selectedRegion, canvasWidthPx, canvasHeightPx),
    [settings.selectedRegion, canvasWidthPx, canvasHeightPx],
  );

  const setRecordingMode = useCallback(
    (mode: RecordingMode) => {
      if (session.phase !== 'IDLE') return;
      updateSettings((prev) => ({ ...prev, recordingMode: mode }));
    },
    [session.phase, updateSettings],
  );

  const setOrientationMode = useCallback(
    (mode: OrientationMode) => {
      if (session.phase !== 'IDLE') return;
      updateSettings((prev) => ({ ...prev, orientationMode: mode }));
    },
    [session.phase, updateSettings],
  );

  const setAudioSource = useCallback(
    (source: AudioSourceOption) => {
      if (session.phase !== 'IDLE') return;
      updateSettings((prev) => ({ ...prev, audioSource: source }));
    },
    [session.phase, updateSettings],
  );

  const setColorMode = useCallback(
    (colorMode: ColorModeOption) => {
      if (session.phase !== 'IDLE') return;
      updateSettings((prev) => ({ ...prev, colorMode }));
    },
    [session.phase, updateSettings],
  );

  const setVideoBitrate = useCallback(
    (bitrate: VideoBitrateOption) => {
      if (session.phase !== 'IDLE') return;
      updateSettings((prev) => ({ ...prev, videoBitrate: bitrate }));
    },
    [session.phase, updateSettings],
  );

  const setRegionAnimation = useCallback(
    (option: RegionAnimationOption) => {
      updateSettings((prev) => ({ ...prev, regionAnimation: option }));
      setSession((prev) => {
        if (prev.phase === 'RECORDING' || prev.phase === 'PAUSED') {
          return {
            ...prev,
            showRegionBorder: prev.activeMode === 'REGION' && option === 'SHOW',
          };
        }
        return prev;
      });
    },
    [updateSettings],
  );

  const openRegionEditor = useCallback(() => {
    if (session.phase !== 'IDLE') return;
    setScreenDims(queryScreenDims());
    setIsEditingRegion(true);
  }, [session.phase]);

  const closeRegionEditor = useCallback(() => {
    setIsEditingRegion(false);
  }, []);

  const saveSelectedRegion = useCallback(
    (region: NormalizedRegion) => {
      updateSettings((prev) => ({ ...prev, selectedRegion: region }));
      setIsEditingRegion(false);
    },
    [updateSettings],
  );

  const startRecording = useCallback(async () => {
    if (session.phase !== 'IDLE') return;

    setSession((prev) => ({
      ...prev,
      errorMessage: null,
      warningMessage: null,
      elapsedMillis: 0,
    }));

    const recorder = new WebScreenRecorderSession({
      onPhaseChange: (phase) => {
        setSession((prev) => ({
          ...prev,
          phase,
          elapsedMillis: phase === 'IDLE' ? 0 : prev.elapsedMillis,
          showRegionBorder: phase === 'IDLE' || phase === 'FINALIZING' ? false : prev.showRegionBorder,
        }));
        if (phase === 'IDLE') {
          activeSessionRef.current = null;
          setLiveSourceVideo(null);
          void refreshRecordings();
        }
      },
      onSessionMetadata: (meta) => {
        setSession((prev) => ({
          ...prev,
          activeMode: settings.recordingMode,
          activeColorMode: meta.activeColorMode,
          activeGraphicsBackend: meta.activeGraphicsBackend,
          activeAudioLabel: meta.activeAudioLabel,
          activeEncoderName: meta.activeEncoderName,
          activeBitrateMbps: meta.activeBitrateMbps,
          activeResolutionLabel: meta.activeResolutionLabel,
          activeRegion: meta.activeRegion,
          showRegionBorder: meta.showRegionBorder,
          warningMessage: meta.warningMessage ?? null,
        }));
      },
      onLiveSourceVideo: (video) => {
        setLiveSourceVideo(video);
      },
      onElapsedChange: (elapsedMillis) => {
        setSession((prev) => ({ ...prev, elapsedMillis }));
      },
      onSaved: (item) => {
        setSession((prev) => ({
          ...prev,
          lastSavedId: item.id,
          lastSavedName: item.displayName,
        }));
      },
      onError: (message) => {
        setSession((prev) => ({
          ...prev,
          errorMessage: message,
        }));
      },
    });

    activeSessionRef.current = recorder;

    try {
      await recorder.start(settings);
    } catch (err) {
      activeSessionRef.current = null;
      setLiveSourceVideo(null);
      const message =
        err instanceof Error ? err.message : 'Unable to start screen recording.';
      setSession((prev) => ({
        ...prev,
        phase: 'IDLE',
        elapsedMillis: 0,
        errorMessage: message,
      }));
    }
  }, [refreshRecordings, session.phase, settings]);

  const togglePauseResume = useCallback(() => {
    const recorder = activeSessionRef.current;
    if (!recorder) return;
    if (session.phase === 'RECORDING') {
      recorder.pause();
    } else if (session.phase === 'PAUSED') {
      recorder.resume();
    }
  }, [session.phase]);

  const stopRecording = useCallback(() => {
    const recorder = activeSessionRef.current;
    if (!recorder) return;
    if (session.phase === 'RECORDING' || session.phase === 'PAUSED') {
      void recorder.stop();
    }
  }, [session.phase]);

  const clearMessages = useCallback(() => {
    setSession((prev) => ({
      ...prev,
      warningMessage: null,
      errorMessage: null,
    }));
  }, []);

  const deleteRecording = useCallback(
    async (item: RecordedVideoItem) => {
      const ok = await deleteRecordingFromDb(item.id);
      if (ok) {
        setSession((prev) =>
          prev.lastSavedId === item.id
            ? { ...prev, lastSavedId: null, lastSavedName: null }
            : prev,
        );
        await refreshRecordings();
      }
    },
    [refreshRecordings],
  );

  return {
    settings,
    session,
    screenWidthPx: screenDims[0],
    screenHeightPx: screenDims[1],
    canvasWidthPx,
    canvasHeightPx,
    selectedPixelRegion,
    isEditingRegion,
    recordings,
    isLoadingRecordings,
    liveSourceVideo,
    setRecordingMode,
    setOrientationMode,
    setAudioSource,
    setColorMode,
    setVideoBitrate,
    setRegionAnimation,
    openRegionEditor,
    closeRegionEditor,
    saveSelectedRegion,
    startRecording,
    togglePauseResume,
    stopRecording,
    clearMessages,
    refreshRecordings,
    deleteRecording,
  };
}
