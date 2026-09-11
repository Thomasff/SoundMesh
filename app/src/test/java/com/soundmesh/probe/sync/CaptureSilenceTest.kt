package com.soundmesh.probe.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Telling a room that is playing nothing apart from a room that is playing something quiet.
 *
 * The failure it exists for is invisible from every other direction: the session says PLAYING, the
 * counters are healthy, the drift is fine, and every handset in the room is silent.
 */
class CaptureSilenceTest {
    private val chunk = SyncRenderer.FRAMES_PER_CHUNK * SyncRenderer.CHANNELS * 2

    private fun silence() = ByteArray(chunk)

    private fun music() = ByteArray(chunk) { index -> if (index % 7 == 0) 3 else 0 }

    @Before
    fun open() = CaptureSilence.watch()

    @After
    fun close() = CaptureSilence.forget()

    @Test
    fun `a capture that is handing over audio is not silent`() {
        repeat(50) { CaptureSilence.sawChunk(music()) }

        assertEquals(0L, CaptureSilence.silentNanos())
    }

    /**
     * One sample away from silence is not silence.
     *
     * Real audio nobody can hear still has the bottom bit moving. What this is looking for is a
     * path that has stopped producing, and that produces zeros exactly.
     */
    @Test
    fun `a chunk with one sample in it counts as audio`() {
        val nearly = silence()
        nearly[nearly.size - 1] = 1

        CaptureSilence.sawChunk(silence())
        Thread.sleep(20)
        CaptureSilence.sawChunk(nearly)

        assertEquals(0L, CaptureSilence.silentNanos())
    }

    @Test
    fun `silence is measured from the last audio rather than from the first zero`() {
        CaptureSilence.sawChunk(music())
        Thread.sleep(60)
        CaptureSilence.sawChunk(silence())
        Thread.sleep(60)
        CaptureSilence.sawChunk(silence())

        val silent = CaptureSilence.silentNanos()

        assertTrue("$silent", silent >= 100_000_000L)
    }

    /** Audio returning clears it, because what a screen shows has to be about now. */
    @Test
    fun `audio coming back ends the silence`() {
        CaptureSilence.sawChunk(silence())
        Thread.sleep(40)
        CaptureSilence.sawChunk(silence())
        assertTrue(CaptureSilence.silentNanos() > 0L)

        CaptureSilence.sawChunk(music())

        assertEquals(0L, CaptureSilence.silentNanos())
    }

    /** A session that is not capturing reads zero rather than however long ago the last one was. */
    @Test
    fun `a capture that is not open reads nothing`() {
        CaptureSilence.sawChunk(silence())
        Thread.sleep(40)
        CaptureSilence.sawChunk(silence())

        CaptureSilence.forget()

        CaptureSilence.sawChunk(silence())
        assertEquals(0L, CaptureSilence.silentNanos())
    }
}
