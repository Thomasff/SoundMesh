package com.soundmesh.core

/**
 * What a running session is doing, derived rather than remembered.
 *
 * The design's section 11.1 draws this as a state machine with transitions. It is written here as
 * a function of the conditions that hold right now instead, because every transition that machine
 * would need is already implied by them, and a machine additionally has to be told when to leave a
 * state. A session that was told it lost the link and then lost the audio focus, and is only told
 * about the focus coming back, resumes into a link that is still gone; there is no such reading
 * here, because there is nowhere to hold a stale answer.
 *
 * The order the conditions are asked in is the whole content of this type, so it is spelled out in
 * [of] rather than left to the reader of an enum's declaration order.
 */
enum class SessionState(
    /** Whether this state may put audio on the output. Exactly one may. */
    val mayEmit: Boolean,
    /**
     * Whether clock sync keeps exchanging in this state. Section 11.2 requires it through a
     * suspension: an interrupted session that also stopped its clock would come back needing the
     * estimator's whole window again - about sixteen seconds at the 2s cadence - and would spend
     * that silent, having stayed silent through the call already.
     */
    val keepsClockRunning: Boolean
) {
    /** Nothing has been started. */
    IDLE(mayEmit = false, keepsClockRunning = false),

    /** Started, clock exchanging, not yet emitting: there is no shared timeline to emit onto. */
    SYNCING(mayEmit = false, keepsClockRunning = true),

    /** On the shared timeline and emitting. */
    PLAYING(mayEmit = true, keepsClockRunning = true),

    /**
     * Emitting, but the clock no longer promises the band section 4.3 accepts.
     *
     * Section 11.2 asks for exactly this: say the sync has degraded and keep playing, because
     * slightly out of step beats silence. It emits for that reason and no other - the grading it
     * comes from is [ClockHealth].
     */
    DEGRADED(mayEmit = true, keepsClockRunning = true),

    /** Another app holds the audio focus. Silent by obligation, not by fault. */
    SUSPENDED(mayEmit = false, keepsClockRunning = true),

    /** The peer link is gone. Silent once whatever was already buffered has drained. */
    RECOVERING(mayEmit = false, keepsClockRunning = true),

    /** Finished. Terminal - nothing here reopens a stopped session. */
    STOPPED(mayEmit = false, keepsClockRunning = false);

    companion object {
        /**
         * The conditions, most decisive first.
         *
         * [SessionConditions.stopped] outranks everything because it is the one condition the user
         * set deliberately. Focus outranks the link because losing the focus is a prohibition on
         * emitting while losing the link is only an inability to: a session that reported the link
         * would resume the instant the link returned, on top of the call that took the focus.
         * Convergence is asked last of the four because it is the only one of them that a healthy
         * session passes through on its way up.
         *
         * [SessionConditions.clockUncertain] is asked after it and is the only condition that does
         * not silence anything: an unconverged clock has no timeline to emit onto, while an
         * uncertain one has a timeline it can no longer promise the accuracy of. Asking it last
         * also means a session with no estimate at all reports SYNCING rather than DEGRADED, which
         * is the difference between not knowing where the timeline is and knowing roughly.
         */
        fun of(conditions: SessionConditions): SessionState = when {
            conditions.stopped -> STOPPED
            !conditions.started -> IDLE
            !conditions.hasAudioFocus -> SUSPENDED
            !conditions.linkUp -> RECOVERING
            !conditions.clockConverged -> SYNCING
            conditions.clockUncertain -> DEGRADED
            else -> PLAYING
        }
    }
}

/**
 * The independent facts a session's state is read off, plus whether it has been started and
 * stopped.
 *
 * Kept as one value rather than a handful of parameters so that the reading and the thing read are
 * the same shape: a caller assembling these from live sources hands over one snapshot taken at one
 * instant, instead of several values sampled across the time it took to ask for them.
 */
data class SessionConditions(
    val started: Boolean,
    val stopped: Boolean,
    val hasAudioFocus: Boolean,
    val linkUp: Boolean,
    val clockConverged: Boolean,
    /**
     * The clock converged, but past what section 4.3 accepts - see [ClockHealth].
     *
     * Defaulted so that a caller with nothing to say about the clock's quality describes a session
     * that is not degraded, rather than one whose quality is unknown. There is no third answer
     * here: a session that cannot grade its clock has not converged it either.
     */
    val clockUncertain: Boolean = false
)
