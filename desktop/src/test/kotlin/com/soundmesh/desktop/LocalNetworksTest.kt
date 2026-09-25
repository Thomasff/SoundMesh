package com.soundmesh.desktop

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class LocalNetworksTest {
    private val wifi = OwnAddress("192.168.0.23", "WLAN", 24)
    private val tunnel = OwnAddress("10.8.0.2", "wg0", 24)

    @Test
    fun anAddressOnOneOfOurNetworksIsNotElsewhere() {
        assertFalse(LocalNetworks.elsewhere("192.168.0.150", listOf(tunnel, wifi)))
        assertFalse(LocalNetworks.elsewhere("10.8.0.1", listOf(tunnel, wifi)))
    }

    @Test
    fun anAddressOnNoneOfThemIsElsewhere() {
        assertTrue(LocalNetworks.elsewhere("192.168.1.150", listOf(tunnel, wifi)))
        // The top octet decides it too: a sign bit in the mask must not make these one network.
        assertTrue(LocalNetworks.elsewhere("200.168.0.23", listOf(OwnAddress("100.168.0.23", "x", 1))))
    }

    /**
     * A VirtualBox host-only adapter is a network between this machine and its virtual ones, and
     * no handset is on it: only the adapter the way out goes through is offered.
     */
    @Test
    fun onlyTheWayOutIsOffered() {
        val virtualBox = OwnAddress("192.168.56.1", "VirtualBox Host-Only Network", 24)
        assertEquals(listOf(wifi), LocalNetworks.offered(listOf(virtualBox, wifi), outward = "192.168.0.23"))
    }

    /** With no way out known, or one that is none of these, nothing is left out. */
    @Test
    fun withNoWayOutEveryAddressIsOffered() {
        assertEquals(listOf(tunnel, wifi), LocalNetworks.offered(listOf(tunnel, wifi), outward = null))
        assertEquals(listOf(tunnel, wifi), LocalNetworks.offered(listOf(tunnel, wifi), outward = "100.64.0.9"))
    }

    @Test
    fun whatIsNotEvidenceSaysNothing() {
        assertFalse(LocalNetworks.elsewhere("myhost", listOf(wifi)))
        assertFalse(LocalNetworks.elsewhere("192.168.1.300", listOf(wifi)))
        assertFalse(LocalNetworks.elsewhere("192.168.1.150", emptyList()))
        assertFalse(LocalNetworks.elsewhere("192.168.1.150", listOf(wifi.copy(prefixLength = 0))))
    }
}
