package com.soundmesh.session

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class LockScreenStateTest {
    @Test
    fun `a running session that is not paused is playing`() {
        assertEquals(
            PlaybackState.STATE_PLAYING,
            lockScreenState(paused = false, running = true)
        )
    }

    @Test
    fun `a running session that is paused is paused`() {
        assertEquals(
            PlaybackState.STATE_PAUSED,
            lockScreenState(paused = true, running = true)
        )
    }

    // A session that has ended is stopped whatever the pause flag last said. The flag outlives
    // the session, and a lock screen still offering to resume a room that is gone is a control
    // that does nothing.
    @Test
    fun `a session that is not running is stopped, whatever the pause flag says`() {
        assertEquals(
            PlaybackState.STATE_STOPPED,
            lockScreenState(paused = false, running = false)
        )
        assertEquals(
            PlaybackState.STATE_STOPPED,
            lockScreenState(paused = true, running = false)
        )
    }
}
