package com.soundmesh.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {
    @Test
    fun aHostPlayingItsOwnFileTakesTheFocus() {
        assertTrue(takesAudioFocus(host = true, capturing = false))
    }

    @Test
    fun aSinkTakesTheFocus() {
        assertTrue(takesAudioFocus(host = false, capturing = false))
    }

    /** The whole point: the app being captured has to keep playing, and the focus is what stops it. */
    @Test
    fun aCapturingHostLeavesTheFocusWithTheAppItIsCapturing() {
        assertFalse(takesAudioFocus(host = true, capturing = true))
    }
}
