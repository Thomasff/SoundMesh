package com.soundmesh.probe.sync

import com.soundmesh.core.Crossover
import com.soundmesh.core.DistanceShelf
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/**
 * The two ends of the wire, checked against each other on the only thing that matters: whether the
 * bytes they produce for the same host instant belong to the same room.
 *
 * Neither session class can be run off a device - one binds three sockets and both own a renderer
 * that opens an AudioTrack - so what is assembled here is the path they wire up rather than the
 * classes themselves: a rule published on one side, decoded on the other, and both sides asked to
 * shape the same chunk at the same instant.
 *
 * The failure this exists for is a rotation running out of phase between two handsets, which is
 * what any part of the rule not surviving the wire produces. It is also the failure with the worst
 * symptom-to-cause distance: both phones sound fine on their own.
 */
class SpatialRoomTest {
    private val host = "a1b2c3d4e5f60718"
    private val sink = "0918273645abcdef"

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitTrue(what: String, test: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (test()) return
            Thread.sleep(5L)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun steady(level: Int = 9_000): ByteArray {
        val pcm = ByteArray(SyncRenderer.FRAMES_PER_CHUNK * SyncRenderer.CHANNELS * 2)
        for (index in 0 until pcm.size / 2) {
            pcm[index * 2] = (level and 0xFF).toByte()
            pcm[index * 2 + 1] = (level shr 8).toByte()
        }
        return pcm
    }

    /**
     * A rotation is a function of the host instant, so a handset holding a rule that differs in
     * any part - the epoch most of all - keeps playing, keeps reporting health, and turns at a
     * different point on the circle from everybody else in the room.
     */
    @Test
    fun bothEndsOfTheWireShapeTheSameInstantTheSameWay() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        val received = AtomicReference<SpatialField?>(null)
        val client = SpatialFieldClient("127.0.0.1", port, sink) { received.set(it) }
        // An epoch well away from zero, because an epoch dropped on the wire is invisible against
        // one that was zero to begin with.
        val drawn = SpatialField(
            SpatialMode.ROTATE,
            SpatialLayout(listOf(SpatialPosition(host, -0.7, 0.9), SpatialPosition(sink, 1.3, -0.4))),
            epochHostNanos = 7_431_002_005_119L
        )
        server.start()
        try {
            client.start()
            awaitTrue("the sink to be named") { server.peerIds() == listOf(sink) }
            server.publish(drawn)
            awaitTrue("the rule to arrive") { received.get() != null }

            val instant = 7_431_002_005_119L + 1_234_567_890L
            val pcm = steady()
            for (who in listOf(host, sink)) {
                assertArrayEquals(
                    "$who heard a different room than the host drew",
                    spatialShaped(11, instant, pcm, drawn, who, crossover = Crossover(), shelf = DistanceShelf()),
                    spatialShaped(11, instant, pcm, received.get(), who, crossover = Crossover(), shelf = DistanceShelf())
                )
            }
            // Not vacuous: the two handsets are in different places, so the same instant has to
            // give them different gains, and an all-silent chunk would satisfy the check above.
            assertFalse(
                "the two handsets were given the same gain",
                spatialShaped(11, instant, pcm, drawn, host, crossover = Crossover(), shelf = DistanceShelf())
                    .contentEquals(spatialShaped(11, instant, pcm, drawn, sink, crossover = Crossover(), shelf = DistanceShelf()))
            )
            assertTrue(
                "the rule did nothing at all",
                !spatialShaped(11, instant, pcm, drawn, host, crossover = Crossover(), shelf = DistanceShelf()).contentEquals(pcm)
            )
        } finally {
            client.stop()
            server.stop()
        }
    }

}
