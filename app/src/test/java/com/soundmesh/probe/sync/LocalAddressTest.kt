package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAddressTest {
    /** The X10 running the hotspot, with mobile data up behind it. */
    @Test
    fun ignoresTheMobileAddressThatReachesNothingInTheRoom() {
        val own = listOf("rmnet_data0" to "10.63.72.14", "wlan0" to "192.168.43.1")

        assertEquals("192.168.43.1", LocalAddress.choose(own))
    }

    /** A vendor build may call the hotspot interface something else. */
    @Test
    fun acceptsTheNamesTheseHandsetsActuallyUse() {
        assertEquals("192.168.43.1", LocalAddress.choose(listOf("swlan0" to "192.168.43.1")))
        assertEquals("192.168.49.1", LocalAddress.choose(listOf("p2p-wlan0-0" to "192.168.49.1")))
    }

    /**
     * The HONOR Pad V9 names its access point interface `softap0`, and when the tablet is the
     * host that is the only interface it has up. `ap` as a prefix matches `ap0` and misses this
     * one, so the tablet could not name itself and showed no code at all.
     */
    @Test
    fun acceptsTheTabletsAccessPointInterface() {
        assertEquals("10.214.89.253", LocalAddress.choose(listOf("softap0" to "10.214.89.253")))
    }

    /**
     * The same refusal discovery makes when two hosts answer. The wrong pick produces a code that
     * scans cleanly and then connects to nothing, with nothing in the run to say why.
     */
    @Test
    fun refusesToPickBetweenTwoAddressesAPeerMightReach() {
        val own = listOf("wlan0" to "192.168.1.20", "ap0" to "192.168.43.1")

        assertNull(LocalAddress.choose(own))
    }

    /** One address on two interfaces is still one address. */
    @Test
    fun isNotConfusedByOneAddressAppearingTwice() {
        val own = listOf("wlan0" to "192.168.43.1", "ap0" to "192.168.43.1")

        assertEquals("192.168.43.1", LocalAddress.choose(own))
    }

    @Test
    fun answersNothingWhenNothingIsUp() {
        assertNull(LocalAddress.choose(emptyList()))
        assertNull(LocalAddress.choose(listOf("rmnet_data0" to "10.63.72.14")))
    }
}
