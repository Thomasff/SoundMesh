package com.soundmesh.session

import com.soundmesh.core.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFlagsTest {
    @Test
    fun isIdleBeforeAnythingIsStarted() {
        assertEquals(SessionState.IDLE, SessionFlags().state())
    }

    /**
     * The focus is granted by the system after the session exists, so there is a window where a
     * started session holds no output. Defaulting the flag the other way would have it report
     * PLAYING across that window - a claim to an output another app may still own.
     */
    @Test
    fun aStartedSessionIsSuspendedUntilTheFocusIsGranted() {
        val flags = SessionFlags()

        flags.markStarted()

        assertEquals(SessionState.SUSPENDED, flags.state())
    }

    /**
     * A host clears neither the link nor convergence, so granting it the focus is the whole of what
     * it takes to reach PLAYING. This is the reading that says so.
     */
    @Test
    fun aStartedSessionWithTheFocusAndNothingClearedIsPlaying() {
        val flags = SessionFlags()

        flags.markStarted()
        flags.setAudioFocus(true)

        assertEquals(SessionState.PLAYING, flags.state())
    }

    @Test
    fun stoppingOutranksEverythingElse() {
        val flags = SessionFlags()
        flags.markStarted()
        flags.setAudioFocus(true)

        flags.markStopped()

        assertEquals(SessionState.STOPPED, flags.state())
    }
}
