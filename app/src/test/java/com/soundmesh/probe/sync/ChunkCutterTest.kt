package com.soundmesh.probe.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder hands over whatever it happened to produce; the renderer wants one exact size.
 *
 * Everything interesting here is about what is *not* handed on yet. A converter that emitted a
 * short chunk at the end of every piece would still play - at a rate set by the decoder's buffer
 * sizes rather than by the timeline - and the run report would look sane throughout.
 */
class ChunkCutterTest {
    private val chunkBytes = 8

    @Test
    fun wholeChunksComeStraightBack() {
        val cutter = ChunkCutter(chunkBytes)
        val chunks = cutter.cut(counting(24))
        assertEquals(3, chunks.size)
        assertArrayEquals(counting(24).copyOfRange(0, 8), chunks[0])
        assertArrayEquals(counting(24).copyOfRange(16, 24), chunks[2])
    }

    /** One byte short is no chunk at all. A short chunk is a shorter twenty milliseconds. */
    @Test
    fun aPartOfAChunkIsHeldBackUntilTheRestArrives() {
        val cutter = ChunkCutter(chunkBytes)
        assertEquals(0, cutter.cut(counting(7)).size)
        val chunks = cutter.cut(byteArrayOf(7))
        assertEquals(1, chunks.size)
        assertArrayEquals(counting(8), chunks[0])
    }

    /**
     * The seam where the song wraps, and later where one song becomes the next: what was held
     * back is finished by whatever comes after it, in order, with nothing dropped between them.
     *
     * The whole-buffer source could avoid this by truncating a song to a whole number of chunks
     * before it started. A stream does not know where the end is until it gets there.
     */
    @Test
    fun whatWasHeldBackIsFinishedByWhatComesNext() {
        val cutter = ChunkCutter(chunkBytes)
        cutter.cut(counting(5))
        val chunks = cutter.cut(byteArrayOf(100, 101, 102, 103))
        assertEquals(1, chunks.size)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 100, 101, 102), chunks[0])
    }

    @Test
    fun nothingInIsNothingOut() {
        assertEquals(0, ChunkCutter(chunkBytes).cut(ByteArray(0)).size)
    }

    /**
     * Fed in pieces of every awkward size, the bytes come out in the order they went in and none
     * of them go missing. The one general statement worth making, so it is made against a stream
     * long enough to wrap many times.
     */
    @Test
    fun theBytesComeOutInTheOrderTheyWentIn() {
        for (piece in listOf(1, 3, 7, 8, 9, 100, 1000)) {
            val source = counting(1000)
            val cutter = ChunkCutter(chunkBytes)
            var out = ByteArray(0)
            var at = 0
            while (at < source.size) {
                val end = minOf(at + piece, source.size)
                cutter.cut(source.copyOfRange(at, end)).forEach { out += it }
                at = end
            }
            assertEquals("pieces of $piece", source.size - out.size, cutter.carried)
            assertArrayEquals("pieces of $piece", source.copyOfRange(0, out.size), out)
        }
    }

    /**
     * What is held is under one chunk, always. This is the whole memory claim: a cutter that grew
     * with the song would put the ceiling back that the streaming path exists to remove.
     */
    @Test
    fun whatIsHeldBackIsNeverAWholeChunk() {
        val cutter = ChunkCutter(chunkBytes)
        var worst = 0
        repeat(500) {
            cutter.cut(counting(13))
            worst = maxOf(worst, cutter.carried)
        }
        assertTrue("held $worst of $chunkBytes", worst < chunkBytes)
    }

    /** 0, 1, 2, ... so a byte out of order is visible rather than merely unequal. */
    private fun counting(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }
}
