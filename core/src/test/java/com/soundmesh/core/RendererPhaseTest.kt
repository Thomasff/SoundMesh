package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererPhaseTest {
    @Test
    fun staysAcquiringOnASingleInDeadbandSample() {
        val next = nextPhaseState(PhaseState.INITIAL, inDeadband = true)

        assertEquals(RendererPhase.ACQUIRING, next.phase)
        assertEquals(1, next.consecutiveInDeadband)
    }

    @Test
    fun switchesToTrackingOnceTheStreakReachesTheRequiredCount() {
        var state = PhaseState.INITIAL
        repeat(4) { state = nextPhaseState(state, inDeadband = true) }
        assertEquals(RendererPhase.ACQUIRING, state.phase)

        state = nextPhaseState(state, inDeadband = true)

        assertEquals(RendererPhase.TRACKING, state.phase)
        assertEquals(5, state.consecutiveInDeadband)
    }

    @Test
    fun resetsTheStreakWhenASampleLeavesTheDeadband() {
        var state = PhaseState.INITIAL
        repeat(4) { state = nextPhaseState(state, inDeadband = true) }

        state = nextPhaseState(state, inDeadband = false)

        assertEquals(RendererPhase.ACQUIRING, state.phase)
        assertEquals(0, state.consecutiveInDeadband)
    }

    @Test
    fun aBrokenStreakNeedsAFreshFullRunToConverge() {
        var state = PhaseState.INITIAL
        repeat(4) { state = nextPhaseState(state, inDeadband = true) }
        state = nextPhaseState(state, inDeadband = false)

        repeat(4) { state = nextPhaseState(state, inDeadband = true) }

        assertEquals(RendererPhase.ACQUIRING, state.phase)
    }

    @Test
    fun trackingIsStickyDespiteALaterWideExcursion() {
        var state = PhaseState.INITIAL
        repeat(5) { state = nextPhaseState(state, inDeadband = true) }
        assertEquals(RendererPhase.TRACKING, state.phase)

        state = nextPhaseState(state, inDeadband = false)

        assertEquals(RendererPhase.TRACKING, state.phase)
    }

    @Test
    fun honoursACustomRequiredConsecutiveCount() {
        var state = PhaseState.INITIAL
        state = nextPhaseState(state, inDeadband = true, requiredConsecutive = 2)
        assertEquals(RendererPhase.ACQUIRING, state.phase)

        state = nextPhaseState(state, inDeadband = true, requiredConsecutive = 2)

        assertEquals(RendererPhase.TRACKING, state.phase)
    }
}
