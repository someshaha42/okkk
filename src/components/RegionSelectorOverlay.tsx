import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Check, Move, RotateCcw, X } from 'lucide-react';
import type { NormalizedRegion, RegionAnimationOption } from '../types/recorder';
import {
  DEFAULT_NORMALIZED_REGION,
  applyAspectRatioPreset,
  clamp,
  normalizeRegion,
  toPixelRegion,
} from '../utils/regionMath';
import { RegionBorderCanvas } from './RegionBorderCanvas';

type AspectPresetKey = 'FREE' | 'R16_9' | 'R9_16' | 'R4_3' | 'R1_1';

const ASPECT_PRESETS: Array<{ key: AspectPresetKey; label: string; ratio: number | null }> = [
  { key: 'FREE', label: 'Free', ratio: null },
  { key: 'R16_9', label: '16:9', ratio: 16 / 9 },
  { key: 'R9_16', label: '9:16', ratio: 9 / 16 },
  { key: 'R4_3', label: '4:3', ratio: 4 / 3 },
  { key: 'R1_1', label: '1:1', ratio: 1 },
];

type DragKind =
  | 'move'
  | 'top-left'
  | 'top-right'
  | 'bottom-left'
  | 'bottom-right'
  | 'top'
  | 'bottom'
  | 'left'
  | 'right';

interface RegionSelectorOverlayProps {
  initialRegion: NormalizedRegion;
  screenWidthPx: number;
  screenHeightPx: number;
  regionAnimation: RegionAnimationOption;
  onConfirm: (region: NormalizedRegion) => void;
  onCancel: () => void;
}

const MIN_SPAN_FRACTION = 0.14;

