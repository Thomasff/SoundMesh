package com.soundmesh.probe.sync

import android.media.AudioManager

/** What one attempt did, so the record says which of these happened rather than "tried". */
enum class NudgeOutcome { PUSHED, NOT_SILENCED, WOULD_NOT_MOVE }

/**
 * Pushes the media stream off zero and straight back, to make the handset look at its audio path
 * again.
 *
 * This is a listener's own repair, automated. Since 09-12 the same three moves have worked every
 * time the room went silent under a capture: nudge the media volume up a step, nudge it back, the
 * sound returns. Plugging in a cable does it too, which is the same thing by another route - both
 * make the handset re-evaluate where audio is going. Leaving media on one was also found to stop
 * it happening at all, and is not what this does: that costs the host's own speaker playing the
 * song a second and a half ahead of the room, all the time, to avoid something that happens rarely.
 *
 * **What it is not is a diagnosis.** Why a stream sitting at zero should ever hand the capture
 * exact zeros while the player is still running is not settled - 09-20 established only that the
 * player *is* still running, because the song jumps forward by the length of the silence rather
 * than resuming where it stopped. A repair that works is worth having before the reason is known,
 * as long as it is written down as that and not as an explanation.
 *
 * Harmless in the case it cannot distinguish. Somebody pausing their own music produces the same
 * silence and the same record, and there is no cheap reading that separates the two - so this does
 * not try. If the player is paused, nothing is flowing, and 0 to 1 to 0 makes no sound at all. If
 * the player is running, a quarter of a second of the song leaks out of the host's own speaker at
 * one step of fifteen. That asymmetry is the whole reason this can be done without knowing which
 * case it is in.
 *
 * Only from zero. A handset whose media volume is not zero was put there by a person, and a step
 * they chose is not this class's to move.
 */
class SilenceNudge(
    private val streams: StreamVolumes,
    private val cooldownNanos: Long = COOLDOWN_SECONDS * 1_000_000_000L,
) {
    private var lastNanos: Long? = null

    /**
     * Whether an attempt may start at [nowNanos], and if so the cooldown starts now.
     *
     * Asked on every chunk of silence past [CaptureSilence.QUIET_NANOS], on the capture's own loop,
     * so it is a comparison and nothing more; the push itself is on a thread of its own. Claimed
     * here rather than inside [push], because by the time that thread ran, the next chunks would
     * have started threads of their own. Stamped whatever the push then does: a throw or a stream
     * that would not move must not leave the next chunk free to try again at once - a repair that
     * fires fifty times a second is a fault of its own.
     */
    @Synchronized
    fun claim(nowNanos: Long): Boolean {
        val last = lastNanos
        if (last != null && nowNanos - last < cooldownNanos) return false
        lastNanos = nowNanos
        return true
    }

    /**
     * [hold] is how long the stream stays off zero, passed in rather than slept here: this runs on
     * a thread of the caller's choosing and a test should not take a quarter of a second. Only
     * after [claim] said yes.
     */
    fun push(hold: () -> Unit): NudgeOutcome {
        if (streams.level(AudioManager.STREAM_MUSIC) != 0) return NudgeOutcome.NOT_SILENCED
        streams.set(AudioManager.STREAM_MUSIC, 1)
        // Read back, because on this project's own handsets setStreamVolume has taken a value,
        // thrown nothing and moved nothing. A push that did not move is not a push that failed to
        // help - it is a push that never happened, and the two want different next steps.
        val moved = streams.level(AudioManager.STREAM_MUSIC) != 0
        hold()
        streams.set(AudioManager.STREAM_MUSIC, 0)
        return if (moved) NudgeOutcome.PUSHED else NudgeOutcome.WOULD_NOT_MOVE
    }

    companion object {
        /**
         * How long after one attempt the next may start, while the silence goes on or when a new
         * one starts straight after. Chosen by the listener on 09-25 along with the 0.7 s: a
         * repair that did not work is retried every five seconds rather than on every chunk.
         */
        const val COOLDOWN_SECONDS = 5

        /**
         * How long the stream stays at one step.
         *
         * A capture chunk is 20 ms, so this is a dozen of them - enough that the path has been
         * asked for real audio and answered, rather than two volume writes in the same millisecond
         * that the handset is free to collapse into nothing. Short enough to read as a blip.
         */
        const val HOLD_MILLIS = 250L
    }
}
