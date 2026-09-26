package com.soundmesh.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMediaHintTest {
    @Test
    fun `a capturing host with media above one is told to turn it down`() {
        assertTrue(hearsCaptureTwice(HomeState(role = Role.HOST, capturing = true, mediaIndex = 2)))
    }

    @Test
    fun `a capturing host with media at one is not`() {
        assertFalse(hearsCaptureTwice(HomeState(role = Role.HOST, capturing = true, mediaIndex = 1)))
    }

    @Test
    fun `a host playing a file is not, whatever its media volume`() {
        assertFalse(hearsCaptureTwice(HomeState(role = Role.HOST, capturing = false, mediaIndex = 12)))
    }

    @Test
    fun `a sink is not - media is what it plays on`() {
        assertFalse(hearsCaptureTwice(HomeState(role = Role.SINK, mediaIndex = 12)))
    }

    @Test
    fun `nothing is said before the level has been read`() {
        assertFalse(hearsCaptureTwice(HomeState(role = Role.HOST, capturing = true, mediaIndex = null)))
    }
}
