package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a handset that cannot reach its host can work out without reaching it.
 *
 * The whole point is that none of this may send a packet: the handset running it is the one that
 * cannot get through. Everything below is two addresses and a prefix length.
 */
class JoinTroubleTest {
    private val mine = IpSubnet("192.168.1.24", 24)

    @Test
    fun `nothing paired yet has nothing to diagnose`() {
        assertNull(joinTrouble(onWifi = true, mine = mine, hostAddress = null))
        assertNull(joinTrouble(onWifi = true, mine = mine, hostAddress = "  "))
    }

    // Checked before the addresses, because a handset on mobile data still has an address and it
    // would compare as a different network - the right answer for the wrong reason, and the wrong
    // sentence: "connect to WiFi" is the action, not "you are on another network".
    @Test
    fun `off WiFi is its own answer, whatever addresses are around`() {
        assertEquals(
            JoinTrouble.NO_WIFI,
            joinTrouble(onWifi = false, mine = mine, hostAddress = "192.168.1.9")
        )
        assertEquals(
            JoinTrouble.NO_WIFI,
            joinTrouble(onWifi = false, mine = null, hostAddress = "192.168.1.9")
        )
    }

    @Test
    fun `the host on this network means the network is not the reason`() {
        assertEquals(
            JoinTrouble.CANNOT_REACH,
            joinTrouble(onWifi = true, mine = mine, hostAddress = "192.168.1.9")
        )
    }

    // A guest network, a router that puts 2.4 and 5 GHz on separate segments, two different WiFis:
    // all of them look like this, and none of them needs a packet to be seen.
    @Test
    fun `the host on another network is told apart from the host being unreachable`() {
        assertEquals(
            JoinTrouble.OTHER_NETWORK,
            joinTrouble(onWifi = true, mine = mine, hostAddress = "192.168.2.9")
        )
        assertEquals(
            JoinTrouble.OTHER_NETWORK,
            joinTrouble(onWifi = true, mine = mine, hostAddress = "10.0.0.9")
        )
    }

    // The prefix is the whole of the difference between the two answers, so it has to be read and
    // not assumed: the same pair of addresses is one network on a /16 and two on a /24. A home
    // router hands out /24, a campus or office network often does not.
    @Test
    fun `the prefix decides, not the first three numbers`() {
        assertEquals(
            JoinTrouble.CANNOT_REACH,
            joinTrouble(onWifi = true, mine = IpSubnet("192.168.1.24", 16), hostAddress = "192.168.2.9")
        )
        assertEquals(
            JoinTrouble.OTHER_NETWORK,
            joinTrouble(onWifi = true, mine = IpSubnet("192.168.1.24", 25), hostAddress = "192.168.1.200")
        )
    }

    // Saying nothing beats saying something wrong: each of these would make the screen accuse a
    // network that may be perfectly fine, and the generic line underneath is still true.
    @Test
    fun `anything unreadable answers nothing at all`() {
        assertNull(joinTrouble(onWifi = true, mine = null, hostAddress = "192.168.1.9"))
        assertNull(joinTrouble(onWifi = true, mine = mine, hostAddress = "soundmesh.local"))
        assertNull(joinTrouble(onWifi = true, mine = mine, hostAddress = "192.168.1"))
        assertNull(joinTrouble(onWifi = true, mine = mine, hostAddress = "192.168.1.999"))
        assertNull(joinTrouble(onWifi = true, mine = mine, hostAddress = "fe80::1"))
        assertNull(joinTrouble(onWifi = true, mine = IpSubnet("192.168.1.24", 0), hostAddress = "10.0.0.9"))
        assertNull(joinTrouble(onWifi = true, mine = IpSubnet("192.168.1.24", 33), hostAddress = "10.0.0.9"))
    }

    // The top bit of an address is the one a signed 32-bit comparison gets wrong, and the addresses
    // that carry it are the ones a hotspot hands out on some vendors' phones.
    @Test
    fun `addresses above 127 compare the same way as the ones below`() {
        assertEquals(
            JoinTrouble.CANNOT_REACH,
            joinTrouble(onWifi = true, mine = IpSubnet("192.168.43.24", 24), hostAddress = "192.168.43.1")
        )
        assertEquals(
            JoinTrouble.OTHER_NETWORK,
            joinTrouble(onWifi = true, mine = IpSubnet("192.168.43.24", 24), hostAddress = "172.20.10.1")
        )
    }

    // A handset serving its own access point. Every sentence here is written from inside a
    // phone that joined somebody else's network, and all three are wrong from inside the phone
    // that *is* the network: "连上主机的热点" when the host is already on this one, "不在同一个
    // 网" read off the joined subnet while the host sits on the access point's, which nothing
    // here can see, and "让主机开热点" to the phone holding the hotspot. Found 09-21, when a
    // handset serving the hotspot was put in as a sink and the room worked.
    @Test
    fun `the phone that is the network is not told to go and find one`() {
        assertNull(
            joinTrouble(
                onWifi = false,
                mine = null,
                hostAddress = "192.168.43.9",
                ownAccessPoint = true
            )
        )
        assertNull(
            joinTrouble(
                onWifi = true,
                mine = mine,
                hostAddress = "192.168.43.9",
                ownAccessPoint = true
            )
        )
    }

    @Test
    fun `every answer has words to go with it`() {
        for (trouble in JoinTrouble.entries) joinWording(trouble)
    }
}
