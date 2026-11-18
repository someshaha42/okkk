package com.example.region

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.compat.PlatformCompat
import com.example.settings.RegionRectPx

class RegionOverlayManager(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var overlayView: BorderOverlayView? = null
    private var currentRegion: RegionRectPx? = null
    private var isPausedState: Boolean = false

    fun show(region: RegionRectPx, isPaused: Boolean = false) {
        if (!PlatformCompat.canDrawOverlays(context)) return
        val wm = windowManager ?: return
        currentRegion = region
        isPausedState = isPaused

        val existing = overlayView
        if (existing != null) {
            existing.updateState(isPaused)
            val lp = existing.layoutParams as? WindowManager.LayoutParams ?: return
            lp.x = region.left
            lp.y = region.top
            lp.width = region.width
            lp.height = region.height
            runCatching { wm.updateViewLayout(existing, lp) }
            return
        }

        val view = BorderOverlayView(context).apply {
            updateState(isPaused)
        }
        val params = WindowManager.LayoutParams(
            region.width,
            region.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = region.left
            y = region.top
        }

        runCatching {
            wm.addView(view, params)
            overlayView = view
        }
    }

    fun setPaused(paused: Boolean) {
        isPausedState = paused
        overlayView?.updateState(paused)
    }

    fun hide() {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        runCatching { wm.removeViewImmediate(view) }
        overlayView = null
        currentRegion = null
    }

    private class BorderOverlayView(context: Context) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            pathEffect = DashPathEffect(floatArrayOf(22f, 12f), 0f)
            color = Color.parseColor("#DC2626")
        }

        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 9f
            color = Color.parseColor("#F4F6FA")
        }

        fun updateState(paused: Boolean) {
            strokePaint.color = if (paused) {
                Color.parseColor("#D97706")
            } else {
                Color.parseColor("#DC2626")
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = 4f
            val w = width.toFloat() - inset
            val h = height.toFloat() - inset
            if (w <= inset || h <= inset) return

            canvas.drawRect(inset, inset, w, h, strokePaint)
            val corner = 24f.coerceAtMost(minOf(w, h) * 0.25f)
            // Top-left
            canvas.drawLine(inset, inset, inset + corner, inset, cornerPaint)
            canvas.drawLine(inset, inset, inset, inset + corner, cornerPaint)
            // Top-right
            canvas.drawLine(w - corner, inset, w, inset, cornerPaint)
            canvas.drawLine(w, inset, w, inset + corner, cornerPaint)
            // Bottom-left
            canvas.drawLine(inset, h - corner, inset, h, cornerPaint)
            canvas.drawLine(inset, h, inset + corner, h, cornerPaint)
            // Bottom-right
            canvas.drawLine(w - corner, h, w, h, cornerPaint)
            canvas.drawLine(w, h - corner, w, h, cornerPaint)
        }
    }
}
