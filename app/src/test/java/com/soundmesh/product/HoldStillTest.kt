package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long somebody has to keep still, which until now the screen answered with "about a minute".
 *
 * A round is the one part of this app where a person is asked to do nothing and stay quiet. The
 * cost of not knowing how long is not impatience: it is somebody moving at second fifty of a
 * fifty-five second window, and a run that fails for a reason nothing records.
 */
class HoldStillTest {
    @Test
    fun countsTheWholeSecondsLeft() {
        assertEquals(12, secondsLeft(now = 1_000L, until = 13_000L))
    }

    /**
     * Rounded up, so the last second is shown as one rather than as zero.
     *
     * Zero on screen while the round is still running is the screen saying it is over when it is
     * not, and the person it is talking to is the one who then moves.
     */
    @Test
    fun roundsAPartSecondUpToAWholeOne() {
        assertEquals(13, secondsLeft(now = 1_000L, until = 13_001L))
        assertEquals(1, secondsLeft(now = 1_000L, until = 1_001L))
    }

    @Test
    fun saysNothingIsLeftOnceTheMomentHasPassed() {
        assertEquals(0, secondsLeft(now = 13_000L, until = 13_000L))
        assertEquals(0, secondsLeft(now = 20_000L, until = 13_000L))
    }
}
