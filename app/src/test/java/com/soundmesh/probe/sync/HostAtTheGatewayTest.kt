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

        assertNull(HostAtTheGateway.ask("127.0.0.1", port = 45331, timeoutMillis = 200))
    }

    @Test
    fun `a handset that takes the role straight back answers again`() {
        HostAtTheGateway.answer(hostId = "da3fe1c00de55dc6", chunkPort = 45124, port = 45332)
        HostAtTheGateway.stop()
        HostAtTheGateway.answer(hostId = "da3fe1c00de55dc6", chunkPort = 45124, port = 45332)

        // The sequence the timeline of 2026-09-22 is full of: role NONE and role HOST a second or
        // two apart, somebody changing their mind on the role screen. What it catches is answer()
        // swallowing a failed re-bind and leaving a host nobody can ask.
        //
        // What it does **not** catch, tried on 09-22: the join inside stop(). Removing that left
        // this green too - the port comes back before the next statement on this JVM either way.
        // Said here so the next person does not read this test as the reason that line is there.
        assertEquals(
            "da3fe1c00de55dc6",
            HostAtTheGateway.ask("127.0.0.1", port = 45332)?.hostId
        )
    }
}
