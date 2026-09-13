package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The time service also answers a question nobody was asking it: who is still here.
 *
 * A sink asks for the time every two seconds for the whole of a session, over UDP, which has no
 * connection to go on claiming anything. Writing down when each request arrived costs one map
 * entry per handset and is the only signal on this host that a phone leaving the network actually
 * moves - see [PeerSilence] for why the TCP channels cannot.
 */
class ClockSyncServerTest {
    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    private fun ask(port: Int, bytes: ByteArray) {
        DatagramSocket().use {
            it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port))
        }
    }

    private fun waitForAnAnswer(server: ClockSyncServer): Map<String, Long> {
        val giveUpAt = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < giveUpAt) {
            val heard = server.heardFrom()
            if (heard.isNotEmpty()) return heard
            Thread.sleep(10)
        }
        return server.heardFrom()
    }

    @Test
    fun remembersTheAddressEveryClockRequestCameFrom() {
        val port = freePort()
        val server = ClockSyncServer(port)
        server.start()
        try {
            val before = System.currentTimeMillis()

            ask(port, ClockPacket.encodeRequest(1, 1_000L))

            val heard = waitForAnAnswer(server)
            assertEquals(setOf("127.0.0.1"), heard.keys)
            assertTrue("heard at ${heard["127.0.0.1"]}, asked at $before", heard["127.0.0.1"]!! >= before)
        } finally {
            server.stop()
        }
    }

    /**
     * Anything that is not a clock request leaves no trace, so a stray packet on this port cannot
     * make a handset that has gone look like one that is still asking.
     */
    @Test
    fun countsOnlyPacketsThatAreActuallyClockRequests() {
        val port = freePort()
        val server = ClockSyncServer(port)
        server.start()
        try {
            ask(port, ByteArray(7))
            Thread.sleep(200)

            assertEquals(emptyMap<String, Long>(), server.heardFrom())
        } finally {
            server.stop()
        }
    }
}
