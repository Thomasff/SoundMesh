package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun playingIsTheOnlyStateThatMayEmit() {
        val emitting = SessionState.entries.filter { it.mayEmit }

        assertEquals(listOf(SessionState.PLAYING), emitting)
    }

    /**
     * Clock sync outlives every interruption short of stopping: the spec keeps it running through
     * a suspension precisely so the resumed session lands on the shared timeline rather than
     * spending its first seconds re-converging.
     */
    @Test
    fun everyStateButIdleAndStoppedKeepsTheClockRunning() {
        assertFalse(SessionState.IDLE.keepsClockRunning)
        assertFalse(SessionState.STOPPED.keepsClockRunning)
        assertTrue(SessionState.SYNCING.keepsClockRunning)
        assertTrue(SessionState.RECOVERING.keepsClockRunning)
        assertTrue(SessionState.SUSPENDED.keepsClockRunning)
        assertTrue(SessionState.PLAYING.keepsClockRunning)
    }
}
