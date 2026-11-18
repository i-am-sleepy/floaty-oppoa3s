package com.floaty.volume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager

/**
 * Foreground service that keeps the bubble (and the optional panel) alive while
 * the user is in other apps.
 *
 * Performance: the service holds no timers, no polling loops and no wake locks.
 * It only reacts to touch events, so idle cost is effectively zero. The panel
 * object is kept between opens so its view hierarchy is inflated once, and the
 * notification is built once and reused.
 */
class FloatyService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var volume: VolumeController

    private var bubble: BubbleOverlay? = null
    private var panel: VolumePanel? = null
    private var notification: Notification? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        volume = VolumeController(this)
        // Live opacity changes from the app screen (same-package broadcast).
        try {
            registerReceiver(opacityReceiver, IntentFilter(ACTION_OPACITY))
        } catch (ignored: Exception) {
            // Receiver registration is best-effort; the slider still persists.
        }
    }

    private val opacityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val op = intent?.getIntExtra(EXTRA_OPACITY, -1) ?: -1
            if (op in 0..100) bubble?.setOpacity(op)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground()
                if (Settings.canDrawOverlays(this)) {
                    prefs.wantRunning = true
                    showBubble()
                } else {
                    // Permission was revoked: nothing we can draw, stop cleanly.
                    stopEverything()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        // Build once; rebuilding on every start would be wasted work.
        val built = notification ?: buildNotification().also { notification = it }
        startForeground(NOTIFICATION_ID, built)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_volume_up)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_stop),
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun showBubble() {
        if (bubble?.isShowing == true) return

        val b = BubbleOverlay(
            context = this,
            windowManager = windowManager,
            prefs = prefs,
            onTap = { togglePanel() },
            onPositionChanged = { repositionPanel() }
        )
        b.show()
        b.setOpacity(prefs.bubbleOpacity)
        bubble = b
    }

    private fun togglePanel() {
        val current = panel
        if (current != null && current.isOpen) {
            current.hide()
            // Panel closed: clear the dock and send the bubble home.
            bubble?.openDock = null
            bubble?.restoreFromDock()
            return
        }
        // Reuse the existing panel object: its views are already inflated.
        val p = current ?: VolumePanel(
            context = this,
            windowManager = windowManager,
            volume = volume,
            onTerminate = { stopEverything() }
        ).also { panel = it }
        p.show()

        // Dock the bubble just above the card, centered like the card.
        bubble?.openDock = { center -> dockPoint(center) }
        // The panel scrim is added after the bubble window, so the bubble must
        // be re-inserted on top, then moved to its dock spot once the card
        // has a real laid-out position.
        p.cardView?.post {
            bubble?.bringToFront()
            bubble?.dockNow()
        }
    }

    /**
     * Dock point for the bubble while the panel is open: horizontally centered
     * (like the card) and just above the card's top edge.
     */
    private fun dockPoint(center: Point): Point {
        val card = panel?.cardView ?: return center
        val metrics = resources.displayMetrics
        val size = (40 * metrics.density).toInt()
        val gap = (8 * metrics.density).toInt()
        val x = ((metrics.widthPixels - size) / 2).coerceAtLeast(0)
        val y = (card.top - gap - size).coerceAtLeast(0)
        return Point(x, y)
    }

    private fun repositionPanel() {
        // The bubble moved: if the panel is still open (e.g. the bubble was
        // dragged while docked), close it and clear the dock so the bubble
        // behaves like a normal draggable bubble again.
        val open = panel ?: return
        if (open.isOpen) {
            open.hide()
            bubble?.openDock = null
        }
    }

    private fun stopEverything() {
        panel?.hide()
        panel = null
        bubble?.hide()
        bubble = null
        prefs.wantRunning = false
        stopForegroundCompat()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.reclamp()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(opacityReceiver)
        } catch (ignored: Exception) {
            // Not registered.
        }
        panel?.hide()
        panel = null
        bubble?.hide()
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.floaty.volume.action.START"
        const val ACTION_STOP = "com.floaty.volume.action.STOP"
        const val ACTION_OPACITY = "com.floaty.volume.action.OPACITY"
        const val EXTRA_OPACITY = "opacity"
        private const val CHANNEL_ID = "floaty_volume_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, FloatyService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatyService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (ignored: Exception) {
                // Service not running.
            }
        }
    }
}
