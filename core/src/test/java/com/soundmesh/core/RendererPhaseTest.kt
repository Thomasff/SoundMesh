package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererPhaseTest {
    /** A sample the drift controller placed inside its deadband: converging, and never wide. */
    private fun settled(state: PhaseState) = nextPhaseState(state, inDeadband = true, filteredErrorFrames = 0)

    /**
     * A sample outside the deadband but nowhere near the reacquire threshold - 60 frames is past
     * DriftController's 48-frame deadband and far under 192.
     */
    private fun offBy60(state: PhaseState) = nextPhaseState(state, inDeadband = false, filteredErrorFrames = -60)

    /** One of the measured excursions that voided a run: -903 frames against a 960-frame chunk. */
    private fun wide(state: PhaseState) = nextPhaseState(state, inDeadband = false, filteredErrorFrames = -903)

    @Test
    fun staysAcquiringOnASingleInDeadbandSample() {
        val next = settled(PhaseState.INITIAL)

        assertEquals(RendererPhase.ACQUIRING, next.phase)
        assertEquals(1, next.consecutiveInDeadband)
    }

    @Test
    fun switchesToTrackingOnceTheStreakReachesTheRequiredCount() {
        var state = PhaseState.INITIAL
        repeat(4) { state = settled(state) }
        assertEquals(RendererPhase.ACQUIRING, state.phase)

        state = settled(state)

        assertEquals(RendererPhase.TRACKING, state.phase)
        assertEquals(5, state.consecutiveInDeadband)
    }

    @Test
    fun resetsTheStreakWhenASampleLeavesTheDeadband() {
        var state = PhaseState.INITIAL
        repeat(4) { state = settled(state) }

        state = offBy60(state)

        assertEquals(RendererPhase.ACQUIRING, state.phase)
        assertEquals(0, state.consecutiveInDeadband)
    }

    @Test
    fun aBrokenStreakNeedsAFreshFullRunToConverge() {
        var state = PhaseState.INITIAL
        repeat(4) { state = settled(state) }
        state = offBy60(state)

        repeat(4) { state = settled(state) }

        assertEquals(RendererPhase.ACQUIRING, state.phase)
    }

    @Test
    fun trackingSurvivesASingleWideExcursion() {
        var state = PhaseState.INITIAL
        repeat(5) { state = settled(state) }
        assertEquals(RendererPhase.TRACKING, state.phase)

        state = wide(state)

        assertEquals(RendererPhase.TRACKING, state.phase)
        assertEquals(1, state.consecutiveBeyondThreshold)
    }

    @Test
    fun fallsBackToAcquiringOnTwoConsecutiveWideExcursions() {
        var state = PhaseState.INITIAL
        repeat(5) { state = settled(state) }

        state = wide(state)
        state = wide(state)

        assertEquals(RendererPhase.ACQUIRING, state.phase)
        assertEquals(0, state.consecutiveBeyondThreshold)
    }

    @Test
    fun trackingHoldsThroughAnExcursionOutsideTheDeadbandButUnderTheThreshold() {
        var state = PhaseState.INITIAL
        repeat(5) { state = settled(state) }

        repeat(4) { state = offBy60(state) }

        assertEquals(RendererPhase.TRACKING, state.phase)
        assertEquals(0, state.consecutiveBeyondThreshold)
    }

    @Test
    fun anInDeadbandSampleResetsTheWideStreak() {
        var state = PhaseState.INITIAL
        repeat(5) { state = settled(state) }

        state = wide(state)
        state = settled(state)
        assertEquals(0, state.consecutiveBeyondThreshold)
        state = wide(state)

        assertEquals(RendererPhase.TRACKING, state.phase)
    }

    @Test
    fun reacquisitionNeedsAFreshFullDeadbandRunBeforeTrackingReturns() {
        var state = PhaseState.INITIAL
        repeat(5) { state = settled(state) }
        state = wide(state)
        state = wide(state)
        assertEquals(RendererPhase.ACQUIRING, state.phase)

        state = settled(state)
        assertEquals(RendererPhase.ACQUIRING, state.phase)
        repeat(3) { state = settled(state) }
        assertEquals(RendererPhase.ACQUIRING, state.phase)

        state = settled(state)

        assertEquals(RendererPhase.TRACKING, state.phase)
    }

    @Test
    fun honoursACustomRequiredConsecutiveCount() {
        var state = PhaseState.INITIAL
        state = nextPhaseState(state, inDeadband = true, filteredErrorFrames = 0, requiredConsecutive = 2)
        assertEquals(RendererPhase.ACQUIRING, state.phase)

        state = nextPhaseState(state, inDeadband = true, filteredErrorFrames = 0, requiredConsecutive = 2)

        assertEquals(RendererPhase.TRACKING, state.phase)
    }

    @Test
    fun honoursACustomReacquireThreshold() {
        var state = PhaseState.INITIAL
        repeat(5) { state = settled(state) }

        // 60 frames is under the default threshold, so only the override can trip this.
        repeat(2) {
            state = nextPhaseState(state, inDeadband = false, filteredErrorFrames = -60, reacquireThresholdFrames = 50)
        }

        assertEquals(RendererPhase.ACQUIRING, state.phase)
    }

    @Test
    fun theReacquireThresholdSitsWellAboveTheDeadbandAndWellUnderAChunk() {
        assertEquals(4 * DriftController.DEFAULT_DEADBAND_FRAMES, REACQUIRE_THRESHOLD_FRAMES)
        // A chunk is 960 frames; the threshold has to catch slips smaller than a whole one.
        assertTrue(REACQUIRE_THRESHOLD_FRAMES < 960 / 2)
    }

    @Test
    fun acquiringSamplesOncePerChunkAndTrackingAtTheSlowerCadence() {
        val chunkNanos = 20_000_000L
        val trackingNanos = 1_000_000_000L

        val acquiringInterval = driftIntervalNanos(RendererPhase.ACQUIRING, chunkNanos, trackingNanos)
        val trackingInterval = driftIntervalNanos(RendererPhase.TRACKING, chunkNanos, trackingNanos)

        assertEquals(chunkNanos, acquiringInterval)
        assertEquals(trackingNanos, trackingInterval)
        // The direction is the whole point of the split: inverted, one frame of correction per
        // second puts convergence of a one-chunk release-phase error back at ~16 minutes.
        assertTrue(acquiringInterval < trackingInterval)
    }
}
