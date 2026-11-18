package com.floaty.volume

import android.os.Build
import android.view.WindowManager

/**
 * Picks the overlay window type that works on the running Android version.
 *
 * TYPE_APPLICATION_OVERLAY only exists from Android 9 (API 28); on Android 8.x
 * the equivalent is the older TYPE_PHONE window, which also requires the
 * SYSTEM_ALERT_WINDOW permission (already declared in the manifest).
 */
internal object OverlayWindows {

    @Suppress("DEPRECATION")
    fun type(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
