package com.soundmesh.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBudgetTest {
    @Test
    fun anOrdinarySongFits() {
        assertFalse(SourceBudget.tooLong(minutes(3.5)))
    }

    @Test
    fun theLimitItselfFits() {
        assertFalse(SourceBudget.tooLong(SourceBudget.MAX_WHOLE_SECONDS * 1_000_000L))
    }

    @Test
    fun aSecondPastTheLimitDoesNot() {
        assertFalse(SourceBudget.tooLong(SourceBudget.MAX_WHOLE_SECONDS * 1_000_000L))
        assertTrue(SourceBudget.tooLong((SourceBudget.MAX_WHOLE_SECONDS + 1) * 1_000_000L))
    }

    /**
     * MediaFormat's duration is microseconds and nothing in the name says so. Read as
     * milliseconds the limit becomes seven seconds, which refuses everything; read as nanoseconds
     * it becomes five days, which refuses nothing. Both failures are silent.
     */
    @Test
    fun theDurationIsReadAsMicroseconds() {
        assertFalse(SourceBudget.tooLong(minutes(1.0)))
        assertTrue(SourceBudget.tooLong(minutes(20.0)))
    }

    /** Not every container declares one. An unknown length is not a refusal - it is a truncation. */
    @Test
    fun anUndeclaredLengthIsNotARefusal() {
        assertFalse(SourceBudget.tooLong(null))
        assertFalse(SourceBudget.tooLong(0L))
        assertFalse(SourceBudget.tooLong(-1L))
    }

    private fun minutes(count: Double): Long = (count * 60_000_000L).toLong()
}
