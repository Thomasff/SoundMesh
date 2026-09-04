package com.soundmesh.product

import com.soundmesh.core.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StateWordingTest {
    /**
     * The failure this exists for: somebody adds a state and the screen quietly shows whatever the
     * fallback says. DEGRADED was added a day ago and would have landed exactly there.
     */
    @Test
    fun everyStateHasItsOwnWording() {
        val words = SessionState.entries.map { StateWording.of(it) }
        assertEquals(SessionState.entries.size, words.toSet().size)
        for (word in words) assertNotEquals(0, word)
    }

    @Test
    fun noSessionReadsAsIdleRatherThanBlank() {
        assertNotEquals(0, StateWording.of(null))
    }

    /** Seven of them, and the ladder is the platform's rather than ours. */
    @Test
    fun everyThermalStatusHasItsOwnWording() {
        val words = (0..6).map { StateWording.thermal(it) }
        assertEquals(7, words.toSet().size)
        for (word in words) assertNotEquals(0, word)
    }

    /** A vendor build that invents an eighth must not blank the row. */
    @Test
    fun anUnknownThermalStatusStillSaysSomething() {
        assertNotEquals(0, StateWording.thermal(99))
    }
}
