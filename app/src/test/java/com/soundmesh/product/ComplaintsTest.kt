package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

class ComplaintsTest {
    /**
     * Two reasons failing together are two lines, not two a second.
     *
     * Kept as one slot until 2026-09-24, so the volume and the still-here, both refused by the same
     * dead line, took turns being "a new complaint" and each wrote itself every second. The
     * two-thousand-line timeline on X10 held seventeen minutes, and what had gone wrong before
     * that - the thing being asked about - had been pushed out of it.
     */
    @Test
    fun twoReasonsFailingTogetherAreWrittenOnceEach() {
        val written = mutableListOf<String>()
        val complaints = Complaints { written += it }
        repeat(10) {
            complaints.complain("volume not said", "the line is down")
            complaints.complain("still-here not said", "the line is down")
        }
        assertEquals(
            listOf("volume not said: the line is down", "still-here not said: the line is down"),
            written
        )
        assertEquals(20, complaints.missed)
    }

    @Test
    fun aNewReasonIsWrittenAndTheEndOfItToo() {
        val written = mutableListOf<String>()
        val complaints = Complaints { written += it }
        complaints.complain("volume not said", "the line is down")
        complaints.complain("volume not said", "refused")
        complaints.said("volume")
        assertEquals(
            listOf(
                "volume not said: the line is down",
                "volume not said: refused",
                "volume said again, after 2 that did not go out"
            ),
            written
        )
        assertEquals(0, complaints.missed)
        // Nothing was wrong, so nothing is over.
        complaints.said("still here")
        assertEquals(3, written.size)
    }
}
