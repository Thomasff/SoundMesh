package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SongListTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val chunk = ChunkCodec.FRAMES_PER_CHUNK

    // A tenth of a second: five chunks.
    private fun song(name: String): File = writeTestWav(folder.newFile(name), seconds = 0.1)

    /** The list plays through, passes over a file it cannot read and says so, then ends. */
    @Test
    fun theSongsPlayOneAfterAnotherPastOneThatCannotBeRead() {
        val junk = folder.newFile("junk.wav").apply { writeText("not a wav") }
        val list = SongList(listOf(song("a.wav"), junk, song("b.wav")), leadFrames = 0)
        assertTrue(list.prepare())
        var chunks = 0
        while (list.nextChunk() != null) chunks++
        assertEquals(10, chunks)
        assertTrue(list.finished)
        assertEquals(1, list.skipped().size)
        assertTrue(list.skipped().single().startsWith("junk.wav"))
    }

    /** A list with nothing playable says so before anything is sent. */
    @Test
    fun aListWithNothingPlayableIsSaidUpFront() {
        val junk = folder.newFile("junk.wav").apply { writeText("not a wav") }
        val list = SongList(listOf(junk), leadFrames = 0)
        assertFalse(list.prepare())
        assertNull(list.nextChunk())
    }

    /**
     * Paused, the room is sent silence; going on starts from what was heard, which is a lead
     * behind what had been read.
     */
    @Test
    fun aPauseSendsSilenceAndGoesOnFromWhatWasHeard() {
        val a = song("a.wav")
        val whole = FilePcmSource.open(a)
        val list = SongList(listOf(a), leadFrames = 2L * chunk)
        repeat(4) { list.nextChunk() }
        list.setPaused(true)
        assertArrayEquals(ByteArray(chunk * 4), list.nextChunk())
        assertEquals(2L * chunk * 1_000 / 48_000, list.playhead()!!.heardMillis)
        list.setPaused(false)
        assertArrayEquals(whole.fill(2L * chunk, chunk), list.nextChunk())
    }

    /** A step goes to the top of another song, and a seek to a place in this one. */
    @Test
    fun aStepAndASeekGoWhereTheySay() {
        val b = song("b.wav")
        val list = SongList(listOf(song("a.wav"), b), leadFrames = 0)
        list.nextChunk()
        list.step(1)
        // Sixty milliseconds is three chunks exactly; a seek lands on a chunk's edge.
        list.seekTo(60)
        assertArrayEquals(FilePcmSource.open(b).fill(3L * chunk, chunk), list.nextChunk())
        assertEquals("b.wav", list.playhead()!!.name)
        list.step(-5)
        list.nextChunk()
        assertEquals(0, list.playhead()!!.song)
    }
}
