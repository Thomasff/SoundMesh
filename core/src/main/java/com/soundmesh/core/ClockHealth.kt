package com.soundmesh.core

/**
 * How much of a clock estimate's uncertainty a session can still play through.
 *
 * The design's section 11.2 asks for two thresholds rather than one: past the first the session
 * says its sync has degraded and **keeps playing**, because slightly out of step beats silence;
 * only past the second does it stop. This is where those two numbers live, and what they are
 * derived from.
 *
 * The quantity graded is [ClockEstimate.uncertaintyNanos] - half the shortest round trip in the
 * window. It is a bound on the asymmetry the midpoint cannot cancel, not the error itself: the
 * runs measured at 2-3 ms of it aligned to within 0.7 ms. Grading a bound is deliberate. It is the
 * only figure available while a session is running, since the error itself is only knowable by
 * putting a microphone in the room.
 */
enum class ClockHealth {
    /** Within what the link normally does. Nothing to say. */
    GOOD,

    /** Past what section 4.3 accepts, still one sound to a listener. Say so, keep playing. */
    DEGRADED,

    /** Past where two handsets are heard as an echo. An echo is worse than silence. */
    UNUSABLE;

    companion object {
        /**
         * Section 4.3's acceptable alignment bound, 10 ms.
         *
         * Above this the clock alone can no longer promise the band the design accepts, so the
         * session stops claiming it is in sync - while continuing to play, because 5-30 ms still
         * merges into one sound by section 4.2.
         *
         * The archive says this will not fire on an ordinary link: across 186 archived runs and
         * 15,603 estimates the uncertainty ran 1.44 to 9.02 ms, median 2.53, 99th percentile 3.80.
         * The whole observed range sits under this bound, which is the property a warning has to
         * have to be worth anything. Re-derive it if the link changes - a threshold justified by a
         * distribution is only as current as the distribution.
         */
        const val DEGRADED_ABOVE_NANOS = 10_000_000L

        /**
         * Section 4.2's echo threshold, 40 ms.
         *
         * Past it two handsets are no longer heard as one badly placed sound but as a sound and
         * its echo, and "slightly out of step beats silence" stops being true of it.
         */
        const val UNUSABLE_ABOVE_NANOS = 40_000_000L

        fun of(uncertaintyNanos: Long): ClockHealth = when {
            uncertaintyNanos > UNUSABLE_ABOVE_NANOS -> UNUSABLE
            uncertaintyNanos > DEGRADED_ABOVE_NANOS -> DEGRADED
            else -> GOOD
        }
    }
}
