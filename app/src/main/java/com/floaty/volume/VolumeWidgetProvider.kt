package com.floaty.volume

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.widget.RemoteViews

/**
 * Home-screen widget: a full volume panel that lives in the launcher's
 * process, so it survives an app kill.
 *
 * RemoteViews cannot host interactive SeekBars, so each stream is a
 * ProgressBar display with − / + buttons (the same [VolumeController.step]
 * code path the bubble panel uses — they can never desync). The widget
 * refreshes on every volume change via the system VOLUME_CHANGED broadcast.
 *
 * Performance: no timers, no polling. It only redraws when a broadcast
 * arrives (a handful of RemoteViews updates), so idle cost is zero.
 */
class VolumeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, id))
        }
    }

    /**
     * Handles the widget's button taps. Each − / + button carries its own
     * stream + delta extras, so the target is fully described by the intent.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

        when (intent.action) {
            ACTION_STEP -> {
                val stream = intent.getIntExtra(EXTRA_STREAM, AudioManager.STREAM_MUSIC)
                val delta = intent.getIntExtra(EXTRA_DELTA, 0)
                if (delta != 0) {
                    VolumeController(context).step(stream, delta)
                    // Redraw right away; the system VOLUME_CHANGED broadcast
                    // also arrives, and repaints are cheap + idempotent.
                    if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
                }
            }
            ACTION_OPEN -> {
                // Bring the bubble back (start it if it is off). If the overlay
                // permission was revoked, offer to re-grant it first.
                if (!Settings.canDrawOverlays(context)) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                FloatyService.start(context)
            }
            ACTION_POWER -> {
                val prefs = Prefs(context)
                if (prefs.wantRunning) {
                    FloatyService.stop(context)
                    prefs.wantRunning = false
                } else if (Settings.canDrawOverlays(context)) {
                    FloatyService.start(context)
                    prefs.wantRunning = true
                }
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
            }
        }
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        AppWidgetManager.getInstance(context)
            .updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
    }

    /** Drop the cached PendingIntents when a widget instance is removed. */
    override fun onRemoved(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) piCache.remove(id)
    }

    companion object {
        const val ACTION_OPEN = "com.floaty.volume.widget.OPEN"
        const val ACTION_STEP = "com.floaty.volume.widget.STEP"
        const val ACTION_POWER = "com.floaty.volume.widget.POWER"
        const val EXTRA_STREAM = "stream"
        const val EXTRA_DELTA = "delta"

        private val streams = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_VOICE_CALL
        )

        private fun componentName(context: Context) =
            ComponentName(context, VolumeWidgetProvider::class.java)

        /**
         * Cached per placed widget: the 12 click PendingIntents never change
         * after first creation, so we build them once and reuse them on every
         * redraw. Creating them fresh on each volume change costs 12 binder
         * IPCs; caching removes that entirely.
         */
        private val piCache = HashMap<Int, Array<PendingIntent?>>()

        private fun pendingIntents(context: Context, id: Int): Array<PendingIntent?> {
            piCache[id]?.let { return it }
            val arr = arrayOfNulls<PendingIntent>(12)
            // 0: title -> open the full panel.
            arr[0] = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, VolumeWidgetProvider::class.java)
                    .setAction(ACTION_OPEN)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 1..10: − / + per stream, one PendingIntent each (shared intents
            // would carry identical extras, so we could not tell the button).
            var code = 11
            var idx = 1
            for (s in streams) {
                for (delta in intArrayOf(-1, 1)) {
                    arr[idx] = PendingIntent.getBroadcast(
                        context,
                        code,
                        Intent(context, VolumeWidgetProvider::class.java)
                            .setAction(ACTION_STEP)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .putExtra(EXTRA_STREAM, s)
                            .putExtra(EXTRA_DELTA, delta),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    idx++
                    code++
                }
            }
            // 11: power -> toggle the whole tool.
            arr[11] = PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, VolumeWidgetProvider::class.java)
                    .setAction(ACTION_POWER)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            piCache[id] = arr
            return arr
        }

        /** Maps a (stream, direction) pair to the matching − / + view id. */
        private fun streamButtonId(stream: Int, delta: Int): Int {
            val minus = when (stream) {
                AudioManager.STREAM_MUSIC -> R.id.wminusMedia
                AudioManager.STREAM_RING -> R.id.wminusRing
                AudioManager.STREAM_ALARM -> R.id.wminusAlarm
                AudioManager.STREAM_SYSTEM -> R.id.wminusSystem
                else -> R.id.wminusCall
            }
            val plus = when (stream) {
                AudioManager.STREAM_MUSIC -> R.id.wplusMedia
                AudioManager.STREAM_RING -> R.id.wplusRing
                AudioManager.STREAM_ALARM -> R.id.wplusAlarm
                AudioManager.STREAM_SYSTEM -> R.id.wplusSystem
                else -> R.id.wplusCall
            }
            return if (delta < 0) minus else plus
        }

        /** Builds the RemoteViews for [appWidgetId] (clicks cached, bars live). */
        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_volume)
            val pis = pendingIntents(context, appWidgetId)
            pis[0]?.let { views.setOnClickPendingIntent(R.id.wTitle, it) }
            var idx = 1
            for (s in streams) {
                pis[idx]?.let { views.setOnClickPendingIntent(streamButtonId(s, -1), it) }; idx++
                pis[idx]?.let { views.setOnClickPendingIntent(streamButtonId(s, +1), it) }; idx++
            }
            pis[11]?.let { views.setOnClickPendingIntent(R.id.wbtnPower, it) }
            paintStreams(views, VolumeController(context))
            return views
        }

        /** Sets all five progress bars from the current streams. */
        private fun paintStreams(views: RemoteViews, volume: VolumeController) {
            // Scale each bar to the stream's real maximum (7–15 on most phones,
            // not the 100 the layout declares) so the fill is accurate.
            views.setInt(R.id.wbarMedia, "setMax", volume.max(AudioManager.STREAM_MUSIC))
            views.setInt(R.id.wbarRing, "setMax", volume.max(AudioManager.STREAM_RING))
            views.setInt(R.id.wbarAlarm, "setMax", volume.max(AudioManager.STREAM_ALARM))
            views.setInt(R.id.wbarSystem, "setMax", volume.max(AudioManager.STREAM_SYSTEM))
            views.setInt(R.id.wbarCall, "setMax", volume.max(AudioManager.STREAM_VOICE_CALL))

            views.setInt(R.id.wbarMedia, "setProgress", volume.current(AudioManager.STREAM_MUSIC))
            views.setInt(R.id.wbarRing, "setProgress", volume.current(AudioManager.STREAM_RING))
            views.setInt(R.id.wbarAlarm, "setProgress", volume.current(AudioManager.STREAM_ALARM))
            views.setInt(R.id.wbarSystem, "setProgress", volume.current(AudioManager.STREAM_SYSTEM))
            views.setInt(R.id.wbarCall, "setProgress", volume.current(AudioManager.STREAM_VOICE_CALL))
        }

        /**
         * Redraws every placed widget. Called from the service when the
         * bubble panel changes a volume, so the widget and the panel always
         * show the same values.
         */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(componentName(context))
            if (ids.isEmpty()) return
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, id))
            }
        }
    }
}
