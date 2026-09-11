package com.soundmesh.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a room playing nothing is worth saying so about. */
class CaptureSilenceLineTest {
    @Test
    fun `a handset that is not capturing says nothing`() {
        assertFalse(capturesNothingWorthSaying(null))
    }

    /** The gap between two tracks is not a fault, and a screen that cries about it is noise. */
    @Test
    fun `a gap between two tracks is not worth saying`() {
        assertFalse(capturesNothingWorthSaying(0))
        assertFalse(capturesNothingWorthSaying(CAPTURE_SILENCE_SECONDS - 1))
    }

    @Test
    fun `silence that has gone on is worth saying`() {
        assertTrue(capturesNothingWorthSaying(CAPTURE_SILENCE_SECONDS))
        assertTrue(capturesNothingWorthSaying(30))
    }
}
