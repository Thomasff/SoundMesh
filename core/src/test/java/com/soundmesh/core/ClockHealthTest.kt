package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockHealthTest {
    /**
     * The worst single estimate in the whole archive - 186 runs, 15,603 estimates, none of which
     * was a link anybody complained about. If this reads anything but good, the warning fires on
     * ordinary operation and stops meaning anything.
     */
    @Test
    fun theWorstReadingAnyArchivedRunProducedIsStillGood() {
        assertEquals(ClockHealth.GOOD, ClockHealth.of(9_021_000L))
    }

    @Test
    fun theMedianReadingIsGood() {
        assertEquals(ClockHealth.GOOD, ClockHealth.of(2_534_000L))
    }

    /** Strictly above, so the acceptable bound itself is still acceptable. */
    @Test
    fun theAcceptableBoundItselfIsGood() {
        assertEquals(ClockHealth.GOOD, ClockHealth.of(ClockHealth.DEGRADED_ABOVE_NANOS))
    }

    @Test
    fun pastTheAcceptableBoundIsDegraded() {
        assertEquals(ClockHealth.DEGRADED, ClockHealth.of(ClockHealth.DEGRADED_ABOVE_NANOS + 1))
    }

    /** Still one sound to a listener, however badly placed. Section 4.2 puts the echo above this. */
    @Test
    fun theEchoThresholdItselfIsStillOnlyDegraded() {
        assertEquals(ClockHealth.DEGRADED, ClockHealth.of(ClockHealth.UNUSABLE_ABOVE_NANOS))
    }

    @Test
    fun pastTheEchoThresholdIsUnusable() {
        assertEquals(ClockHealth.UNUSABLE, ClockHealth.of(ClockHealth.UNUSABLE_ABOVE_NANOS + 1))
    }

    /** Swapped, every reading past the first bound would report unusable and the session go silent. */
    @Test
    fun theWarningBoundComesBeforeTheSilenceBound() {
        assertEquals(true, ClockHealth.DEGRADED_ABOVE_NANOS < ClockHealth.UNUSABLE_ABOVE_NANOS)
    }
}
