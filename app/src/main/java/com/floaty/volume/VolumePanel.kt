package com.floaty.volume

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Compact volume panel shown as a second overlay window.
 *
 * The window is a full-screen scrim; the card sits centered horizontally with
 * its top at 20% of the screen height (the bubble docks just above it) and
 * any touch outside the card closes the panel.
 *
 * Performance: the view hierarchy is inflated once and reused across opens
 * (hide() only detaches the window), slider drags write to AudioManager without
 * any read-back, and repaints are skipped when nothing actually changed.
 */
class VolumePanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val volume: VolumeController,
    private val onTerminate: () -> Unit
) {

    /** Inflated once, then re-attached on every open. */
    private var content: View? = null
    private var attached = false
    private var card: View? = null

    private var targetName: TextView? = null
    private var targetValue: TextView? = null

    private var seekMedia: SeekBar? = null
    private var seekRing: SeekBar? = null
    private var seekAlarm: SeekBar? = null
    private var seekSystem: SeekBar? = null

    private var valMedia: TextView? = null
    private var valRing: TextView? = null
    private var valAlarm: TextView? = null
    private var valSystem: TextView? = null

    private var rowMedia: View? = null
    private var rowRing: View? = null
    private var rowAlarm: View? = null
    private var rowSystem: View? = null

    /** Guards against feedback loops while we programmatically set progress. */
    private var updating = false

    /** Stream currently painted as selected, so we only repaint on change. */
    private var highlighted = Int.MIN_VALUE

    /** Throttle timestamp for slider writes (one AudioManager call per frame max). */
    private var lastWriteAt = 0L

    private val selectedColor by lazy { context.getColor(R.color.row_selected) }

    val isOpen: Boolean
        get() = attached

    /** The card view (used by the service to dock the bubble above it). */
    val cardView: View?
        get() = card

    @SuppressLint("ClickableViewAccessibility", "InflateParams", "SetTextI18n")
    fun show() {
        if (attached) {
            refresh()
            return
        }

        val view = content ?: buildContent()
        content = view

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OverlayWindows.type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(view, lp)
        } catch (e: Exception) {
            return
        }
        attached = true

        positionCard()
        // Re-position once the card has a real measured height.
        view.post { positionCard() }
        refresh()
    }

    fun hide() {
        if (!attached) return
        content?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Exception) {
                // Already detached.
            }
        }
        attached = false
    }

    /** Re-reads every stream from AudioManager and repaints the sliders. */
    fun refresh() {
        if (!attached) return

        val media = volume.current(AudioManager.STREAM_MUSIC)
        val ring = volume.current(AudioManager.STREAM_RING)
        val alarm = volume.current(AudioManager.STREAM_ALARM)
        val system = volume.current(AudioManager.STREAM_SYSTEM)

        updating = true
        seekMedia?.let {
            it.max = volume.max(AudioManager.STREAM_MUSIC)
            it.progress = media
        }
        seekRing?.let {
            it.max = volume.max(AudioManager.STREAM_RING)
            it.progress = ring
        }
        seekAlarm?.let {
            it.max = volume.max(AudioManager.STREAM_ALARM)
            it.progress = alarm
        }
        seekSystem?.let {
            it.max = volume.max(AudioManager.STREAM_SYSTEM)
            it.progress = system
        }
        updating = false

        setText(valMedia, media)
        setText(valRing, ring)
        setText(valAlarm, alarm)
        setText(valSystem, system)

        highlightTarget()
        updateTargetLabels()
    }

    private fun buildContent(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_panel, null)

        card = view.findViewById(R.id.panelCard)
        targetName = view.findViewById(R.id.targetName)
        targetValue = view.findViewById(R.id.targetValue)

        seekMedia = view.findViewById(R.id.seekMedia)
        seekRing = view.findViewById(R.id.seekRing)
        seekAlarm = view.findViewById(R.id.seekAlarm)
        seekSystem = view.findViewById(R.id.seekSystem)

        valMedia = view.findViewById(R.id.valMedia)
        valRing = view.findViewById(R.id.valRing)
        valAlarm = view.findViewById(R.id.valAlarm)
        valSystem = view.findViewById(R.id.valSystem)

        rowMedia = view.findViewById(R.id.rowMedia)
        rowRing = view.findViewById(R.id.rowRing)
        rowAlarm = view.findViewById(R.id.rowAlarm)
        rowSystem = view.findViewById(R.id.rowSystem)

        view.findViewById<View>(R.id.btnClose).setOnClickListener { hide() }

        // Red power button: terminates the whole tool (bubble + service).
        view.findViewById<View>(R.id.btnPower).setOnClickListener { onTerminate() }

        // Tap on the scrim (outside the card) closes the panel.
        view.setOnClickListener { hide() }
        card?.setOnClickListener { /* swallow taps inside the card */ }

        wireStepButtons(view)
        wireSeekBars()
        wireRowSelection()

        return view
    }

    private fun wireStepButtons(view: View) {
        view.findViewById<View>(R.id.btnPlus).setOnClickListener {
            volume.step(volume.targetStream, +1)
            refresh()
        }
        view.findViewById<View>(R.id.btnMinus).setOnClickListener {
            volume.step(volume.targetStream, -1)
            refresh()
        }
    }

    private fun wireSeekBars() {
        attachSeekBar(seekMedia, AudioManager.STREAM_MUSIC, valMedia)
        attachSeekBar(seekRing, AudioManager.STREAM_RING, valRing)
        attachSeekBar(seekAlarm, AudioManager.STREAM_ALARM, valAlarm)
        attachSeekBar(seekSystem, AudioManager.STREAM_SYSTEM, valSystem)
    }

    private fun attachSeekBar(bar: SeekBar?, stream: Int, label: TextView?) {
        bar ?: return
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updating || !fromUser) return

                // One-way write, throttled to roughly one call per frame: a
                // read-back here would double the binder traffic while dragging.
                val now = SystemClock.uptimeMillis()
                if (now - lastWriteAt >= 16L) {
                    lastWriteAt = now
                    volume.setFast(stream, progress)
                }
                setText(label, progress)
                if (volume.targetStream == stream) updateTargetLabels(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                volume.targetStream = stream
                highlightTarget()
                updateTargetLabels()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Commit the final value, then reconcile with what the system took.
                volume.setFast(stream, seekBar.progress)
                lastWriteAt = 0L
                val actual = volume.current(stream)
                if (actual != seekBar.progress) {
                    updating = true
                    seekBar.progress = actual
                    updating = false
                }
                setText(label, actual)
                if (volume.targetStream == stream) updateTargetLabels(actual)
            }
        })
    }

    private fun wireRowSelection() {
        rowMedia?.setOnClickListener { selectStream(AudioManager.STREAM_MUSIC) }
        rowRing?.setOnClickListener { selectStream(AudioManager.STREAM_RING) }
        rowAlarm?.setOnClickListener { selectStream(AudioManager.STREAM_ALARM) }
        rowSystem?.setOnClickListener { selectStream(AudioManager.STREAM_SYSTEM) }
    }

    private fun selectStream(stream: Int) {
        volume.targetStream = stream
        highlightTarget()
        updateTargetLabels()
    }

    private fun highlightTarget() {
        val stream = volume.targetStream
        if (stream == highlighted) return
        highlighted = stream

        rowMedia?.setBackgroundColor(if (stream == AudioManager.STREAM_MUSIC) selectedColor else 0)
        rowRing?.setBackgroundColor(if (stream == AudioManager.STREAM_RING) selectedColor else 0)
        rowAlarm?.setBackgroundColor(if (stream == AudioManager.STREAM_ALARM) selectedColor else 0)
        rowSystem?.setBackgroundColor(if (stream == AudioManager.STREAM_SYSTEM) selectedColor else 0)
    }

    private fun setText(view: TextView?, value: Int) {
        val text = value.toString()
        if (view != null && view.text.toString() != text) view.text = text
    }

    @SuppressLint("SetTextI18n")
    private fun updateTargetLabels(knownValue: Int = -1) {
        val stream = volume.targetStream
        targetName?.text = when (stream) {
            AudioManager.STREAM_MUSIC -> context.getString(R.string.stream_media)
            AudioManager.STREAM_RING -> context.getString(R.string.stream_ring)
            AudioManager.STREAM_ALARM -> context.getString(R.string.stream_alarm)
            else -> context.getString(R.string.stream_system)
        }
        val value = if (knownValue >= 0) knownValue else volume.current(stream)
        targetValue?.text = "$value / ${volume.max(stream)}"
    }

    /**
     * Places the card centered horizontally with its top at 20% of the screen
     * height; the bubble docks just above the card while the panel is open.
     */
    private fun positionCard() {
        val c = card ?: return
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val cardW = dp(272)
        val cardH = c.height.takeIf { it > 0 } ?: dp(280)
        val margin = dp(8)

        val x = ((screenW - cardW) / 2).coerceAtLeast(margin)
        val y = (screenH * 0.20f).toInt()
            .coerceIn(margin, (screenH - cardH - margin).coerceAtLeast(margin))

        val lp = c.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = cardW
        lp.leftMargin = x
        lp.topMargin = y
        lp.gravity = Gravity.TOP or Gravity.START
        c.layoutParams = lp
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
