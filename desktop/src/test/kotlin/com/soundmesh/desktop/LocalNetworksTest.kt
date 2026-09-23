package com.soundmesh.desktop

import org.junit.Test
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

    @Test
    fun whatIsNotEvidenceSaysNothing() {
        assertFalse(LocalNetworks.elsewhere("myhost", listOf(wifi)))
        assertFalse(LocalNetworks.elsewhere("192.168.1.300", listOf(wifi)))
        assertFalse(LocalNetworks.elsewhere("192.168.1.150", emptyList()))
        assertFalse(LocalNetworks.elsewhere("192.168.1.150", listOf(wifi.copy(prefixLength = 0))))
    }
}
