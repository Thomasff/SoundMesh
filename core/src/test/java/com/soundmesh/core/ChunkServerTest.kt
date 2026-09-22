package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class ChunkServerTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun chunk(sequence: Int) = AudioChunk(sequence, 1_000L * sequence, ByteArray(FRAME_BYTES))

    @Test
    fun aSinkReceivesTheChunksItWasSent() {
        val port = freePort()
        val server = ChunkServer(port)
        server.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                announce(socket, PEER)
                waitForClients(server, 1)
                server.broadcast(chunk(7))
                val reader = FrameReader(socket.getInputStream())

                assertEquals(7, reader.readChunk()?.sequence)
            }
        } finally {
            server.stop()
        }
    }

    /**
     * The defect this queue exists for, measured on hardware at 26.9 seconds in one call.
     *
     * A handset that leaves the network rather than the session takes its socket with it without
     * closing it, and a write to that socket does not fail - the send buffer fills and the write
     * blocks for as long as the kernel keeps retransmitting. Broadcasting on the caller's thread
     * stalled whoever was producing the chunks, and on a host that thread also feeds its own
     * output: one sink walking out of the room silenced the room.
     *
     * A socket nobody reads from is the same condition, reached in a second rather than by turning
     * off a phone's WiFi.
     */
    @Test
    fun aSinkThatStopsReadingIsDroppedRatherThanWaitedOn() {
        val port = freePort()
        val server = ChunkServer(port)
        server.start()
        try {
            Socket("127.0.0.1", port).use {
                announce(it, PEER)
                waitForClients(server, 1)
                val startedAt = System.nanoTime()
                repeat(CHUNKS_PAST_EVERY_BUFFER) { server.broadcast(chunk(it)) }
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                assertTrue("broadcast blocked for $elapsedMillis ms", elapsedMillis < BLOCKING_MILLIS)
                assertTrue("nothing was dropped, so nothing filled", server.droppedChunks() > 0)
            }
        } finally {
            server.stop()
        }
    }

    /**
     * The other half of the fix that landed on the control channel and not here.
     *
     * A socket only leaves this roster when a write to it fails, and a half open TCP swallows a
     * great many writes before one does - so the connection a handset left behind outlives it, and
     * the handset that comes back stands in the room twice. Measured on three devices: the host
     * reported four sinks against two handsets, while the control channel, which had already been
     * taught to recognise a returning peer, reported the roster correctly.
     *
     * The new connection wins rather than being turned away, for the reason the control channel
     * gives: the old one is only still here because nothing has failed on it yet, which is the
     * same reason nobody noticed it die.
     */
    @Test
    fun aSinkThatComesBackReplacesTheConnectionItLeftBehind() {
        val port = freePort()
        val server = ChunkServer(port)
        server.start()
        try {
            val left = Socket("127.0.0.1", port)
            announce(left, PEER)
            waitForClients(server, 1)
            Socket("127.0.0.1", port).use { returned ->
                announce(returned, PEER)
                waitFor("the returning sink to take the old one's place") { server.replacedSinks() == 1 }

                assertEquals(1, server.clientCount())
                server.broadcast(chunk(3))
                assertEquals(3, FrameReader(returned.getInputStream()).readChunk()?.sequence)
            }
            left.soTimeout = SOCKET_WAIT_MILLIS
            val ending = runCatching { left.getInputStream().read() }

            assertTrue(
                "the connection it left behind is still open",
                ending.getOrNull() == -1 || ending.exceptionOrNull() is SocketException
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun twoSinksThatNameThemselvesDifferentlyBothStay() {
        val port = freePort()
        val server = ChunkServer(port)
        server.start()
        try {
            Socket("127.0.0.1", port).use { one ->
                Socket("127.0.0.1", port).use { other ->
                    announce(one, PEER)
                    announce(other, OTHER_PEER)
                    waitForClients(server, 2)

                    assertEquals(0, server.replacedSinks())
                }
            }
        } finally {
            server.stop()
        }
    }

    /**
     * Unlike the control channel, which closes a connection that cannot name itself.
     *
     * There the cost of turning one away is an icon missing from a drawing; here it is a handset
     * that plays nothing. A sink of an older build, and the probe path, both connect without a
     * name - they lose only the ability to be recognised when they come back.
     */
    @Test
    fun aSinkThatNamesNothingIsStillServed() {
        val port = freePort()
        val server = ChunkServer(port)
        server.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                waitForClients(server, 1)
                server.broadcast(chunk(11))

                assertEquals(11, FrameReader(socket.getInputStream()).readChunk()?.sequence)
            }
        } finally {
            server.stop()
        }
    }

    /**
     * The two halves of the name against each other, rather than against a hand written socket.
     *
     * Every other test here says its name by writing sixteen bytes itself, which would go on
     * passing if the sink had never been taught to say one - and a sink that says nothing is
     * served, so nothing else would fail either. This is the only place the write and the read
     * meet.
     */
    @Test
    fun aSinkOfThisBuildSaysTheNameThisServerReads() {
        val port = freePort()
        val server = ChunkServer(port)
        server.start()
        val left = ChunkClient("127.0.0.1", port, peerId = PEER) {}
        val returned = ChunkClient("127.0.0.1", port, peerId = PEER) {}
        try {
            left.start()
            waitForClients(server, 1)
            returned.start()
            waitFor("the returning sink to take the old one's place") { server.replacedSinks() == 1 }

            assertEquals(1, server.clientCount())
        } finally {
            left.stop()
            returned.stop()
            server.stop()
        }
    }

    /**
     * The list this server can be asked for, and the one sink that is not in it.
     *
     * The list exists to be read against the control channel's roster: a name there and not here
     * is the handset that stopped being sent audio, which until now was a question the host could
     * pose and not answer. A sink that named nothing is served and is counted, and is in no list -
     * so the two readings disagree by one on a healthy room, and that is not a handset leaving.
     */
    @Test
    fun theNamedSinksCanBeListedAndAnUnnamedOneIsOnlyCounted() {
        val port = freePort()
        val server = ChunkServer(port)
        server.start()
        try {
            Socket("127.0.0.1", port).use { named ->
                Socket("127.0.0.1", port).use { nameless ->
                    announce(named, PEER)
                    waitForClients(server, 2)

                    assertEquals(listOf(PEER), server.peerIds())
                    assertEquals(2, server.clientCount())
                    assertTrue("the unnamed sink is not being served", nameless.isConnected)
                }
            }
        } finally {
            server.stop()
        }
    }

    private fun announce(socket: Socket, peerId: String) {
        socket.getOutputStream().apply { write(peerId.toByteArray(Charsets.US_ASCII)); flush() }
    }

    private fun waitForClients(server: ChunkServer, count: Int) {
        waitFor("$count sink(s) to connect") { server.clientCount() == count }
        assertEquals(count, server.clientCount())
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + SOCKET_WAIT_MILLIS * 1_000_000L
        while (!done() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("timed out waiting for $what", done())
    }

    private companion object {
        /** 20 ms of stereo 16-bit at 48 kHz, so the buffers fill at the rate they do in a session. */
        const val FRAME_BYTES = 3840

        /** More than any queue between here and the far end holds: about a minute of audio. */
        const val CHUNKS_PAST_EVERY_BUFFER = 3000

        /** Far below what one blocked write costs, and far above what three thousand enqueues do. */
        const val BLOCKING_MILLIS = 5_000

        /** Long next to a loopback connection and a sixteen byte write, short next to a stuck test. */
        const val SOCKET_WAIT_MILLIS = 4_000

        const val PEER = "0123456789abcdef"
        const val OTHER_PEER = "fedcba9876543210"
    }
}
