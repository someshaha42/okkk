import { useEffect, useRef } from 'react';

interface RegionBorderCanvasProps {
  leftFraction: number;
  topFraction: number;
  rightFraction: number;
  bottomFraction: number;
  animate: boolean;
  showBorder: boolean;
  strokeWidth?: number;
  scrimColor?: string;
  neutralGuideColor?: string;
  accentColor?: string;
  className?: string;
}

/**
 * Lightweight GPU-friendly deep crimson animated dashed border.
 * When showBorder or animate is false, no requestAnimationFrame loop runs.
 */
export function RegionBorderCanvas({
  leftFraction,
  topFraction,
  rightFraction,
  bottomFraction,
  animate,
  showBorder,
  strokeWidth = 2.5,
  scrimColor = 'transparent',
  neutralGuideColor = 'transparent',
  accentColor = '#DC2626',
  className = '',
}: RegionBorderCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let rafId: number | null = null;
    let startTime = performance.now();

    const draw = (phase: number) => {
      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();
      const cssW = Math.max(1, rect.width);
      const cssH = Math.max(1, rect.height);
      const targetW = Math.round(cssW * dpr);
      const targetH = Math.round(cssH * dpr);

      if (canvas.width !== targetW || canvas.height !== targetH) {
        canvas.width = targetW;
        canvas.height = targetH;
      }

      ctx.save();
      ctx.scale(dpr, dpr);
      ctx.clearRect(0, 0, cssW, cssH);

      const l = Math.min(Math.max(leftFraction * cssW, 0), cssW);
      const t = Math.min(Math.max(topFraction * cssH, 0), cssH);
      const r = Math.min(Math.max(rightFraction * cssW, l), cssW);
      const b = Math.min(Math.max(bottomFraction * cssH, t), cssH);

      if (scrimColor !== 'transparent') {
        ctx.fillStyle = scrimColor;
        ctx.fillRect(0, 0, cssW, t);
        ctx.fillRect(0, b, cssW, Math.max(0, cssH - b));
        ctx.fillRect(0, t, l, Math.max(0, b - t));
        ctx.fillRect(r, t, Math.max(0, cssW - r), Math.max(0, b - t));
      }

      const rectW = Math.max(1, r - l);
      const rectH = Math.max(1, b - t);

      if (showBorder) {
        const dashPx = 10;
        const gapPx = 7;

        ctx.setLineDash([]);
        ctx.strokeStyle = 'rgba(0, 0, 0, 0.35)';
        ctx.lineWidth = strokeWidth + 1.5;
        ctx.strokeRect(l, t, rectW, rectH);

        ctx.setLineDash([dashPx, gapPx]);
        ctx.lineDashOffset = phase;
        ctx.strokeStyle = accentColor;
        ctx.lineWidth = strokeWidth;
        ctx.strokeRect(l, t, rectW, rectH);
        ctx.setLineDash([]);
      } else if (neutralGuideColor !== 'transparent') {
        ctx.setLineDash([]);
        ctx.strokeStyle = neutralGuideColor;
        ctx.lineWidth = 1.5;
        ctx.strokeRect(l, t, rectW, rectH);
      }

      ctx.restore();
    };

    if (showBorder && animate) {
      const period = 17;
      const durationMs = 650;
      const loop = (now: number) => {
        const elapsed = (now - startTime) % durationMs;
        const phase = -(elapsed / durationMs) * period;
        draw(phase);
        rafId = requestAnimationFrame(loop);
      };
      rafId = requestAnimationFrame(loop);
    } else {
      draw(0);
    }

    const observer = new ResizeObserver(() => {
      if (!(showBorder && animate)) {
        draw(0);
      }
    });
    observer.observe(canvas);

    return () => {
      if (rafId !== null) cancelAnimationFrame(rafId);
      observer.disconnect();
    };
  }, [
    leftFraction,
    topFraction,
    rightFraction,
    bottomFraction,
    animate,
    showBorder,
    strokeWidth,
    scrimColor,
    neutralGuideColor,
    accentColor,
  ]);

  return (
    <canvas
      ref={canvasRef}
      aria-hidden="true"
      className={`pointer-events-none block h-full w-full ${className}`}
    />
  );
}
