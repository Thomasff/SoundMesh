package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What [readableSsid] does with WifiManager's raw SSID read, which is the only part of the WiFi
 * name read that is pure enough to test without a device: whether this handset is on WiFi at all
 * is answered elsewhere, from ConnectivityManager - see [HomeActivity.readWifiName].
 */
class ReadableSsidTest {
    @Test
    fun `a real SSID comes back with its quotes stripped`() {
        assertEquals("MyNetwork", readableSsid("\"MyNetwork\""))
    }

    // Since Android 10 this is what WifiManager hands back to any app without
    // ACCESS_FINE_LOCATION and live location services - which is not a name, whatever it looks
    // like.
    @Test
    fun `the unknown-ssid placeholder is not a name`() {
        assertNull(readableSsid("<unknown ssid>"))
    }

    @Test
    fun `blank is not a name`() {
        assertNull(readableSsid(""))
        assertNull(readableSsid("   "))
    }

    @Test
    fun `null is not a name`() {
        assertNull(readableSsid(null))
    }

    // A non-UTF-8 SSID arrives as a bare hex string rather than a name, and is exactly as
    // unreadable as the placeholder.
    @Test
    fun `a hex-quoted SSID is not a name`() {
        assertNull(readableSsid("0x4E6574776F726B"))
    }
}
