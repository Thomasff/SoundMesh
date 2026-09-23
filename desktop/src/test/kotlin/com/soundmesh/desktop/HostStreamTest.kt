package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.sync.ChunkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class HostStreamTest {
    @Test
    fun aSinkOnTheLoopbackIsSentEveryChunkInOrder() {
        val received = streamTo(chunks = 25)

        assertEquals(25, received.size)
        assertEquals((0 until 25).toList(), received.map { it.sequence })
        assertTrue(
            "a chunk is ${ChunkCodec.FRAMES_PER_CHUNK} frames of stereo PCM16",
            received.all { it.pcm.size == ChunkCodec.FRAMES_PER_CHUNK * 2 * 2 }
        )
    }

    /**
     * The instants have to come off one anchor, not off the clock each time round.
     *
     * Windows sleeps in steps of 15.625 ms and a chunk is 20, so a loop that stamped each chunk
     * with the clock it woke up on would hand the sink a timeline that jittered by most of a
     * chunk - inaudible on a handset, where the same loop drifts by a millisecond or two, and
     * plainly audible here. Nothing else in the run would look wrong: every chunk arrives, in
     * order, at the right average rate.
     */
    @Test
    fun theInstantsAreAnAnchorPlusTheSequence() {
        val received = streamTo(chunks = 25)

        val first = received.first().playAtHostNanos
        assertEquals(
            (0 until 25).map { first + it * HostStream.CHUNK_NANOS },
            received.map { it.playAtHostNanos }
        )
    }

    @Test
    fun theFirstInstantIsAWholeLeadAwayFromWhenItWasSent() {
        val before = System.nanoTime()
        val received = streamTo(chunks = 5)
        val after = System.nanoTime()

        val lead = received.first().playAtHostNanos
        assertTrue(
            "the first chunk is to be played ${(lead - before) / 1e6} ms from now, and the lead is " +
                "${HostStream.DEFAULT_LEAD_NANOS / 1e6} ms",
            lead >= before + HostStream.DEFAULT_LEAD_NANOS && lead <= after + HostStream.DEFAULT_LEAD_NANOS
        )
    }

    /**
     * A host with its own speakers plays the instants it sent, not instants of its own.
     *
     * Its clock IS the host clock, so the offset is zero and the frame a chunk lands on is a
     * function of the instant on the wire alone. Two timelines that agreed on the wire and
     * disagreed in the room would be the one fault nobody could hear from either end.
     */
    @Test
    fun aHostWithSpeakersPlaysTheInstantsItSent() {
        val port = freePort()
        val output = FakeOutput()
        val host = HostStream(port, TonePcmSource()::fill, localOutput = output)
        val received = CopyOnWriteArrayList<AudioChunk>()
        val client = ChunkClient("127.0.0.1", port) { received.add(it) }
        try {
            host.start()
            client.start()
            awaitSink(host)
            host.stream(25)
            awaitDelivery(received, 25)
        } finally {
            client.stop()
            host.stop()
        }

        assertEquals(25, host.playedLocally())
        assertEquals(
            received.map { output.frameAtLocalNanos(it.playAtHostNanos) },
            output.scheduled.map { it.first }
        )
        assertEquals("a whole chunk apart leaves no seam", 0, host.worstLocalSeamFrames())
    }

    private fun streamTo(chunks: Int): List<AudioChunk> {
        val port = freePort()
        val host = HostStream(port, TonePcmSource()::fill)
        val received = CopyOnWriteArrayList<AudioChunk>()
        val client = ChunkClient("127.0.0.1", port) { received.add(it) }
        try {
            host.start()
            client.start()
            // A sink that names nothing is only in the roster once the host's wait for a name has
            // run out, and a chunk broadcast before that is sent to nobody at all.
            awaitSink(host)
            host.stream(chunks)
            awaitDelivery(received, chunks)
        } finally {
            client.stop()
            host.stop()
        }
        return received.toList()
    }

    private fun awaitSink(host: HostStream) {
        val deadline = System.nanoTime() + ARRIVAL_TIMEOUT_NANOS
        while (host.sinkCount() < 1) {
            if (System.nanoTime() > deadline) throw AssertionError("no sink connected")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun awaitDelivery(received: List<AudioChunk>, chunks: Int) {
        val deadline = System.nanoTime() + ARRIVAL_TIMEOUT_NANOS
        while (received.size < chunks) {
            if (System.nanoTime() > deadline) throw AssertionError("only ${received.size} of $chunks arrived")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val ARRIVAL_TIMEOUT_NANOS = 10_000_000_000L
        const val POLL_MILLIS = 5L
    }
}
