package com.floaty.volume

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.media.AudioManager
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
 * The window is a full-screen scrim; the card sits next to the bubble and any
 * touch outside the card closes the panel.
 */
class VolumePanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val volume: VolumeController,
    private val anchor: () -> Point
) {

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var card: View? = null

    private var title: TextView? = null
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

    val isOpen: Boolean
        get() = root != null

    @SuppressLint("ClickableViewAccessibility", "InflateParams", "SetTextI18n")
    fun show() {
        if (root != null) {
            refresh()
            return
        }

        val content = LayoutInflater.from(context).inflate(R.layout.overlay_panel, null)
        bindViews(content)

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

        // Tap on the scrim (outside the card) closes the panel.
        content.setOnClickListener { hide() }
        card?.setOnClickListener { /* swallow taps inside the card */ }

        // root must be set before the listeners that look it up.
        root = content
        params = lp

        wireStepButtons()
        wireSeekBars()
        wireRowSelection()

        try {
            windowManager.addView(content, lp)
        } catch (e: Exception) {
            root = null
            params = null
            return
        }

        positionCard()
        // Re-position once the card has a real measured height.
        content.post { positionCard() }
        refresh()
    }

    fun hide() {
        val v = root ?: return
        try {
            windowManager.removeView(v)
        } catch (ignored: Exception) {
            // Already detached.
        }
        root = null
        params = null
    }

    /** Re-reads every stream from AudioManager and repaints the sliders. */
    fun refresh() {
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

        valMedia?.text = media.toString()
        valRing?.text = ring.toString()
        valAlarm?.text = alarm.toString()
        valSystem?.text = system.toString()

        highlightTarget()
        updateTargetLabels()
    }

    private fun bindViews(content: View) {
        card = content.findViewById(R.id.panelCard)
        title = content.findViewById(R.id.panelTitle)
        targetName = content.findViewById(R.id.targetName)
        targetValue = content.findViewById(R.id.targetValue)

        seekMedia = content.findViewById(R.id.seekMedia)
        seekRing = content.findViewById(R.id.seekRing)
        seekAlarm = content.findViewById(R.id.seekAlarm)
        seekSystem = content.findViewById(R.id.seekSystem)

        valMedia = content.findViewById(R.id.valMedia)
        valRing = content.findViewById(R.id.valRing)
        valAlarm = content.findViewById(R.id.valAlarm)
        valSystem = content.findViewById(R.id.valSystem)

        rowMedia = content.findViewById(R.id.rowMedia)
        rowRing = content.findViewById(R.id.rowRing)
        rowAlarm = content.findViewById(R.id.rowAlarm)
        rowSystem = content.findViewById(R.id.rowSystem)

        content.findViewById<View>(R.id.btnClose).setOnClickListener { hide() }
    }

    private fun wireStepButtons() {
        root?.findViewById<View>(R.id.btnPlus)?.setOnClickListener {
            volume.step(volume.targetStream, +1)
            refresh()
        }
        root?.findViewById<View>(R.id.btnMinus)?.setOnClickListener {
            volume.step(volume.targetStream, -1)
            refresh()
        }
    }

    private fun wireSeekBars() {
        attachSeekBar(seekMedia, AudioManager.STREAM_MUSIC) { valMedia?.text = it.toString() }
        attachSeekBar(seekRing, AudioManager.STREAM_RING) { valRing?.text = it.toString() }
        attachSeekBar(seekAlarm, AudioManager.STREAM_ALARM) { valAlarm?.text = it.toString() }
        attachSeekBar(seekSystem, AudioManager.STREAM_SYSTEM) { valSystem?.text = it.toString() }
    }

    private fun attachSeekBar(bar: SeekBar?, stream: Int, onValue: (Int) -> Unit) {
        bar ?: return
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updating || !fromUser) return
                volume.set(stream, progress)
                val actual = volume.current(stream)
                onValue(actual)
                if (volume.targetStream == stream) updateTargetLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                volume.targetStream = stream
                highlightTarget()
                updateTargetLabels()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Keep the slider in sync with what the system really accepted.
                if (!updating) {
                    updating = true
                    seekBar.progress = volume.current(stream)
                    updating = false
                }
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
        val selected = context.getColor(R.color.row_selected)
        val normal = 0
        rowMedia?.setBackgroundColor(if (volume.targetStream == AudioManager.STREAM_MUSIC) selected else normal)
        rowRing?.setBackgroundColor(if (volume.targetStream == AudioManager.STREAM_RING) selected else normal)
        rowAlarm?.setBackgroundColor(if (volume.targetStream == AudioManager.STREAM_ALARM) selected else normal)
        rowSystem?.setBackgroundColor(if (volume.targetStream == AudioManager.STREAM_SYSTEM) selected else normal)
        title?.text = context.getString(R.string.panel_title)
    }

    @SuppressLint("SetTextI18n")
    private fun updateTargetLabels() {
        val stream = volume.targetStream
        targetName?.text = when (stream) {
            AudioManager.STREAM_MUSIC -> context.getString(R.string.stream_media)
            AudioManager.STREAM_RING -> context.getString(R.string.stream_ring)
            AudioManager.STREAM_ALARM -> context.getString(R.string.stream_alarm)
            else -> context.getString(R.string.stream_system)
        }
        targetValue?.text = "${volume.current(stream)} / ${volume.max(stream)}"
    }

    /** Places the card next to the bubble, always fully on screen. */
    private fun positionCard() {
        val c = card ?: return
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val cardW = dp(300)
        val cardH = c.height.takeIf { it > 0 } ?: dp(430)
        val margin = dp(8)

        val bubble = anchor()
        var x = bubble.x + dp(56) + margin
        if (x + cardW > screenW) x = bubble.x - cardW - margin
        x = x.coerceIn(margin, (screenW - cardW - margin).coerceAtLeast(margin))

        var y = bubble.y - cardH / 3
        y = y.coerceIn(margin, (screenH - cardH - margin).coerceAtLeast(margin))

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
