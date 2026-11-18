package com.floaty.volume

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager

/**
 * The draggable floating bubble (overlay window).
 *
 * Drag moves the window; a short tap without movement opens the panel.
 *
 * Performance: WindowManager.updateViewLayout() is a cross-process transaction,
 * so calling it on every ACTION_MOVE makes dragging stutter badly on low-end
 * hardware. Moves are therefore coalesced to at most one layout update per
 * displayed frame via postOnAnimation(), and events that do not change the
 * integer position are dropped entirely.
 */
class BubbleOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val prefs: Prefs,
    private val onTap: () -> Unit,
    private val onPositionChanged: () -> Unit
) {

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong() + 120L
    private val bubbleSize = dp(56)

    /** True when a coalesced layout update is already scheduled for this frame. */
    private var updateScheduled = false

    private val applyPendingLayout = Runnable {
        updateScheduled = false
        val v = view
        val p = params
        if (v != null && p != null) safeUpdate(v, p)
    }

    val isShowing: Boolean
        get() = view != null

    /** Current bubble centre in screen pixels (falls back to the window offset). */
    fun center(): Point {
        val v = view
        val p = params
        if (v == null || p == null) return Point(0, 0)
        val w = if (p.width > 0) p.width else v.width
        val h = if (p.height > 0) p.height else v.height
        return Point(p.x + w / 2, p.y + h / 2)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (view != null) return

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_bubble, null)
        val size = bubbleSize
        val defaults = defaultPosition(size)

        val lp = WindowManager.LayoutParams(
            size,
            size,
            OverlayWindows.type(),
            // No FLAG_LAYOUT_NO_LIMITS: it can force a software layer on some
            // ROMs. The bubble is clamped to the screen instead, which is cheaper
            // and also keeps it reachable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX >= 0) prefs.bubbleX else defaults.x
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else defaults.y
        }

        root.setOnTouchListener(object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startX = 0
            private var startY = 0
            private var downTime = 0L
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startX = lp.x
                        startY = lp.y
                        downTime = SystemClock.uptimeMillis()
                        dragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startRawX).toInt()
                        val dy = (event.rawY - startRawY).toInt()
                        if (!dragging &&
                            (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)
                        ) {
                            dragging = true
                        }
                        if (!dragging) return true

                        val newX = startX + dx
                        val newY = startY + dy
                        // Sub-pixel / repeated positions cost a full transaction
                        // for nothing, so skip them.
                        if (newX == lp.x && newY == lp.y) return true
                        lp.x = newX
                        lp.y = newY
                        scheduleUpdate(v)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val elapsed = SystemClock.uptimeMillis() - downTime
                        if (!dragging && elapsed <= tapTimeout) {
                            onTap()
                        } else {
                            // Snap back inside the screen and commit the position
                            // immediately (no coalescing: this is the final frame).
                            flushUpdate(v, lp)
                            prefs.bubbleX = lp.x
                            prefs.bubbleY = lp.y
                            onPositionChanged()
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        flushUpdate(v, lp)
                        prefs.bubbleX = lp.x
                        prefs.bubbleY = lp.y
                        onPositionChanged()
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(root, lp)
            view = root
            params = lp
            prefs.bubbleX = lp.x
            prefs.bubbleY = lp.y
        } catch (e: Exception) {
            view = null
            params = null
        }
    }

    fun hide() {
        val v = view ?: return
        v.removeCallbacks(applyPendingLayout)
        updateScheduled = false
        try {
            windowManager.removeView(v)
        } catch (ignored: Exception) {
            // Already detached.
        }
        view = null
        params = null
    }

    /** Re-clamps the bubble (e.g. after a rotation) and persists the result. */
    fun reclamp() {
        val v = view ?: return
        val p = params ?: return
        clampInto(p)
        flushUpdate(v, p)
        prefs.bubbleX = p.x
        prefs.bubbleY = p.y
    }

    /** Queues at most one layout transaction per frame. */
    private fun scheduleUpdate(v: View) {
        if (updateScheduled) return
        updateScheduled = true
        v.postOnAnimation(applyPendingLayout)
    }

    /** Applies the position now, dropping any pending coalesced update. */
    private fun flushUpdate(v: View, p: WindowManager.LayoutParams) {
        v.removeCallbacks(applyPendingLayout)
        updateScheduled = false
        clampInto(p)
        safeUpdate(v, p)
    }

    private fun safeUpdate(v: View, p: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(v, p)
        } catch (ignored: Exception) {
            // Window may have been removed concurrently.
        }
    }

    private fun clampInto(lp: WindowManager.LayoutParams) {
        val screen = screenSize()
        val maxX = (screen.x - bubbleSize).coerceAtLeast(0)
        val maxY = (screen.y - bubbleSize).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
    }

    private fun defaultPosition(size: Int): Point {
        val screen = screenSize()
        return Point(screen.x - size - dp(8), (screen.y * 0.55f).toInt())
    }

    private fun screenSize(): Point {
        val metrics = context.resources.displayMetrics
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
