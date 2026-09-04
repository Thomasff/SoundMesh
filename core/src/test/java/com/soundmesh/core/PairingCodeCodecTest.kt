package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingCodeCodecTest {
    private val code = PairingCode(hostId = "0123456789abcdef", address = "192.168.43.1", chunkPort = 45124)

    @Test
    fun survivesTheRoundTrip() {
        assertEquals(code, PairingCodeCodec.decode(PairingCodeCodec.encode(code)))
    }

    @Test
    fun carriesTheProtocolVersionSoAScannedCodeIsCheckedLikeAFoundOne() {
        val fields = PairingCodeCodec.encode(code).split(" ")

        assertEquals(PairingCodeCodec.MAGIC, fields[0])
        assertEquals(PeerAdvertisement.PROTOCOL_VERSION, fields[1])
    }

    /**
     * Scanning a code from a build that cannot be talked to must fail at the scan. There is no
     * mDNS record around this path to carry the check that discovery does.
     */
    @Test
    fun refusesACodeFromAnotherProtocolVersion() {
        val stale = "${PairingCodeCodec.MAGIC} 1 0123456789abcdef 192.168.43.1 45124"

        assertThrows(IllegalArgumentException::class.java) { PairingCodeCodec.decode(stale) }
    }

    @Test
    fun refusesSomethingThatIsNotAPairingCodeAtAll() {
        assertThrows(IllegalArgumentException::class.java) { PairingCodeCodec.decode("https://example.com") }
        assertThrows(IllegalArgumentException::class.java) { PairingCodeCodec.decode("") }
    }

    /**
     * The scanned id names a file. A code offering a path segment instead of a name must be
     * refused where it is read, not where it lands.
     */
    @Test
    fun refusesAHostIdThatCouldNameSomethingElse() {
        val hostile = "${PairingCodeCodec.MAGIC} ${PeerAdvertisement.PROTOCOL_VERSION} ../../secret 192.168.43.1 45124"

        assertThrows(IllegalArgumentException::class.java) { PairingCodeCodec.decode(hostile) }
    }

    @Test
    fun refusesAPortNoSocketCouldOpen() {
        listOf("0", "65536", "-1", "chunk").forEach { port ->
            val text = "${PairingCodeCodec.MAGIC} ${PeerAdvertisement.PROTOCOL_VERSION} 0123456789abcdef 192.168.43.1 $port"
            assertThrows("port $port must be refused", IllegalArgumentException::class.java) {
                PairingCodeCodec.decode(text)
            }
        }
    }

    @Test
    fun refusesACodeMissingAField() {
        val short = "${PairingCodeCodec.MAGIC} ${PeerAdvertisement.PROTOCOL_VERSION} 0123456789abcdef 192.168.43.1"

        assertThrows(IllegalArgumentException::class.java) { PairingCodeCodec.decode(short) }
    }
}
