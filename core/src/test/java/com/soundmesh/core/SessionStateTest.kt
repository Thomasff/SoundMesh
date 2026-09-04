package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateTest {
    /** Everything a session needs before it may put a frame on the wire. */
    private val healthy = SessionConditions(
        started = true,
        stopped = false,
        hasAudioFocus = true,
        linkUp = true,
        clockConverged = true
    )

    @Test
    fun isIdleBeforeAnythingStarts() {
        assertEquals(SessionState.IDLE, SessionState.of(healthy.copy(started = false)))
    }

    @Test
    fun isStoppedOnceStoppedEvenWhileEverythingElseLooksHealthy() {
        assertEquals(SessionState.STOPPED, SessionState.of(healthy.copy(stopped = true)))
    }

    @Test
    fun isSyncingUntilTheClockConverges() {
        assertEquals(SessionState.SYNCING, SessionState.of(healthy.copy(clockConverged = false)))
    }

    @Test
    fun isRecoveringWhileTheLinkIsDown() {
        assertEquals(SessionState.RECOVERING, SessionState.of(healthy.copy(linkUp = false)))
    }

    @Test
    fun isSuspendedWhileAnotherAppHoldsTheFocus() {
        assertEquals(SessionState.SUSPENDED, SessionState.of(healthy.copy(hasAudioFocus = false)))
    }

    /**
     * A call drops the link and takes the focus at once. Reporting RECOVERING there would let the
     * session emit as soon as the link came back, over the top of the call still in progress.
     */
    @Test
    fun losingTheFocusOutranksLosingTheLink() {
        val both = healthy.copy(hasAudioFocus = false, linkUp = false)

        assertEquals(SessionState.SUSPENDED, SessionState.of(both))
    }

    @Test
    fun isPlayingOnceNothingIsWrong() {
        assertEquals(SessionState.PLAYING, SessionState.of(healthy))
    }

    /**
     * The state carries no memory of what it was before the interruption, so a session coming back
     * from SUSPENDED re-earns PLAYING from the conditions that hold now. A remembered state is how
     * a session resumes into a link that went away while it was not listening.
     */
    @Test
    fun regainingTheFocusWithADeadLinkReportsTheDeadLinkRatherThanPlaying() {
        val regained = healthy.copy(hasAudioFocus = true, linkUp = false)

        assertEquals(SessionState.RECOVERING, SessionState.of(regained))
    }

    /**
     * The one condition that does not silence anything. Section 11.2 asks for the sync to be
     * reported as degraded while playback continues, so this state has to emit or the requirement
     * is not implemented.
     */
    @Test
    fun isDegradedWhileTheClockConvergedPastWhatIsAccepted() {
        assertEquals(SessionState.DEGRADED, SessionState.of(healthy.copy(clockUncertain = true)))
    }

    /**
     * No estimate at all is not a bad estimate. Reading it as degraded would let a handset emit
     * onto a timeline it has never converged to.
     */
    @Test
    fun anUnconvergedClockOutranksAnUncertainOne() {
        val both = healthy.copy(clockConverged = false, clockUncertain = true)

        assertEquals(SessionState.SYNCING, SessionState.of(both))
    }

    /** A degraded clock says nothing about who owns the output. */
    @Test
    fun losingTheFocusOutranksADegradedClock() {
        val both = healthy.copy(hasAudioFocus = false, clockUncertain = true)

        assertEquals(SessionState.SUSPENDED, SessionState.of(both))
    }

    /** Absent means not degraded, so every reading taken before this existed still reads the same. */
    @Test
    fun aCallerThatSaysNothingAboutTheClockQualityDescribesAHealthyOne() {
        val silent = SessionConditions(
            started = true,
            stopped = false,
            hasAudioFocus = true,
            linkUp = true,
            clockConverged = true
        )

        assertEquals(SessionState.PLAYING, SessionState.of(silent))
    }

    @Test
    fun playingAndDegradedAreTheOnlyStatesThatMayEmit() {
        val emitting = SessionState.entries.filter { it.mayEmit }

        assertEquals(listOf(SessionState.PLAYING, SessionState.DEGRADED), emitting)
    }

    /**
     * Clock sync outlives every interruption short of stopping: the spec keeps it running through
     * a suspension precisely so the resumed session lands on the shared timeline rather than
     * spending its first seconds re-converging.
     */
    @Test
    fun everyStateButIdleAndStoppedKeepsTheClockRunning() {
        val notRunning = SessionState.entries.filterNot { it.keepsClockRunning }

        assertEquals(listOf(SessionState.IDLE, SessionState.STOPPED), notRunning)
    }
}
