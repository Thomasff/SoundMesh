package com.soundmesh.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class CaptureFeedTest {
    private val clock = AtomicLong(0L)

    private fun feed(chunk: Int = 8, backlog: Int = 32) =
        CaptureFeed(chunkBytes = chunk, starvedNanos = 50_000_000L, maxBacklogBytes = backlog, now = clock::get)

    private fun bytes(from: Int, count: Int) = ByteArray(count) { (from + it).toByte() }

    /** Packets that do not line up with chunks come out as chunks, in order, across the ring's end. */
    @Test
    fun packetsComeOutAsWholeChunksInOrder() {
        val feed = feed()
        feed.push(bytes(0, 12), 12)
        assertArrayEquals(bytes(0, 8), feed.nextChunk())
        feed.push(bytes(12, 28), 28)
        assertArrayEquals(bytes(8, 8), feed.nextChunk())
        assertArrayEquals(bytes(16, 8), feed.nextChunk())
        assertArrayEquals(bytes(24, 8), feed.nextChunk())
        assertArrayEquals(bytes(32, 8), feed.nextChunk())
        assertEquals(0, feed.paddedChunks)
    }

    /**
     * A program with nothing playing hands over nothing, and the stream is not held up by it: once
     * the capture has starved, what there is goes out with silence after it, at once.
     */
    @Test
    fun aStarvedCaptureIsMadeUpWithSilenceAtOnce() {
        val feed = feed()
        feed.push(bytes(1, 4), 4)
        clock.set(60_000_000L)
        assertArrayEquals(bytes(1, 4) + ByteArray(4), feed.nextChunk())
        val started = System.nanoTime()
        assertArrayEquals(ByteArray(8), feed.nextChunk())
        assertTrue("waited on a capture already known to be starved", System.nanoTime() - started < 20_000_000L)
        assertEquals(2, feed.paddedChunks)
    }

    /** Short of a chunk but not starved, it waits for the rest rather than making it up. */
    @Test
    fun theRestOfAChunkIsWaitedFor() {
        val feed = feed()
        feed.push(bytes(0, 4), 4)
        Thread {
            Thread.sleep(120)
            feed.push(bytes(4, 4), 4)
        }.start()
        assertArrayEquals(bytes(0, 8), feed.nextChunk())
        assertEquals(0, feed.paddedChunks)
    }

    /** A capture running ahead loses its oldest whole frames, never more than it has to. */
    @Test
    fun aCaptureTooFarAheadLosesItsOldestFrames() {
        val feed = feed(backlog = 16)
        feed.push(bytes(0, 12), 12)
        feed.push(bytes(12, 8), 8)
        assertEquals(4L, feed.droppedBytes)
        assertArrayEquals(bytes(4, 8), feed.nextChunk())
        assertArrayEquals(bytes(12, 8), feed.nextChunk())
    }
}
