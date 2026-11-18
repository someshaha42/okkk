import type {
  NormalizedRegion,
  OrientationMode,
  PixelRegion,
} from '../types/recorder';

export const DEFAULT_NORMALIZED_REGION: NormalizedRegion = {
  left: 0.1,
  top: 0.2,
  right: 0.9,
  bottom: 0.7,
};

const MIN_SPAN_FRACTION = 0.12;
const MIN_PIXEL_SPAN = 128;

export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

export function normalizeRegion(region: NormalizedRegion): NormalizedRegion {
  const clampedLeft = clamp(region.left, 0, 1 - MIN_SPAN_FRACTION);
  const clampedTop = clamp(region.top, 0, 1 - MIN_SPAN_FRACTION);
  const clampedRight = clamp(region.right, clampedLeft + MIN_SPAN_FRACTION, 1);
  const clampedBottom = clamp(region.bottom, clampedTop + MIN_SPAN_FRACTION, 1);
  return {
    left: clampedLeft,
    top: clampedTop,
    right: clampedRight,
    bottom: clampedBottom,
  };
}

export function alignEven(value: number): number {
  const rounded = Math.round(value);
  return rounded % 2 === 0 ? Math.max(2, rounded) : Math.max(2, rounded - 1);
}

export function resolveFullScreenDimensions(
  rawWidth: number,
  rawHeight: number,
  orientationMode: OrientationMode = 'PORTRAIT',
): [number, number] {
  const evenW = alignEven(Math.max(128, rawWidth));
  const evenH = alignEven(Math.max(128, rawHeight));
  if (orientationMode === 'PORTRAIT') {
    return [Math.min(evenW, evenH), Math.max(evenW, evenH)];
  }
  return [Math.max(evenW, evenH), Math.min(evenW, evenH)];
}

export function toPixelRegion(
  normalized: NormalizedRegion,
  screenWidth: number,
  screenHeight: number,
): PixelRegion {
  const safeScreenW = alignEven(Math.max(MIN_PIXEL_SPAN, screenWidth));
  const safeScreenH = alignEven(Math.max(MIN_PIXEL_SPAN, screenHeight));
  const clean = normalizeRegion(normalized);

  const left = clamp(
    alignEven(Math.round(clean.left * safeScreenW)),
    0,
    safeScreenW - MIN_PIXEL_SPAN,
  );
  const top = clamp(
    alignEven(Math.round(clean.top * safeScreenH)),
    0,
    safeScreenH - MIN_PIXEL_SPAN,
  );
  let right = clamp(
    alignEven(Math.round(clean.right * safeScreenW)),
    left + MIN_PIXEL_SPAN,
    safeScreenW,
  );
  let bottom = clamp(
    alignEven(Math.round(clean.bottom * safeScreenH)),
    top + MIN_PIXEL_SPAN,
    safeScreenH,
  );

  if ((right - left) % 2 !== 0) {
    right = right > left + MIN_PIXEL_SPAN ? right - 1 : Math.min(right + 1, safeScreenW);
  }
  if ((bottom - top) % 2 !== 0) {
    bottom = bottom > top + MIN_PIXEL_SPAN ? bottom - 1 : Math.min(bottom + 1, safeScreenH);
  }

  if (left === right) right = Math.min(left + 2, safeScreenW);
  if (top === bottom) bottom = Math.min(top + 2, safeScreenH);

  return {
    left,
    top,
    right,
    bottom,
    screenWidth: safeScreenW,
    screenHeight: safeScreenH,
    width: Math.max(2, right - left),
    height: Math.max(2, bottom - top),
  };
}

export function applyAspectRatioPreset(
  current: NormalizedRegion,
  targetRatio: number,
  screenW: number,
  screenH: number,
): NormalizedRegion {
  const centerX = (current.left + current.right) / 2;
  const centerY = (current.top + current.bottom) / 2;
  const curW = (current.right - current.left) * screenW;

  let targetW = curW;
  let targetH = targetW / targetRatio;
  if (targetH > screenH * 0.85) {
    targetH = screenH * 0.85;
    targetW = targetH * targetRatio;
  }
  if (targetW > screenW * 0.92) {
    targetW = screenW * 0.92;
    targetH = targetW / targetRatio;
  }

  const halfFracW = targetW / screenW / 2;
  const halfFracH = targetH / screenH / 2;
  const clampedCx = clamp(centerX, halfFracW, 1 - halfFracW);
  const clampedCy = clamp(centerY, halfFracH, 1 - halfFracH);

  return normalizeRegion({
    left: clampedCx - halfFracW,
    top: clampedCy - halfFracH,
    right: clampedCx + halfFracW,
    bottom: clampedCy + halfFracH,
  });
}

export function formatElapsedTimer(millis: number): string {
  const totalSec = Math.max(0, Math.floor(millis / 1000));
  const hours = Math.floor(totalSec / 3600);
  const minutes = Math.floor((totalSec % 3600) / 60);
  const seconds = totalSec % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  if (hours > 0) {
    return `${hours}:${pad(minutes)}:${pad(seconds)}`;
  }
  return `${pad(minutes)}:${pad(seconds)}`;
}

export function formatDuration(ms: number): string {
  const totalSec = Math.max(0, Math.floor(ms / 1000));
  const mins = Math.floor(totalSec / 60);
  const secs = totalSec % 60;
  return `${mins}:${String(secs).padStart(2, '0')}`;
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${Math.round(kb)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1)} MB`;
  const gb = mb / 1024;
  return `${gb.toFixed(2)} GB`;
}

export function formatDate(epochSec: number): string {
  const d = new Date(epochSec * 1000);
  return d.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}
