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

    @Test
    fun countsTheAcquisitionStillRunningRatherThanWaitingForItToConverge() {
        // A run that ends mid-reacquisition - which is how the pad ended both long sessions -
        // would otherwise report the acquisition as costing nothing at all.
        val total = acquiringTotalNanos(
            completedNanos = 0L, currentStartNanos = 1_000L, currentConvergedNanos = null, nowNanos = { 1_500L }
        )

        assertEquals(500L, total)
    }

    @Test
    fun countsNothingBeforeTheFirstChunkHasPinnedTheTimeline() {
        val total = acquiringTotalNanos(
            completedNanos = 0L, currentStartNanos = null, currentConvergedNanos = null, nowNanos = { 9_000L }
        )

        assertEquals(0L, total)
    }

    @Test
    fun doesNotCountAConvergedWindowASecondTime() {
        // The window was added to the running total the instant it converged, so counting it
        // again here would double every acquisition but the last.
        val total = acquiringTotalNanos(
            completedNanos = 500L, currentStartNanos = 1_000L, currentConvergedNanos = 1_500L, nowNanos = { 9_000L }
        )

        assertEquals(500L, total)
    }

    /**
     * The clock is read only when there is a running acquisition to measure against it.
     *
     * A sink has no host time until its first clock estimate succeeds, and asking for it before
     * then throws rather than inventing one. The report is polled by the home screen from the
     * moment the session leaves IDLE - seconds before any estimate exists - so an argument
     * evaluated whether or not this branch needs it takes the whole app down on the sink, every
     * time, on the main thread. It did: two crashes in seven seconds on the X10.
     *
     * Both branches that return early here are exactly the branches where no host time exists
     * yet, so a clock that is called rather than read is not a workaround - it is the only
     * arrangement in which this function asks for what it is entitled to.
     */
    @Test
    fun doesNotReadTheClockWhenThereIsNoRunningAcquisitionToMeasure() {
        val unavailable: () -> Long = { throw IllegalStateException("the clock was read") }

        assertEquals(
            0L,
            acquiringTotalNanos(
                completedNanos = 0L, currentStartNanos = null,
                currentConvergedNanos = null, nowNanos = unavailable
            )
        )
        assertEquals(
            500L,
            acquiringTotalNanos(
                completedNanos = 500L, currentStartNanos = 1_000L,
                currentConvergedNanos = 1_500L, nowNanos = unavailable
            )
        )
    }
}
