package com.floaty.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Restarts the bubble after a reboot or an app update, if the user wanted it. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val prefs = Prefs(context)
        if (!prefs.wantRunning) return
        if (!Settings.canDrawOverlays(context)) return
        try {
            FloatyService.start(context)
        } catch (ignored: Exception) {
            // Background start restrictions: the user can reopen the app.
        }
    }
}
