package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.TonePcmSource
import java.io.File

/** Where the room is in its list: which song, and how far into it the listener is hearing. */
data class Playhead(val song: Int, val songs: Int, val name: String, val heardMillis: Long, val durationMillis: Long)

/**
 * The songs a desktop host plays, one after another, read a chunk at a time by its stream - the
 * handset host's folder source, for a list of files.
 *
 * A song is decoded whole when it is reached, not before, so a long list costs one song's memory.
 * One that cannot be read is skipped and said, rather than ending the room: the handset does the
 * same (skippedSongs), and a folder is exactly where one odd file turns up.
 *
 * Read on the stream's thread and moved from the window's, so everything is under one lock.
 * Decoding happens under it too, which holds a click on 下一首 for as long as a song takes to
 * decode - a fraction of a second, on a thread that is not the window's.
 */
class SongList(
    private val files: List<File>,
    first: Int = 0,
    /** How far ahead of what is heard the stream reads, so the playhead can say what is heard. */
    private val leadFrames: Long,
    private val open: (File) -> FilePcmSource = { FilePcmSource.open(it) }
) {
    private val lock = Any()
    private var index = first.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    private var song: FilePcmSource? = null
    private var frame = 0L

    @Volatile var paused = false
        private set

    /** The list ran out: nothing more will come. */
    @Volatile var finished = false
        private set

    private val skippedSongs = ArrayList<String>()

    /** Songs that could not be read, each with why, in the order they were reached. */
    fun skipped(): List<String> = synchronized(lock) { skippedSongs.toList() }

    /**
     * Opens the song the list starts on, skipping what cannot be read, and answers whether one
     * could be - so a list with nothing playable is said before anybody is told to play.
     */
    fun prepare(): Boolean = synchronized(lock) { loaded() != null }

    /** The next chunk to send, silence while paused, or null once the list is over. */
    fun nextChunk(): ByteArray? = synchronized(lock) {
        if (paused) return@synchronized ByteArray(CHUNK_BYTES)
        advanced()?.fill(frame, FRAMES)?.also { frame += FRAMES }
    }

    /**
     * Whether a chunk is left to send, moving on to the next song if this one is used up - asked
     * before every chunk, so the stream stops on the song's last chunk and not one after it.
     */
    fun hasMore(): Boolean = synchronized(lock) { paused || advanced() != null }

    /** The song with a chunk left in it, moving down the list as songs run out. Under [lock]. */
    private fun advanced(): FilePcmSource? {
        var current = loaded()
        while (current != null && frame + FRAMES > current.frameCount) {
            index++
            song = null
            frame = 0
            current = loaded()
        }
        return current
    }

    /** [by] songs on, or back, within the list; from the top of that song. */
    fun step(by: Int) = synchronized(lock) {
        index = (index + by).coerceIn(0, files.size - 1)
        song = null
        frame = 0
        finished = false
    }

    /** Into the current song at [millis]. */
    fun seekTo(millis: Long) = synchronized(lock) {
        val current = loaded() ?: return@synchronized
        val wanted = millis * SAMPLE_RATE / 1_000
        frame = (wanted - wanted % FRAMES).coerceIn(0L, (current.frameCount - FRAMES).toLong().coerceAtLeast(0L))
    }

    /**
     * Paused, the stream is sent silence and the song waits where the listener was: moved back
     * by what had been read ahead, so going on again starts from what was last heard - the
     * handset host's seek into a pause.
     */
    fun setPaused(wanted: Boolean) = synchronized(lock) {
        if (wanted == paused) return@synchronized
        if (wanted) frame = heardFrame()
        paused = wanted
    }

    fun playhead(): Playhead? = synchronized(lock) {
        val current = song ?: return@synchronized null
        Playhead(
            song = index,
            songs = files.size,
            name = files[index].name,
            heardMillis = heardFrame() * 1_000 / SAMPLE_RATE,
            durationMillis = current.frameCount.toLong() * 1_000 / SAMPLE_RATE
        )
    }

    /** Under [lock]. While paused nothing is read ahead, so where it stands is what was heard. */
    private fun heardFrame(): Long =
        if (paused) frame else (frame - leadFrames).coerceAtLeast(0L).let { it - it % FRAMES }

    /** The song at [index], opened if it is not, skipping forward past any that cannot be. Under [lock]. */
    private fun loaded(): FilePcmSource? {
        song?.let { return it }
        while (index < files.size) {
            val file = files[index]
            val opened = runCatching { open(file) }
            opened.getOrNull()?.let {
                song = it
                frame = 0
                return it
            }
            skippedSongs.add("${file.name}：${opened.exceptionOrNull()?.message ?: "读不出来"}")
            index++
        }
        finished = true
        return null
    }

    companion object {
        private const val FRAMES = ChunkCodec.FRAMES_PER_CHUNK
        private const val SAMPLE_RATE = TonePcmSource.SAMPLE_RATE.toLong()
        private const val CHUNK_BYTES = FRAMES * 4
    }
}