export function RegionSelectorOverlay({
  initialRegion,
  screenWidthPx,
  screenHeightPx,
  regionAnimation,
  onConfirm,
  onCancel,
}: RegionSelectorOverlayProps) {
  const [draft, setDraft] = useState<NormalizedRegion>(() => normalizeRegion(initialRegion));
  const [selectedPreset, setSelectedPreset] = useState<AspectPresetKey>('FREE');
  const stageRef = useRef<HTMLDivElement | null>(null);
  const [stageSize, setStageSize] = useState<{ w: number; h: number }>({
    w: Math.max(1, screenWidthPx),
    h: Math.max(1, screenHeightPx),
  });

  const dragStateRef = useRef<{
    kind: DragKind;
    startX: number;
    startY: number;
    startDraft: NormalizedRegion;
  } | null>(null);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onCancel]);

  useEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const updateSize = () => {
      const rect = el.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        setStageSize({ w: rect.width, h: rect.height });
      }
    };
    updateSize();
    const ro = new ResizeObserver(updateSize);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const pixelRegion = useMemo(
    () => toPixelRegion(draft, screenWidthPx, screenHeightPx),
    [draft, screenWidthPx, screenHeightPx],
  );

  const canvasAspect = clamp(screenWidthPx / Math.max(1, screenHeightPx), 0.3, 3.0);
  const hudAtTop = draft.bottom > 0.74 && draft.top > 0.24;
  const showRedBorder = regionAnimation === 'SHOW';

  const startDrag = useCallback(
    (kind: DragKind, e: React.PointerEvent<HTMLElement>) => {
      e.stopPropagation();
      e.currentTarget.setPointerCapture(e.pointerId);
      dragStateRef.current = {
        kind,
        startX: e.clientX,
        startY: e.clientY,
        startDraft: draft,
      };
    },
    [draft],
  );

  const onPointerMove = useCallback(
    (e: React.PointerEvent<HTMLElement>) => {
      const active = dragStateRef.current;
      if (!active) return;

      const dx = (e.clientX - active.startX) / Math.max(1, stageSize.w);
      const dy = (e.clientY - active.startY) / Math.max(1, stageSize.h);
      const s = active.startDraft;

      if (active.kind === 'move') {
        const wFrac = s.right - s.left;
        const hFrac = s.bottom - s.top;
        const newLeft = clamp(s.left + dx, 0, 1 - wFrac);
        const newTop = clamp(s.top + dy, 0, 1 - hFrac);
        setDraft({
          left: newLeft,
          top: newTop,
          right: newLeft + wFrac,
          bottom: newTop + hFrac,
        });
        return;
      }

      setSelectedPreset('FREE');

      let next = { ...s };
      if (active.kind === 'top-left' || active.kind === 'bottom-left' || active.kind === 'left') {
        next.left = clamp(s.left + dx, 0, s.right - MIN_SPAN_FRACTION);
      }
      if (active.kind === 'top-right' || active.kind === 'bottom-right' || active.kind === 'right') {
        next.right = clamp(s.right + dx, s.left + MIN_SPAN_FRACTION, 1);
      }
      if (active.kind === 'top-left' || active.kind === 'top-right' || active.kind === 'top') {
        next.top = clamp(s.top + dy, 0, s.bottom - MIN_SPAN_FRACTION);
      }
      if (active.kind === 'bottom-left' || active.kind === 'bottom-right' || active.kind === 'bottom') {
        next.bottom = clamp(s.bottom + dy, s.top + MIN_SPAN_FRACTION, 1);
      }

      setDraft(normalizeRegion(next));
    },
    [stageSize.h, stageSize.w],
  );

  const endDrag = useCallback((e: React.PointerEvent<HTMLElement>) => {
    if (dragStateRef.current) {
      dragStateRef.current = null;
      if (e.currentTarget.hasPointerCapture(e.pointerId)) {
        e.currentTarget.releasePointerCapture(e.pointerId);
      }
    }
  }, []);

  const leftPct = `${draft.left * 100}%`;
  const topPct = `${draft.top * 100}%`;
  const widthPct = `${(draft.right - draft.left) * 100}%`;
  const heightPct = `${(draft.bottom - draft.top) * 100}%`;
  const midXPct = `${((draft.left + draft.right) / 2) * 100}%`;
  const midYPct = `${((draft.top + draft.bottom) / 2) * 100}%`;
  const rightPct = `${draft.right * 100}%`;
  const bottomPct = `${draft.bottom * 100}%`;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Adjust recording region"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/88 p-4 backdrop-blur-xs"
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
    >
      {/* Aspect-locked screen stage */}
      <div
        ref={stageRef}
        style={{
          aspectRatio: String(canvasAspect),
        }}
        className="relative max-h-[78vh] max-w-[92vw] w-auto h-full rounded-xl border border-white/25 bg-[var(--studio-bg)] overflow-visible shadow-2xl"
      >
        <div className="absolute inset-0 overflow-hidden rounded-xl">
          <RegionBorderCanvas
            leftFraction={draft.left}
            topFraction={draft.top}
            rightFraction={draft.right}
            bottomFraction={draft.bottom}
            animate={showRedBorder}
            showBorder={showRedBorder}
            strokeWidth={3}
            scrimColor="rgba(0, 0, 0, 0.58)"
            neutralGuideColor="rgba(255, 255, 255, 0.78)"
            accentColor="#DC2626"
          />
        </div>

        {/* Draggable interior area */}
        <div
          onPointerDown={(e) => startDrag('move', e)}
          style={{
            left: leftPct,
            top: topPct,
            width: widthPct,
            height: heightPct,
          }}
          className="absolute flex cursor-move touch-none items-center justify-center"
          aria-label="Drag to move region"
        >
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-black/60 text-white">
            <Move className="h-5 w-5" />
          </div>
        </div>

        {/* 4 Corner Handles */}
        <ResizeHandle
          left={leftPct}
          top={topPct}
          variant="corner"
          label="Resize top left corner"
          onPointerDown={(e) => startDrag('top-left', e)}
        />
        <ResizeHandle
          left={rightPct}
          top={topPct}
          variant="corner"
          label="Resize top right corner"
          onPointerDown={(e) => startDrag('top-right', e)}
        />
        <ResizeHandle
          left={leftPct}
          top={bottomPct}
          variant="corner"
          label="Resize bottom left corner"
          onPointerDown={(e) => startDrag('bottom-left', e)}
        />
        <ResizeHandle
          left={rightPct}
          top={bottomPct}
          variant="corner"
          label="Resize bottom right corner"
          onPointerDown={(e) => startDrag('bottom-right', e)}
        />

        {/* 4 Edge Handles */}
        <ResizeHandle
          left={midXPct}
          top={topPct}
          variant="edge"
          label="Adjust top edge"
          onPointerDown={(e) => startDrag('top', e)}
        />
        <ResizeHandle
          left={midXPct}
          top={bottomPct}
          variant="edge"
          label="Adjust bottom edge"
          onPointerDown={(e) => startDrag('bottom', e)}
        />
        <ResizeHandle
          left={leftPct}
          top={midYPct}
          variant="edge"
          label="Adjust left edge"
          onPointerDown={(e) => startDrag('left', e)}
        />
        <ResizeHandle
          left={rightPct}
          top={midYPct}
          variant="edge"
          label="Adjust right edge"
          onPointerDown={(e) => startDrag('right', e)}
        />
      </div>

      {/* Floating Control HUD */}
      <div
        className={`fixed left-1/2 z-50 w-[min(520px,calc(100vw-2rem))] -translate-x-1/2 rounded-[20px] border border-[var(--studio-outline)] bg-[var(--studio-surface)]/95 p-3.5 shadow-2xl backdrop-blur-md ${
          hudAtTop ? 'top-4' : 'bottom-4'
        }`}
      >
        <div className="flex flex-col gap-2.5">
          <div className="flex items-center justify-between gap-3">
            <div>
              <p className="font-display font-mono-tabular text-base font-semibold text-[var(--studio-on-surface)]">
                {pixelRegion.width} × {pixelRegion.height} px
              </p>
              <p className="font-mono-tabular text-xs text-[var(--studio-on-surface-variant)]">
                Origin ({pixelRegion.left}, {pixelRegion.top})
              </p>
            </div>
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => {
                  setSelectedPreset('FREE');
                  setDraft(DEFAULT_NORMALIZED_REGION);
                }}
                aria-label="Reset region"
                title="Reset"
                className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--studio-on-surface-variant)] transition-colors hover:bg-[var(--studio-surface-variant)] hover:text-[var(--studio-on-surface)] focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
              >
                <RotateCcw className="h-4.5 w-4.5" />
              </button>
              <button
                type="button"
                onClick={onCancel}
                aria-label="Cancel"
                title="Cancel"
                className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--studio-on-surface-variant)] transition-colors hover:bg-[var(--studio-surface-variant)] hover:text-[var(--studio-on-surface)] focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
              >
                <X className="h-4.5 w-4.5" />
              </button>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            {ASPECT_PRESETS.map((preset) => {
              const active = selectedPreset === preset.key;
              return (
                <button
                  key={preset.key}
                  type="button"
                  onClick={() => {
                    setSelectedPreset(preset.key);
                    if (preset.ratio !== null) {
                      setDraft((prev) =>
                        applyAspectRatioPreset(prev, preset.ratio!, stageSize.w, stageSize.h),
                      );
                    }
                  }}
                  className={`whitespace-nowrap shrink-0 rounded-lg border px-3 py-1.5 font-mono-tabular text-xs font-medium transition-colors ${
                    active
                      ? 'border-[var(--studio-record-accent)] bg-[var(--studio-record-container)] text-[var(--studio-record-accent)]'
                      : 'border-[var(--studio-outline)] bg-[var(--studio-surface-variant)]/55 text-[var(--studio-on-surface-variant)] hover:text-[var(--studio-on-surface)]'
                  }`}
                >
                  {preset.label}
                </button>
              );
            })}
          </div>

          <button
            type="button"
            onClick={() => onConfirm(normalizeRegion(draft))}
            className="flex h-11 w-full items-center justify-center gap-2 whitespace-nowrap rounded-full bg-[var(--studio-record-accent)] px-5 text-sm font-semibold text-white transition-opacity hover:opacity-95 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--studio-record-accent)]"
          >
            <Check className="h-4.5 w-4.5" />
            <span>Confirm Region</span>
          </button>
        </div>
      </div>
    </div>
  );
}

interface ResizeHandleProps {
  left: string;
  top: string;
  variant: 'corner' | 'edge';
  label: string;
  onPointerDown: (e: React.PointerEvent<HTMLButtonElement>) => void;
}

function ResizeHandle({ left, top, variant, label, onPointerDown }: ResizeHandleProps) {
  return (
    <button
      type="button"
      aria-label={label}
      onPointerDown={onPointerDown}
      style={{ left, top }}
      className="absolute flex h-11 w-11 -translate-x-1/2 -translate-y-1/2 cursor-grab touch-none items-center justify-center rounded-full focus-visible:outline-2 focus-visible:outline-white active:cursor-grabbing"
    >
      <span
        className={`h-4 w-4 rounded-full border-2 border-[#111318] shadow-sm ${
          variant === 'corner' ? 'bg-[var(--studio-record-accent)]' : 'bg-white'
        }`}
      />
    </button>
  );
}
