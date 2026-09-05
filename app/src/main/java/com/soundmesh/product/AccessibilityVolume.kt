package com.soundmesh.product

import android.media.AudioManager

/**
 * The accessibility output's own volume level, and whether this handset lets the app move it.
 */
data class OutputVolume(val level: Int, val max: Int, val settable: Boolean)

/**
 * The volume slider for the output a capturing host is actually heard on.
 *
 * A host that streams what this phone is playing cannot use the media output - the capture only
 * reads a stream whose media volume is at zero, and a media-usage playback is muted along with it -
 * so it plays on the accessibility output instead. That output has its own volume, and the system's
 * own controls cannot reach it: with a session playing, the volume keys move the media stream and
 * leave this one wherever it happened to be. A listener reported the host sounding quiet and found
 * by hand that the panel was moving the wrong stream. This is the only control that answers that.
 *
 * Whether an ordinary app may set it is a per-device question rather than a documented one, so
 * [read] finds out by asking for the level that is already set. That is a no-op when it is allowed
 * and throws when it is not, which is an answer that costs the user nothing either way - the
 * alternative is a slider that looks live and silently does nothing.
 */
class AccessibilityVolume(private val audio: AudioManager) {
    /**
     * Asked once. [read] runs several times a minute while a capture is up, and a no-op write that
     * often is still a write to a system-wide setting - which this has no business making a habit
     * of. What it answers cannot change under a running app anyway.
     */
    private val settable: Boolean by lazy {
        runCatching { set(audio.getStreamVolume(STREAM)) }.isSuccess
    }

    fun read(): OutputVolume =
        OutputVolume(audio.getStreamVolume(STREAM), audio.getStreamMaxVolume(STREAM), settable)

    /**
     * Moves the accessibility output's volume, or throws on a handset that reserves it.
     *
     * Without FLAG_SHOW_UI: the system's own volume panel would come up over the screen the person
     * is already dragging a slider on, showing a second control for the same thing.
     */
    fun set(level: Int) {
        audio.setStreamVolume(STREAM, level.coerceIn(0, audio.getStreamMaxVolume(STREAM)), 0)
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_ACCESSIBILITY
    }
}
