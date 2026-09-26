package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeadMeterTest {
    private val ms = 1_000_000L

    @Test
    fun saysNothingUntilTheWindowHasGoneBy() {
        val meter = LeadMeter("sink-slack", windowNanos = 10_000 * ms)
        assertNull(meter.record(1_400 * ms, nowNanos = 0))
        assertNull(meter.record(1_450 * ms, nowNanos = 9_999 * ms))
    }

    /** Nearest-rank, as ChunkPlayout's band: every number printed is one that was recorded. */
    @Test
    fun aWindowIsOneLineOfItsSpread() {
        val meter = LeadMeter("sink-slack", windowNanos = 10_000 * ms)
        var line: String? = null
        for (i in 1..100) line = meter.record(i * ms, nowNanos = if (i == 100) 10_001 * ms else i * ms)
        assertEquals("sink-slack n=100 min 1 p1 1 p50 50 max 100 ms", line)
    }

    /** One stall is what the window is read for, so the next one starts clean rather than carrying it. */
    @Test
    fun theNextWindowStartsEmpty() {
        val meter = LeadMeter("sink-slack", windowNanos = 10_000 * ms)
        meter.record(80 * ms, nowNanos = 0)
        meter.record(1_500 * ms, nowNanos = 10_000 * ms)
        assertNull(meter.record(1_490 * ms, nowNanos = 10_001 * ms))
        assertEquals(
            "sink-slack n=2 min 1470 p1 1470 p50 1470 max 1490 ms",
            meter.record(1_470 * ms, nowNanos = 20_001 * ms)
        )
    }
}
