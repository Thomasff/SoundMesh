package com.soundmesh.probe.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one path that does not need the network to carry a multicast.
 *
 * Measured on 2026-09-22: a handset serving its own hotspot cannot be found by the handsets on it.
 * Eleven consecutive searches from a client answered nothing over two minutes, and the moment a
 * different client took the role it was found in one. Unicast to that handset has always worked -
 * it is the gateway, and the chunk stream has run over it since the first hotspot session - so
 * what is missing is not a route but a name to dial. This is that name, asked for rather than
 * listened for.
 */
class HostAtTheGatewayTest {

    @After
    fun tearDown() {
        HostAtTheGateway.stop()
    }

    @Test
    fun `a host answering says who it is and where this link reached it`() {
        HostAtTheGateway.answer(hostId = "da3fe1c00de55dc6", chunkPort = 45124, port = 45329)

        val code = HostAtTheGateway.ask("127.0.0.1", port = 45329)

        assertEquals("da3fe1c00de55dc6", code?.hostId)
        assertEquals(45124, code?.chunkPort)
        // Read off the accepted socket rather than off the handset's own list of addresses. The
        // asker already proved this one reaches it, and a handset serving an access point holds
        // several - the wrong one is a record naming an address nobody can open.
        assertEquals("127.0.0.1", code?.address)
    }

    @Test
    fun `nobody answering is not a host`() {
        assertNull(HostAtTheGateway.ask("127.0.0.1", port = 45330, timeoutMillis = 200))
    }

    @Test
    fun `a handset that gave the role back stops answering`() {
        HostAtTheGateway.answer(hostId = "da3fe1c00de55dc6", chunkPort = 45124, port = 45331)
        HostAtTheGateway.stop()

        // Deliberately after stop() has returned rather than after a sleep. close() hands the
        // descriptor to the thread sitting in accept, so "stop returned" and "the port is given
        // back" are two different instants, and a gate that waits out the difference with a clock
        // is a gate that passes on a fast machine.
        assertNull(HostAtTheGateway.ask("127.0.0.1", port = 45331, timeoutMillis = 200))
    }
}
