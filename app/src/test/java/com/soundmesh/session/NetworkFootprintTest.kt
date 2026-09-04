package com.soundmesh.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFootprintTest {
    private val wifi = NetworkFootprint(handle = 101L, addresses = setOf("192.168.1.20"))

    @Test
    fun aDifferentNetworkIsAMove() {
        assertTrue(wifi.movedTo(NetworkFootprint(handle = 202L, addresses = setOf("192.168.1.20"))))
    }

    @Test
    fun aDifferentAddressOnTheSameNetworkIsAMove() {
        assertTrue(wifi.movedTo(NetworkFootprint(handle = 101L, addresses = setOf("192.168.1.31"))))
    }

    /**
     * The system reports link properties more than once while a connection settles, IPv4 first and
     * IPv6 a moment later. Reading that as a move would have every session spend a discovery window
     * before its first connection, looking for a host that had not gone anywhere.
     */
    @Test
    fun gainingAnAddressIsNotAMove() {
        val settled = NetworkFootprint(handle = 101L, addresses = setOf("192.168.1.20", "fe80::1"))

        assertFalse(wifi.movedTo(settled))
    }

    /** The half of that which is a move: the address a peer was told about may be the one that went. */
    @Test
    fun losingOneOfTwoAddressesIsAMove() {
        val both = NetworkFootprint(handle = 101L, addresses = setOf("192.168.1.20", "fe80::1"))

        assertTrue(both.movedTo(wifi))
    }

    @Test
    fun standingStillIsNotAMove() {
        assertFalse(wifi.movedTo(wifi))
    }
}
