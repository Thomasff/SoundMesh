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

    /**
     * Which network an address is on cannot be read off its interface name.
     *
     * The temptation is `wlan` means joined and `ap` means hotspot, and the very first test in
     * this file is the counterexample: the X10 runs its access point on `wlan0`. So the question
     * is asked of ConnectivityManager instead - it names the IPv4 of the network it calls WiFi,
     * and whatever wireless address is not that one is this handset's own access point.
     */
    @Test
    fun tellsTheHotspotFromTheNetworkThisHandsetJoined() {
        val own = listOf("wlan0" to "192.168.1.20", "ap0" to "192.168.43.1")

        assertEquals(
            listOf(
                LocalAddress.Reachable("192.168.1.20", LocalAddress.ReachedBy.WIFI),
                LocalAddress.Reachable("192.168.43.1", LocalAddress.ReachedBy.HOTSPOT)
            ),
            LocalAddress.reachable(own, joinedAddress = "192.168.1.20")
        )
    }

    /** The X10 case again, read the other way: nothing joined, so the one address is its own AP. */
    @Test
    fun callsTheOnlyAddressAHotspotWhenThisHandsetJoinedNothing() {
        assertEquals(
            listOf(LocalAddress.Reachable("192.168.43.1", LocalAddress.ReachedBy.HOTSPOT)),
            LocalAddress.reachable(listOf("wlan0" to "192.168.43.1"), joinedAddress = null)
        )
    }

    /**
     * Both up at once, which Android 11 and these handsets allow, and the code can only name one.
     *
     * This is the case that showed no code at all until 2026-09-15: two candidates was a refusal,
     * and a person with a hotspot running for some phones and WiFi for the rest had no way to say
     * which. The refusal was right - the app cannot know which network the phone doing the
     * scanning is on - so what changed is that somebody can now answer it.
     */
    @Test
    fun letsThePersonSayWhichNetworkTheCodeIsFor() {
        val own = listOf("wlan0" to "192.168.1.20", "ap0" to "192.168.43.1")

        assertEquals(
            "192.168.43.1",
            LocalAddress.choose(own, joinedAddress = "192.168.1.20", prefer = LocalAddress.ReachedBy.HOTSPOT)
        )
        assertEquals(
            "192.168.1.20",
            LocalAddress.choose(own, joinedAddress = "192.168.1.20", prefer = LocalAddress.ReachedBy.WIFI)
        )
    }

    /** And with only one address up, what somebody asked for does not make it disappear. */
    @Test
    fun answersTheOneAddressThereIsWhicheverWasAskedFor() {
        val own = listOf("wlan0" to "192.168.43.1")

        assertEquals(
            "192.168.43.1",
            LocalAddress.choose(own, joinedAddress = null, prefer = LocalAddress.ReachedBy.WIFI)
        )
    }

    /**
     * Two addresses and no way to tell them apart is still a refusal.
     *
     * ConnectivityManager not naming a joined address makes both of them look like this handset's
     * own access point, and a phone cannot be its own access point twice. Answering either would
     * be the guess this whole file exists to avoid.
     */
    @Test
    fun refusesWhenTwoAddressesCannotBeToldApart() {
        val own = listOf("wlan0" to "192.168.1.20", "ap0" to "192.168.43.1")

        assertNull(
            LocalAddress.choose(own, joinedAddress = null, prefer = LocalAddress.ReachedBy.HOTSPOT)
        )
    }
}
