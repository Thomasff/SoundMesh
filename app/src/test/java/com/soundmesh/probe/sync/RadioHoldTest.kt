package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release discipline and the reporting, which are the two things about a radio hold that can
 * be wrong without anything looking wrong.
 */
class RadioHoldTest {
    private class Fake(val failsToAcquire: Boolean = false) : RadioHold {
        var acquired = 0
        var released = 0
        override fun acquire() {
            if (failsToAcquire) throw SecurityException("no WAKE_LOCK")
            acquired++
        }
        override fun release() { released++ }
    }

    /**
     * A leaked hold keeps the radio out of power save for the life of the process, which is a
     * battery fault nothing in a run would show.
     */
    @Test
    fun theHoldIsGivenBackEvenWhenTheRunThrows() {
        val hold = Fake()
        val thrown = runCatching {
            holdingRadio(hold, held = {}) { throw IllegalStateException("the run failed") }
        }

        assertTrue(thrown.isFailure)
        assertEquals(1, hold.acquired)
        assertEquals(1, hold.released)
    }

    @Test
    fun theHoldIsGivenBackWhenTheRunReturns() {
        val hold = Fake()

        assertEquals("answer", holdingRadio(hold, held = {}) { "answer" })
        assertEquals(1, hold.released)
    }

    /**
     * acquire needs WAKE_LOCK and is best effort. A hold that quietly did nothing would make a run
     * read as evidence that power save does not matter, when the arm under test never ran - so
     * which arm ran is reported, not assumed.
     */
    @Test
    fun aHoldThatCouldNotBeTakenSaysSoAndTheRunStillHappens() {
        val hold = Fake(failsToAcquire = true)
        var reported: Boolean? = null

        val answer = holdingRadio(hold, held = { reported = it }) { "answer" }

        assertEquals("answer", answer)
        assertFalse("a hold that threw was reported as taken", reported!!)
        assertEquals("released a hold it never took", 0, hold.released)
    }

    /** A handset with no WiFi service at all still runs the calibration, and still says so. */
    @Test
    fun withNoHoldAtAllTheRunStillHappensAndSaysItWasNotHeld() {
        var reported: Boolean? = null

        assertEquals("answer", holdingRadio(null, held = { reported = it }) { "answer" })
        assertFalse(reported!!)
    }
}
