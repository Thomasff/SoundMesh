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
 * Whether an ordinary app may set it is a per-device question rather than a documented one, and it
 * has to be answered by moving the stream and reading it back. Asking whether the call throws is
 * not the same question and gets the wrong answer: on the X10 it throws nothing at all and changes
 * nothing at all. Sixteen calls went through during one drag -
 *
 *     AudioManager: setStreamVolume 10 index: 6 flags: 0
 *     ...
 *     AudioManager: setStreamVolume 10 index: 12 flags: 0
 *
 * - and the stream stayed on 4 throughout. The platform accepts the call from an app that is not an
 * accessibility service and silently drops it, so a slider driven by the no-throw answer looks live
 * and does nothing, which is the one outcome this class exists to avoid.
 */
class AccessibilityVolume(private val audio: AudioManager) {
    /**
     * Asked once, by moving the stream one step and putting it straight back.
     *
     * Once because [read] runs several times a minute while a capture is up, and this probe writes
     * - briefly, and to a system-wide setting. On a handset that ignores the write there is nothing
     * to put back; on one that honours it the level is restored in the same breath, before anything
     * is playing on that output. What it answers cannot change under a running app anyway.
     */
    private val settable: Boolean by lazy {
        val before = audio.getStreamVolume(STREAM)
        val max = audio.getStreamMaxVolume(STREAM)
        val probe = if (before < max) before + 1 else before - 1
        if (probe < 0 || probe > max) return@lazy false
        runCatching {
            try {
                set(probe)
                audio.getStreamVolume(STREAM) == probe
            } finally {
                set(before)
            }
        }.getOrDefault(false)
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
