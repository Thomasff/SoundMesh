package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long the standing-by loop was away between one run and the next.
 *
 * The number the whole standby fault turns on. A loop that is running reports a gap of about a
 * second forever; a loop whose handset suspended under it reports the length of the suspend on
 * the first run after it wakes, because the clock behind this one keeps counting through sleep.
 * On 2026-09-14 the only thing separating "this phone is trying and failing" from "this phone is
 * not running at all" was a count of failed writes, and a count of one is ambiguous between them.
 */
class StandbyGapTest {
    private val gap = StandbyGap()

    @Test
    fun `the first run reports nothing, not the age of the handset`() {
        // The trap this class exists for: measured against zero, the first run of a phone that
        // booted four hours ago reports a four-hour gap on the screen.
        assertEquals(0L, gap.since(14_400_000L))
    }

    @Test
    fun `a loop that keeps running reports the tick it was written for`() {
        gap.since(10_000L)

        assertEquals(1_000L, gap.since(11_000L))
        assertEquals(1_000L, gap.since(12_000L))
    }

    @Test
    fun `a loop that was away reports how long it was away, on the run that comes back`() {
        gap.since(10_000L)
        gap.since(11_000L)

        assertEquals(62_000L, gap.since(73_000L))
    }

    @Test
    fun `and goes back to reporting the tick once it is running again`() {
        gap.since(10_000L)
        gap.since(72_000L)

        assertEquals(1_000L, gap.since(73_000L))
    }
}
