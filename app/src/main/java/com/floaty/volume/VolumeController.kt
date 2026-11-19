package com.floaty.volume

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.SparseIntArray

/**
 * Thin, null-safe wrapper around AudioManager.
 *
 * Performance notes (this matters on a Snapdragon 450):
 * every AudioManager getter is a binder IPC call to audioserver, so the panel
 * must never poll them in a loop. Stream max/min values are cached (they do not
 * change for a given device) and slider dragging uses [setRobust], which
 * performs one verified write per throttled frame with a relative-adjust
 * fallback for ROMs that ignore absolute writes.
 */
class VolumeController(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Streams shown in the panel, in display order. */
    val streams: IntArray = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_VOICE_CALL
    )

    // Cached because getStreamMaxVolume/getStreamMinVolume are IPC and constant
    // for a device. Static (per-process) so a fresh instance — e.g. the widget
    // creating one per redraw — reuses the values instead of re-reading them.
    private val maxCache = Companion.maxCache
    private val minCache = Companion.minCache

    fun max(stream: Int): Int {
        val cached = maxCache.get(stream, -1)
        if (cached >= 0) return cached
        val value = try {
            audioManager.getStreamMaxVolume(stream)
        } catch (e: Exception) {
            15
        }
        maxCache.put(stream, value)
        return value
    }

    fun min(stream: Int): Int {
        val cached = minCache.get(stream, -1)
        if (cached >= 0) return cached
        val value = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(stream)
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
        minCache.put(stream, value)
        return value
    }

    fun current(stream: Int): Int = try {
        audioManager.getStreamVolume(stream)
    } catch (e: Exception) {
        0
    }

    /** Stream that the +/- buttons act on by default. */
    var targetStream: Int = AudioManager.STREAM_MUSIC

    /**
     * Robust absolute set used while a slider is being dragged.
     *
     * Tries a single [setStreamVolume] write, verifies it stuck, and if the ROM
     * silently rejected it (ColorOS DND, or a guarded "default" stream) falls
     * back to a relative [adjustStreamVolume]. Returns the value the system
     * actually settled on so the caller can reconcile the UI. The read-back is
     * one binder call and only happens on the (throttled) drag frames.
     */
    fun setRobust(stream: Int, index: Int): Int {
        val lo = min(stream)
        val hi = max(stream)
        if (hi <= lo) return current(stream)
        val value = index.coerceIn(lo, hi)
        return try {
            audioManager.setStreamVolume(stream, value, 0)
            val actual = audioManager.getStreamVolume(stream)
            if (actual == value) {
                actual
            } else {
                audioManager.adjustStreamVolume(
                    stream,
                    if (value > actual) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0
                )
                audioManager.getStreamVolume(stream)
            }
        } catch (e: SecurityException) {
            current(stream)
        } catch (e: IllegalArgumentException) {
            current(stream)
        }
    }

    /**
     * Sets an absolute index on [stream] and verifies the system accepted it.
     * Used for discrete +/- taps, where one extra read-back is cheap.
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
            val actual = audioManager.getStreamVolume(stream)
            if (actual == value) {
                true
            } else {
                // Some ROMs (incl. ColorOS with Do-Not-Disturb) silently ignore
                // setStreamVolume; fall back to a relative adjust.
                audioManager.adjustStreamVolume(
                    stream,
                    if (value > actual) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
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

    companion object {
        /** Device-constant stream bounds, shared by every instance. */
        private val maxCache = SparseIntArray(4)
        private val minCache = SparseIntArray(4)
    }
}
