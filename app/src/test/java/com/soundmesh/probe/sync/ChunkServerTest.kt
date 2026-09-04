package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

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
                waitForClient(server)
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
                waitForClient(server)
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

    private fun waitForClient(server: ChunkServer) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (server.clientCount() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(1, server.clientCount())
    }

    private companion object {
        /** 20 ms of stereo 16-bit at 48 kHz, so the buffers fill at the rate they do in a session. */
        const val FRAME_BYTES = 3840

        /** More than any queue between here and the far end holds: about a minute of audio. */
        const val CHUNKS_PAST_EVERY_BUFFER = 3000

        /** Far below what one blocked write costs, and far above what three thousand enqueues do. */
        const val BLOCKING_MILLIS = 5_000
    }
}
