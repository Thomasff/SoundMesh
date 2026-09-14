package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The worst this handset has been since it started standing by, kept rather than shown live.
 *
 * Taken on 2026-09-14, from a reading that nearly did not survive being read: the handset was
 * woken to look at its own notification, the line came back inside a second, and the numbers on
 * it went back to saying everything was fine before the person holding it had finished pulling
 * the shade down. A live reading of a fault that heals on being observed is not a reading.
 */
class StandbyWorstTest {
    private val worst = StandbyWorst()

    @Test
    fun `a handset that has been well all along says so`() {
        worst.ran(1_000L)
        worst.network(present = true, now = 10_000L)

        assertEquals(1_000L, worst.longestGapMillis)
        assertEquals(0L, worst.longestAwayMillis)
    }

    @Test
    fun `the longest the loop was away is kept after it starts running again`() {
        worst.ran(1_000L)
        worst.ran(62_000L)
        worst.ran(1_000L)

        assertEquals(62_000L, worst.longestGapMillis)
    }

    @Test
    fun `an outage that ended is kept after the network comes back`() {
        worst.network(present = true, now = 10_000L)
        worst.network(present = false, now = 11_000L)
        worst.network(present = true, now = 53_000L)

        assertEquals(42_000L, worst.longestAwayMillis)
    }

    @Test
    fun `an outage still running counts now, rather than waiting to be over`() {
        // The one that matters on a handset somebody is holding: if this waited for the network
        // to come back, the number would be nought for exactly as long as the fault lasted.
        worst.network(present = true, now = 10_000L)
        worst.network(present = false, now = 11_000L)
        worst.network(present = false, now = 40_000L)

        assertEquals(29_000L, worst.longestAwayMillis)
    }

    @Test
    fun `the longest of several outages wins, not the last`() {
        worst.network(present = false, now = 0L)
        worst.network(present = true, now = 30_000L)
        worst.network(present = false, now = 40_000L)
        worst.network(present = true, now = 45_000L)

        assertEquals(30_000L, worst.longestAwayMillis)
    }

    @Test
    fun `a handset with no network from the start is away from the start`() {
        worst.network(present = false, now = 5_000L)
        worst.network(present = false, now = 25_000L)

        assertEquals(20_000L, worst.longestAwayMillis)
    }
}
