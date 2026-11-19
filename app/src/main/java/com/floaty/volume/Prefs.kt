package com.floaty.volume

import android.content.Context

/**
 * Tiny SharedPreferences wrapper: bubble position + opacity, panel opacity
 * and "should be running".
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("floaty_volume", Context.MODE_PRIVATE)

    var bubbleX: Int
        get() = sp.getInt(KEY_X, -1)
        set(value) = sp.edit().putInt(KEY_X, value).apply()

    var bubbleY: Int
        get() = sp.getInt(KEY_Y, -1)
        set(value) = sp.edit().putInt(KEY_Y, value).apply()

    /** Bubble opacity in percent (0..100), 100 = fully opaque. */
    var bubbleOpacity: Int
        get() = sp.getInt(KEY_OPACITY, 100)
        set(value) = sp.edit().putInt(KEY_OPACITY, value.coerceIn(0, 100)).apply()

    /**
     * Panel background opacity in percent (0..100), 100 = fully opaque black.
     * Only the card background is affected; text, sliders and buttons stay
     * fully opaque.
     */
    var panelOpacity: Int
        get() = sp.getInt(KEY_PANEL_OPACITY, 80)
        set(value) = sp.edit().putInt(KEY_PANEL_OPACITY, value.coerceIn(0, 100)).apply()

    /** True while the user wants the bubble up (used to restart after reboot). */
    var wantRunning: Boolean
        get() = sp.getBoolean(KEY_RUNNING, false)
        set(value) = sp.edit().putBoolean(KEY_RUNNING, value).apply()

    companion object {
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val KEY_OPACITY = "bubble_opacity"
        private const val KEY_PANEL_OPACITY = "panel_opacity"
        private const val KEY_RUNNING = "want_running"
    }
}
