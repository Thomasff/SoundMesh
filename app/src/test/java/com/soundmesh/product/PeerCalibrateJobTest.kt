package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One button called "和配对的另一台手机对时" used to hide three unrelated jobs, two of which are
 * the most important measurements this product does. Nobody who had not read the source could
 * find them.
 */
class PeerCalibrateJobTest {
    @Test
    fun `each job comes back by name`() {
        assertEquals(PeerJob.PAIR, peerJobOf("PAIR"))
        assertEquals(PeerJob.ROOM, peerJobOf("ROOM"))
        assertEquals(PeerJob.OVERHEAD, peerJobOf("OVERHEAD"))
    }

    // Started with no extra is how ADB has always started this activity, and that has to keep
    // working: the pair measurement is the one it was driving.
    @Test
    fun `no extra is the pair measurement, which is how ADB starts it`() {
        assertEquals(PeerJob.PAIR, peerJobOf(null))
        assertEquals(PeerJob.PAIR, peerJobOf("nonsense"))
    }
}
