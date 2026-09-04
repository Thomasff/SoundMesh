package com.soundmesh.probe.sync

import com.soundmesh.core.AlignmentConfidence
import com.soundmesh.core.AlignmentReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class AlignmentResultChannelTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun reading(errorMs: Double?) = AlignmentReading(
        firstIndex = 48000,
        secondIndex = 72010,
        measuredStaggerFrames = 24010,
        alignmentErrorMs = errorMs,
        propagationCorrectionMs = 0.0,
        separationMetres = 0.0,
        confidence = if (errorMs == null) AlignmentConfidence.UNRELIABLE else AlignmentConfidence.OK,
        ratios = listOf(41.5, 38.25),
        atSearchEdge = listOf(false, false)
    )

    @Test
    fun carriesAWholeRunFromOneHandsetToTheOther() {
        val port = freePort()
        val server = AlignmentResultServer(port)
        server.start()
        try {
            val readings = listOf(reading(-0.198), reading(null), reading(2.177))
            Thread { AlignmentResultClient("127.0.0.1", port).send("O40", readings) }.start()

            val message = server.awaitResult(5_000)

            assertNull(server.failureCode)
            assertEquals("O40", message!!.caseId)
            assertEquals(readings, message.readings)
        } finally {
            server.stop()
        }
    }

    /**
     * A sink that never delivers must not hang the host's own report. The number this bounds is
     * the sink's correlation pass, measured at 11.5s over six pairs.
     */
    @Test
    fun givesUpWhenNothingArrives() {
        val port = freePort()
        val server = AlignmentResultServer(port)
        server.start()
        try {
            val startedAt = System.nanoTime()

            val message = server.awaitResult(300)

            assertNull(message)
            assertEquals("RESULT_TIMEOUT", server.failureCode)
            assertTrue(System.nanoTime() - startedAt < 5_000_000_000L)
        } finally {
            server.stop()
        }
    }

    /**
     * A connection that opens and then dies mid-run reads as a short run, because the sender's
     * close is what marks the end. The header's own count is the only thing that can tell them
     * apart, and half a run must not be combined as though it were whole.
     */
    @Test
    fun refusesATruncatedDelivery() {
        val port = freePort()
        val server = AlignmentResultServer(port)
        server.start()
        try {
            Thread {
                Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write("soundmesh-alignment 1 O40 3".toByteArray())
                }
            }.start()

            val message = server.awaitResult(5_000)

            assertNull(message)
            assertEquals("RESULT_UNREADABLE", server.failureCode)
        } finally {
            server.stop()
        }
    }
}
