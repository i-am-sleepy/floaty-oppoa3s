package com.floaty.volume

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * Thin, null-safe wrapper around AudioManager.
 *
 * All four streams the panel exposes are real system streams, so changes are
 * exactly what the hardware volume keys would do (and are reflected in the
 * system volume UI / other apps).
 */
class VolumeController(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Streams shown in the panel, in display order. */
    val streams: IntArray = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_SYSTEM
    )

    fun max(stream: Int): Int = audioManager.getStreamMaxVolume(stream)

    fun min(stream: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(stream)
        } else {
            0
        }

    fun current(stream: Int): Int = audioManager.getStreamVolume(stream)

    /** Stream that the +/- buttons act on by default. */
    var targetStream: Int = AudioManager.STREAM_MUSIC

    /**
     * Sets an absolute index on [stream], clamped to the device range.
     * Uses flag 0 so the system volume overlay is not shown on top of our panel.
     *
     * @return true when AudioManager actually accepted the value.
     */
    fun set(stream: Int, index: Int): Boolean {
        val lo = min(stream)
        val hi = max(stream)
        if (hi <= lo) return false
        val value = index.coerceIn(lo, hi)
        return try {
            audioManager.setStreamVolume(stream, value, 0)
            // Some ROMs (incl. ColorOS with Do-Not-Disturb) silently ignore the call.
            if (audioManager.getStreamVolume(stream) == value) {
                true
            } else {
                audioManager.adjustStreamVolume(
                    stream,
                    if (value > audioManager.getStreamVolume(stream)) {
                        AudioManager.ADJUST_RAISE
                    } else {
                        AudioManager.ADJUST_LOWER
                    },
                    0
                )
                audioManager.getStreamVolume(stream) == value
            }
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** Moves [stream] by [delta] steps. */
    fun step(stream: Int, delta: Int): Boolean = set(stream, current(stream) + delta)

    fun isMuted(stream: Int): Boolean = current(stream) <= min(stream)
}
