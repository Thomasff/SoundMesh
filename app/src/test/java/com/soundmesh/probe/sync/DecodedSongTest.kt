package com.soundmesh.probe.sync

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The song a person already waited for, kept between choosing it and pressing play.
 *
 * Every rule here refuses to hand back audio that is not the audio being asked for, because that
 * is the only way this can go wrong and the way it goes wrong is silent: the wrong song plays, or
 * a minute plays where a whole song was meant, with nothing on any screen to say so.
 */
class DecodedSongTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun clear() = DecodedSong.forget()

    private val chunkBytes = SyncRenderer.FRAMES_PER_CHUNK * SyncRenderer.CHANNELS * 2

    private fun song(name: String = "song.m4a", bytes: Int = 16) = folder.newFile(name).apply {
        writeBytes(ByteArray(bytes) { it.toByte() })
    }

    /** Two chunks of audio, without a decoder: what is under test is the keeping, not the decoding. */
    private fun decoded() =
        FileChunkSource(ByteArray(chunkBytes * 2) { it.toByte() }, chunkBytes * 2)

    @Test
    fun whatWasKeptComesBackForTheSameFile() {
        val file = song()
        DecodedSong.keep(file, whole = true, source = decoded())

        assertNotNull(DecodedSong.take(file, whole = true))
    }

    @Test
    fun nothingKeptIsNothingToTake() {
        assertNull(DecodedSong.take(song(), whole = true))
    }

    /**
     * The harness reads sixty seconds of a file and the product reads the whole of it, and those
     * are different arms of every comparison this project has recorded. Handing a whole song to a
     * caller that asked for the prefix would change which arm ran, with nothing in the report to
     * say that it had.
     */
    @Test
    fun aWholeSongIsNotWhatAPrefixAskedFor() {
        val file = song()
        DecodedSong.keep(file, whole = true, source = decoded())

        assertNull(DecodedSong.take(file, whole = false))
    }

    /** A different file is a different song, whatever else is true of it. */
    @Test
    fun anotherFileGetsNothing() {
        DecodedSong.keep(song("first.m4a"), whole = true, source = decoded())

        assertNull(DecodedSong.take(song("second.m4a"), whole = true))
    }

    /**
     * The chosen song lands under one fixed name, so the next song a person picks writes over the
     * last one. Without this, picking a second song and pressing play would play the first.
     */
    @Test
    fun aFileRewrittenUnderTheSameNameIsADifferentSong() {
        val file = song(bytes = 16)
        DecodedSong.keep(file, whole = true, source = decoded())
        file.writeBytes(ByteArray(32) { it.toByte() })

        assertNull(DecodedSong.take(file, whole = true))
    }

    /**
     * Length as well as time, because a filesystem's timestamp is not always finer than a second:
     * two picks inside one tick carry the same modification time, and then the size is all that is
     * left to say the file changed. Written with the stamp put back, which is what that would look
     * like from here.
     */
    @Test
    fun aRewriteThatKeptItsTimestampIsStillARewrite() {
        val file = song(bytes = 16)
        val stamped = file.lastModified()
        DecodedSong.keep(file, whole = true, source = decoded())
        file.writeBytes(ByteArray(32) { it.toByte() })
        file.setLastModified(stamped)

        assertNull(DecodedSong.take(file, whole = true))
    }

    /**
     * And a rewrite that happens to be the same length is still a rewrite.
     *
     * Guarded here as well as by the line that forgets the cache before a new song is copied in,
     * because that line is one statement somewhere else and this is what fails safe if it moves.
     */
    @Test
    fun aFileTouchedSinceItWasDecodedIsNotTrusted() {
        val file = song()
        DecodedSong.keep(file, whole = true, source = decoded())
        file.setLastModified(file.lastModified() + 5_000L)

        assertNull(DecodedSong.take(file, whole = true))
    }

    /**
     * A fresh reader each time, never the one that was kept. FileChunkSource carries a position
     * that wraps, so handing the same object to a second session would start it wherever the first
     * one stopped - in the middle of the song, and only on the handset that did the choosing.
     */
    @Test
    fun everyTakeStartsAtTheBeginningOfTheSong() {
        val file = song()
        val kept = decoded()
        val first = kept.readChunk()
        DecodedSong.keep(file, whole = true, source = kept)

        val taken = DecodedSong.take(file, whole = true)!!
        assertNotSame(kept, taken)
        assertArrayEquals(first, taken.readChunk())
        assertEquals(kept.chunkCount, taken.chunkCount)
    }

    /** Twice over, because a person who stops a session and starts another gets two takes. */
    @Test
    fun aSecondTakeAlsoStartsAtTheBeginning() {
        val file = song()
        DecodedSong.keep(file, whole = true, source = decoded())

        val once = DecodedSong.take(file, whole = true)!!.readChunk()
        DecodedSong.take(file, whole = true)!!.readChunk()
        assertArrayEquals(once, DecodedSong.take(file, whole = true)!!.readChunk())
    }
}
