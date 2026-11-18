package com.floaty.volume

import android.content.Context

/** Tiny SharedPreferences wrapper: bubble position + "should be running". */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("floaty_volume", Context.MODE_PRIVATE)

    var bubbleX: Int
        get() = sp.getInt(KEY_X, -1)
        set(value) = sp.edit().putInt(KEY_X, value).apply()

    var bubbleY: Int
        get() = sp.getInt(KEY_Y, -1)
        set(value) = sp.edit().putInt(KEY_Y, value).apply()

    /** True while the user wants the bubble up (used to restart after reboot). */
    var wantRunning: Boolean
        get() = sp.getBoolean(KEY_RUNNING, false)
        set(value) = sp.edit().putBoolean(KEY_RUNNING, value).apply()

    companion object {
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val KEY_RUNNING = "want_running"
    }
}
