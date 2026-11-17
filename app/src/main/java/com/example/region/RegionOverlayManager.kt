package com.example.region

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * Optional floating region indicator overlay. When RegionAnimationOption.SHOW is enabled AND the
 * user has granted Display Over Other Apps permission, attaches a non-touchable animated deep-red
 * dashed border strictly outside the crop rectangle. When overlay permission is unavailable or
 * animation is set to HIDE, this manager gracefully no-ops so recording never blocks.
 */
class RegionOverlayManager(private val context: Context) {

  private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private var overlayView: RegionBorderOverlayView? = null

  fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

  fun show(region: PixelRegion, enabled: Boolean) {
    hide()
    if (!enabled) return
    if (!canDrawOverlay()) {
      Log.d(TAG, "Overlay permission unavailable; continuing region recording without floating border")
      return
    }

    val view = RegionBorderOverlayView(context, region)
    val windowType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
      }

    val params =
      WindowManager.LayoutParams(
          WindowManager.LayoutParams.MATCH_PARENT,
          WindowManager.LayoutParams.MATCH_PARENT,
          windowType,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
          PixelFormat.TRANSLUCENT,
        )
        .apply {
          gravity = Gravity.TOP or Gravity.START
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
              WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
          }
        }

    runCatching {
        windowManager.addView(view, params)
        overlayView = view
        view.startAnimation()
      }
      .onFailure { Log.w(TAG, "Unable to attach region overlay; continuing without border", it) }
  }

  fun setPaused(paused: Boolean) {
    overlayView?.setPaused(paused)
  }

  fun hide() {
    val view = overlayView ?: return
    overlayView = null
    view.stopAnimation()
    runCatching { windowManager.removeViewImmediate(view) }
  }

  companion object {
    private const val TAG = "RegionOverlayManager"
  }
}

private class RegionBorderOverlayView(
  context: Context,
  private val region: PixelRegion,
) : View(context) {

  private val density = resources.displayMetrics.density
  private val strokePx = 3f * density
  private val dashLength = 14f * density
  private val gapLength = 9f * density
  private val dashPeriod = dashLength + gapLength

  private var phase = 0f
  private var paused = false

  private val borderPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = strokePx
      color = Color.rgb(220, 38, 38) // Deep studio crimson
    }

  private val backingPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = strokePx + 2f * density
      color = Color.argb(110, 17, 19, 24)
    }

  private val badgePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.rgb(185, 28, 28)
    }

  private val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 10f * density
      isFakeBoldText = true
    }

  private val drawRect = RectF()
  private val badgeRect = RectF()
  private var animator: ValueAnimator? = null

  fun startAnimation() {
    if (animator != null) return
    animator =
      ValueAnimator.ofFloat(0f, dashPeriod).apply {
        duration = 700L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
          phase = -(anim.animatedValue as Float)
          invalidate()
        }
        start()
      }
  }

  fun setPaused(isPaused: Boolean) {
    paused = isPaused
    if (isPaused) {
      animator?.pause()
      borderPaint.color = Color.rgb(217, 119, 6) // Amber when paused
      badgePaint.color = Color.rgb(180, 83, 9)
    } else {
      borderPaint.color = Color.rgb(220, 38, 38)
      badgePaint.color = Color.rgb(185, 28, 28)
      animator?.resume()
    }
    invalidate()
  }

  fun stopAnimation() {
    animator?.cancel()
    animator = null
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    // Position the stroke strictly outside the crop region so no border pixels enter the recording
    val half = strokePx / 2f + 1.5f * density
    drawRect.set(
      (region.left - half).coerceAtLeast(half),
      (region.top - half).coerceAtLeast(half),
      (region.right + half).coerceAtMost(width - half),
      (region.bottom + half).coerceAtMost(height - half),
    )

    canvas.drawRect(drawRect, backingPaint)
    borderPaint.pathEffect = DashPathEffect(floatArrayOf(dashLength, gapLength), phase)
    canvas.drawRect(drawRect, borderPaint)

    val label = if (paused) "PAUSED" else "REC REGION"
    val labelW = textPaint.measureText(label) + 14f * density
    val labelH = 18f * density
    val badgeTop = (drawRect.top - labelH - 4f * density).coerceAtLeast(4f * density)
    val badgeLeft = drawRect.left.coerceAtMost((width - labelW - 4f * density).coerceAtLeast(0f))
    badgeRect.set(badgeLeft, badgeTop, badgeLeft + labelW, badgeTop + labelH)
    canvas.drawRoundRect(badgeRect, 5f * density, 5f * density, badgePaint)
    canvas.drawText(
      label,
      badgeRect.left + 7f * density,
      badgeRect.bottom - 5f * density,
      textPaint,
    )
  }

  override fun onDetachedFromWindow() {
    stopAnimation()
    super.onDetachedFromWindow()
  }
}
