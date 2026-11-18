import { useEffect, useRef, useState } from 'react';
import {
  GpuColorPipeline,
  type GraphicsBackendKind,
  paintCalibrationSource,
} from '../gpu/GpuColorPipeline';
import type {
  ColorModeOption,
  NormalizedRegion,
  PixelRegion,
  RecordingMode,
  RecordingPhase,
  RegionAnimationOption,
} from '../types/recorder';
import { clamp } from '../utils/regionMath';
import { RegionBorderCanvas } from './RegionBorderCanvas';

interface ColorPipelinePreviewProps {
  colorMode: ColorModeOption;
  recordingMode: RecordingMode;
  regionAnimation: RegionAnimationOption;
  phase: RecordingPhase;
  canvasWidthPx: number;
  canvasHeightPx: number;
  normalizedRegion: NormalizedRegion;
  selectedPixelRegion: PixelRegion;
  liveSourceVideo: HTMLVideoElement | null;
}

export function ColorPipelinePreview({
  colorMode,
  recordingMode,
  regionAnimation,
  phase,
  canvasWidthPx,
  canvasHeightPx,
  normalizedRegion,
  selectedPixelRegion,
  liveSourceVideo,
}: ColorPipelinePreviewProps) {
  const gpuCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const pipelineRef = useRef<GpuColorPipeline | null>(null);
  const calibrationCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const [backendLabel, setBackendLabel] = useState<GraphicsBackendKind>('WebGL2 Shader');

  const isLive = Boolean(liveSourceVideo) && (phase === 'RECORDING' || phase === 'PAUSED');

  // Preview stage aspect matches output target aspect (full screen or cropped region when live)
  const previewAspect = isLive && recordingMode === 'REGION'
    ? clamp(selectedPixelRegion.width / Math.max(1, selectedPixelRegion.height), 0.4, 2.5)
    : clamp(canvasWidthPx / Math.max(1, canvasHeightPx), 0.45, 2.2);

  useEffect(() => {
    const canvas = gpuCanvasRef.current;
    if (!canvas) return;
    let disposed = false;

    // Fixed crisp preview resolution (efficient on GPU)
    const previewW = previewAspect >= 1 ? 480 : Math.round(480 * previewAspect);
    const previewH = previewAspect >= 1 ? Math.round(480 / previewAspect) : 480;
    canvas.width = Math.max(120, previewW - (previewW % 2));
    canvas.height = Math.max(120, previewH - (previewH % 2));

    void GpuColorPipeline.create(canvas, true).then((pipe) => {
      if (disposed) {
        pipe.dispose();
        return;
      }
      pipelineRef.current?.dispose();
      pipelineRef.current = pipe;
      setBackendLabel(pipe.backend);

      // Draw initial calibration frame immediately
      if (!liveSourceVideo) {
        if (!calibrationCanvasRef.current) {
          calibrationCanvasRef.current = document.createElement('canvas');
        }
        const cal = calibrationCanvasRef.current;
        paintCalibrationSource(cal, canvas.width, canvas.height);
        pipe.renderFrame({
          source: cal,
          sourceWidth: cal.width,
          sourceHeight: cal.height,
          cropRegion: null,
          colorMode,
        });
      }
    });

    return () => {
      disposed = true;
      pipelineRef.current?.dispose();
      pipelineRef.current = null;
    };
    // Re-init context only if preview aspect bucket changes significantly or unmount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Update canvas dimensions cleanly when orientation/aspect changes
  useEffect(() => {
    const canvas = gpuCanvasRef.current;
    const pipe = pipelineRef.current;
    if (!canvas) return;

    const previewW = previewAspect >= 1 ? 480 : Math.round(480 * previewAspect);
    const previewH = previewAspect >= 1 ? Math.round(480 / previewAspect) : 480;
    const evenW = Math.max(120, previewW - (previewW % 2));
    const evenH = Math.max(120, previewH - (previewH % 2));

    if (canvas.width !== evenW || canvas.height !== evenH) {
      canvas.width = evenW;
      canvas.height = evenH;
    }

    if (!isLive && pipe) {
      if (!calibrationCanvasRef.current) {
        calibrationCanvasRef.current = document.createElement('canvas');
      }
      const cal = calibrationCanvasRef.current;
      paintCalibrationSource(cal, evenW, evenH);
      pipe.renderFrame({
        source: cal,
        sourceWidth: cal.width,
        sourceHeight: cal.height,
        cropRegion: null,
        colorMode,
      });
    }
  }, [colorMode, isLive, previewAspect]);

  // Live GPU preview render loop during active recording (identical shader & crop to encoder)
  useEffect(() => {
    if (!isLive || !liveSourceVideo) return;

    let rafId: number | null = null;
    let lastDraw = 0;
    const minIntervalMs = 1000 / 30;

    const tick = (now: number) => {
      const pipe = pipelineRef.current;
      if (pipe && liveSourceVideo.readyState >= 2 && now - lastDraw >= minIntervalMs) {
        lastDraw = now;
        pipe.renderFrame({
          source: liveSourceVideo,
          sourceWidth: liveSourceVideo.videoWidth || canvasWidthPx,
          sourceHeight: liveSourceVideo.videoHeight || canvasHeightPx,
          cropRegion: recordingMode === 'REGION' ? selectedPixelRegion : null,
          colorMode,
        });
      }
      rafId = requestAnimationFrame(tick);
    };

    rafId = requestAnimationFrame(tick);
    return () => {
      if (rafId !== null) cancelAnimationFrame(rafId);
    };
  }, [
    isLive,
    liveSourceVideo,
    canvasWidthPx,
    canvasHeightPx,
    recordingMode,
    selectedPixelRegion,
    colorMode,
  ]);

  const showRegionOverlay = recordingMode === 'REGION' && !isLive;
  const showRedBorder = regionAnimation === 'SHOW';

  return (
    <div className="w-full rounded-2xl border border-[var(--studio-outline-variant)] bg-[var(--studio-bg)] p-2.5">
      <div className="mb-2 flex items-center justify-between gap-2 text-xs text-[var(--studio-on-surface-variant)]">
        <span className="font-medium text-[var(--studio-on-surface)]">
          {isLive ? 'Live Pipeline Preview' : 'Color Pipeline Preview'}
        </span>
        <span className="font-mono-tabular truncate">{backendLabel}</span>
      </div>

      <div className="flex justify-center">
        <div
          style={{ aspectRatio: String(previewAspect) }}
          className="relative max-h-[176px] w-auto max-w-full overflow-hidden rounded-xl border border-[var(--studio-outline-variant)] bg-black"
        >
          <canvas
            ref={gpuCanvasRef}
            className="block h-full w-full object-contain"
          />

          {showRegionOverlay && (
            <div className="pointer-events-none absolute inset-0">
              <RegionBorderCanvas
                leftFraction={normalizedRegion.left}
                topFraction={normalizedRegion.top}
                rightFraction={normalizedRegion.right}
                bottomFraction={normalizedRegion.bottom}
                animate={false}
                showBorder={showRedBorder}
                strokeWidth={2}
                scrimColor="rgba(0, 0, 0, 0.42)"
                neutralGuideColor="rgba(255, 255, 255, 0.72)"
                accentColor="var(--studio-record-accent)"
              />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
