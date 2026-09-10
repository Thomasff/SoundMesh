package com.soundmesh.probe.sync

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.soundmesh.core.StreamingResampler
import com.soundmesh.core.songAfterStep
import java.util.concurrent.atomic.AtomicReference

/**
 * Where a song is up to and how long it is, both in microseconds.
 *
 * [songIndex] because a folder's playhead has to say which song it is the playhead of - a slider
 * that jumped back to the left without saying why is a slider that looks broken.
 */
data class Playhead(val songIndex: Int, val positionMicros: Long, val durationMicros: Long)

/**
 * Where the listener asked to go next: a place, and which song it is a place in.
 *
 * Carrying the index is what makes next and previous the same mechanism as the slider instead of
 * a second one beside it. A drag names the song already playing; a press on next or previous
 * names its neighbour. Everything after that - ending the decoding pass, emptying the queue,
 * opening a file at an instant - is the path the slider already took.
 */
private data class Jump(val songIndex: Int, val micros: Long)

/**
 * A song decoded a piece at a time, on its own thread, into a queue the renderer takes from.
 *
 * This is [FileChunkSource] with the ceiling taken off. That source decodes the whole song before
 * a note of it is played, which is what makes reading a chunk arithmetic over an array - and also
 * what puts a limit on how long a song may be, because the whole of it plus its conversion has to
 * fit in what a phone gives one app. Here the decoder runs ahead of the renderer by a fixed
 * number of chunks and no further, so a forty minute song costs exactly what a three minute one
 * costs.
 *
 * The margin is not tight. Decoding runs about four milliseconds per second of audio on these
 * handsets, against a renderer that wants twenty milliseconds of audio every twenty milliseconds
 * - some two hundred and fifty times more time than the work needs. What the queue is really for
 * is the bursts: a codec that pauses to reconfigure, or a phone that is briefly busy elsewhere.
 * Three seconds of them are absorbed here before anything reaches the timeline, and the host's
 * own 1.5 s lead absorbs more after that.
 *
 * The waiting itself is [ChunkQueue], split off by what can be looked at away from a handset:
 * what is left here is codec and can only be watched on a phone, while how long to wait, when to
 * give up, and what counts as falling behind are arithmetic over a queue and have tests. That
 * line is drawn where this code has already had a bug.
 *
 * [CaptureChunkSource] is the same shape - a producer that blocks - and the difference between
 * them is the only difference that matters between playing a file and playing what another app is
 * playing: a file can be read ahead of where the listener is, and a live capture cannot.
 */
class StreamingChunkSource private constructor(
    private val context: Context,
    private val songs: List<Uri>
) {
    private val queue = ChunkQueue(READ_AHEAD_CHUNKS, POLL_MILLIS, STARVED_MILLIS)
    private val cutter = ChunkCutter(CHUNK_BYTES)

    @Volatile private var skipped = 0

    /**
     * Where the listener asked to go, or null if nobody has asked.
     *
     * Read and taken by the decoder thread, written by whoever holds the slider or the buttons.
     * Null is nobody having asked, which is not the same as a jump to zero - zero is the start of
     * a song, and somebody dragging a slider all the way left means it.
     */
    private val jumpRequest = AtomicReference<Jump?>(null)

    @Volatile private var decodedMicros = 0L
    @Volatile private var songMicros = 0L
    @Volatile private var songIndex = 0

    private val thread = Thread({ run() }, "song-decoder")

    /** One full chunk, or null once this source is closed. Blocks while the decoder catches up. */
    fun readChunk(): ByteArray? = queue.take()

    /** How many chunks the renderer had to wait for. Zero on a session that kept up. */
    fun lateChunks(): Int = queue.lateChunks()

    /**
     * Songs that would not play and were passed over.
     *
     * The one mark they leave anywhere. A folder plays on without them and sounds exactly like a
     * folder that never held them, so the listener's only other evidence is remembering how many
     * tracks the album has.
     */
    fun skippedSongs(): Int = skipped

    /**
     * Starts playing the song that is playing from [micros] instead.
     *
     * Returns as soon as it is asked for, not once it is heard: the decoder has to finish the pass
     * it is in, and what was already handed to the room is somebody else's to throw away. Emptying
     * the queue here is the deeper half of that - three seconds of decoded audio against the
     * host's 1.5 s of lead.
     */
    fun seekTo(micros: Long) {
        jumpRequest.set(Jump(songIndex, maxOf(0L, micros)))
        queue.discard()
    }

    /**
     * Starts the song [by] places along the list, from its beginning.
     *
     * The two ends are [songAfterStep]'s to decide, and it is asked here rather than by the
     * caller because the index it decides from is this thread's - a caller reading it first and
     * asking second could name a song by the time the answer arrived was no longer the neighbour
     * of anything.
     */
    fun stepSong(by: Int) {
        jumpRequest.set(Jump(songAfterStep(songIndex, by), 0L))
        queue.discard()
    }

    /**
     * Where the next chunk handed over sits in the song, or null before there is one.
     *
     * The decoder runs ahead, so what it has reached is not what anybody is about to hear: the
     * difference is exactly what is still waiting in the queue. The host's own lead is not taken
     * off here - that belongs to whoever knows what the lead is.
     */
    fun playhead(): Playhead? {
        if (songMicros <= 0L) return null
        // Within one song this is right to a chunk. **Across a song boundary it is not**: the
        // length and the index belong to the song being decoded while the queue still holds three
        // seconds of the one before it, so a folder's slider jumps to the next song a few seconds
        // early. Carrying a position with every chunk through the queue would fix it, and it would
        // cost that plumbing to move a slider by three seconds once a song. A jump asked for in
        // that window lands in the song being decoded, which is the one the slider is showing.
        val waiting = queue.depth * CHUNK_MICROS
        return Playhead(songIndex, maxOf(0L, decodedMicros - waiting), songMicros)
    }

    fun close() {
        queue.stop()
        thread.interrupt()
        runCatching { thread.join(JOIN_MILLIS) }
    }

    private fun run() {
        try {
            playThroughTheList()
            // Once each, and then there is no more. It used to start the one song again here,
            // which is what the whole-buffer source does by resetting a read position - and from
            // a room that was a song with no end and no way to reach one short of stopping.
            //
            // Stopping the queue is not emptying it: what is already decoded is still handed over,
            // and the consumer is answered with null only once it runs out. That is how the host
            // learns the difference between a list that ended and a source that broke.
            queue.stop()
        } catch (interrupted: InterruptedException) {
            Log.i(LOG_TAG, "the decoder was asked to stop")
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "the decoder stopped", error)
            queue.fail(error)
        }
    }

    /**
     * Each song in turn, opening the next one where the last one ended.
     *
     * Opening rather than seeking is what lets a folder be the same code as a single song: the
     * next song may be at another sample rate entirely, so it needs its own extractor, its own
     * decoder and its own converter whatever else is true. What carries across the seam is the
     * queue and the cutter, so the last part-chunk of one song is finished by the first bytes of
     * the next and the timeline never sees a join at all.
     *
     * **A song that will not play is passed over rather than fatal.** One unreadable file in a
     * folder of two hundred is not a reason to stop the evening. The exception is a list where
     * nothing played: then the first refusal is thrown, because that is a person who chose
     * something and got silence, and the code they need is the one the first song gave.
     */
    private fun playThroughTheList() {
        var played = false
        var firstRefusal: Exception? = null
        var index = 0
        var from = 0L
        while (index < songs.size && !queue.stopped) {
            songIndex = index
            try {
                play(songs[index], from)
                played = true
            } catch (interrupted: InterruptedException) {
                // Closing, not failing. Passing over the rest of the folder would be the same
                // outward behaviour and would take several seconds of opening files to get there.
                throw interrupted
            } catch (refused: Exception) {
                // Anything, not only a SourceUnusable. A song that was deleted after the folder
                // was listed arrives as an IOException, and it is exactly as skippable as one that
                // will not decode - the listener's folder is missing a track either way.
                skipped++
                if (firstRefusal == null) firstRefusal = refused
                Log.i(LOG_TAG, "a song in the folder was passed over: ${refused.message}")
            }
            // A jump asked for while that song was playing names where to open next - the
            // same song at the place a slider asked for, or a neighbour a button asked for.
            // Anything else means the song is over and the list moves on by itself.
            //
            // Opening rather than seeking the extractor in place, for the reason the folder gives:
            // the converter holds sixteen samples either side of where it is and the codec holds
            // whatever it has buffered, and both would have to be told - where opening again is
            // the path a folder already takes every time it changes song.
            val asked = jumpRequest.getAndSet(null)
            if (asked != null) {
                index = asked.songIndex
                from = asked.micros
                // What the cutter is holding back is from where the listener just left, and
                // finishing the next chunk with it would put a few milliseconds of the old
                // place at the front of the new one. Here rather than inside play(), because
                // the condition is "the listener jumped" and not "the new place is not zero":
                // a press on next lands at zero and is exactly as much of a cut as a drag is.
                cutter.forget()
            } else {
                index++
                from = 0L
            }
        }
        if (!played) firstRefusal?.let { throw it }
    }

    private fun play(song: Uri, fromMicros: Long) {
        val extractor = MediaExtractor()
        try {
            // The address the system handed over rather than a path, so a song is read where it
            // lies. Copying it here first is what this used to do, and what a folder of two
            // hundred songs made impossible.
            extractor.setDataSource(context, song, null)
            var track = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = index
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) throw SourceUnusable("SOURCE_FILE_NO_AUDIO")
            extractor.selectTrack(track)
            // How long the song is, which is the only reason a slider can be drawn at all. A
            // container that does not say leaves this at zero, and a playhead nobody can read is
            // better than a slider whose right-hand end is a guess.
            songMicros = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            if (fromMicros > 0L) {
                // The nearest sync sample at or before the ask. A decoder handed a non-sync sample
                // to start from produces noise until the next one arrives.
                extractor.seekTo(fromMicros, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                pump(extractor, codec)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * One pass through the song: decode, convert, cut, hand over.
     *
     * Handing over is where the pacing lives. The queue is bounded, so [ArrayBlockingQueue.put]
     * blocks once the decoder is a full three seconds ahead - there is no rate to compute and no
     * clock to read here, and the decoder cannot run away with the memory even if the renderer
     * stops taking chunks entirely.
     */
    private fun pump(extractor: MediaExtractor, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var resampler: StreamingResampler? = null
        var converted = 0L
        var inputDone = false
        // A jump ends this pass rather than being handled inside it. The converter is holding
        // sixteen samples either side of where it is and the codec is holding whatever it has
        // buffered; the caller opens the song again at the new place, which is the path a folder
        // already takes every time it changes song.
        while (!queue.stopped && jumpRequest.get() == null) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_MICROS)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)!!
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_MICROS)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val output = codec.outputFormat
                FileChunkSource.requireUsableFormat(output)
                resampler = StreamingResampler(
                    output.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    output.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    SyncRenderer.SAMPLE_RATE
                )
            } else if (outputIndex >= 0) {
                val buffer = codec.getOutputBuffer(outputIndex)!!
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                codec.releaseOutputBuffer(outputIndex, false)
                // Where this buffer sits in the song. What the listener hears is behind it by
                // whatever is still queued - see playhead().
                decodedMicros = info.presentationTimeUs
                // The sync API answers with the output format before the first output buffer, so
                // this is unreachable in practice - which is exactly why it throws.
                val stream = resampler ?: throw SourceUnusable("SOURCE_FILE_FORMAT_UNKNOWN")
                converted += hand(stream.write(bytes))
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    converted += hand(stream.finish())
                    break
                }
            }
        }
        // A song shorter than one chunk would otherwise be reopened hundreds of times a second,
        // which from outside is a decoder thread at full tilt and a room playing a stutter.
        //
        // Not on a jump: a listener who dragged the slider to the last second of a song has asked
        // for less than a chunk of audio and is owed the next song, not a refusal.
        if (!queue.stopped && jumpRequest.get() == null && converted < CHUNK_BYTES) {
            throw SourceUnusable("SOURCE_FILE_TOO_SHORT")
        }
    }

    /** Queues every whole chunk this piece completes, waiting while the renderer catches up. */
    private fun hand(pcm: ByteArray): Long {
        cutter.cut(pcm).forEach { queue.put(it) }
        return pcm.size.toLong()
    }

    companion object {
        private const val LOG_TAG = "SoundMeshStream"
        private const val BYTES_PER_SAMPLE = 2
        private const val DEQUEUE_TIMEOUT_MICROS = 10_000L
        private const val POLL_MILLIS = 100L
        private const val JOIN_MILLIS = 2_000L

        /** How long the renderer waits on an empty queue before calling the decoder gone. */
        private const val STARVED_MILLIS = 5_000L

        /**
         * Three seconds of audio, which is the scheduler's own capacity and not a coincidence:
         * more than that here would be a second copy of a queue that already exists downstream.
         */
        private const val READ_AHEAD_CHUNKS = 150

        /** How long one chunk lasts, which is what a queued chunk is worth to a playhead. */
        const val CHUNK_MICROS = SyncRenderer.FRAMES_PER_CHUNK * 1_000_000L / SyncRenderer.SAMPLE_RATE

        private val CHUNK_BYTES =
            SyncRenderer.FRAMES_PER_CHUNK * SyncRenderer.CHANNELS * BYTES_PER_SAMPLE

        /**
         * Starts decoding, and answers once there is audio to play or a reason there is not.
         *
         * Waiting for the first chunk rather than returning straight away is what keeps a bad
         * file a refusal at the moment it is chosen, with the same codes the whole-buffer source
         * throws, instead of a session that starts and then stops for reasons nobody sees. It
         * costs one chunk's worth of decoding, which is well under a millisecond of work.
         */
        fun open(context: Context, songs: List<Uri>): StreamingChunkSource {
            // A folder whose songs are all sub-folders, or one that was emptied between being
            // chosen and being played. Refused here rather than started, because a session that
            // begins and immediately ends is a stop nobody asked for and nobody can explain.
            if (songs.isEmpty()) throw SourceUnusable("SOURCE_FOLDER_EMPTY")
            val source = StreamingChunkSource(context, songs)
            source.thread.start()
            val arrived = source.queue.awaitFirst(STARVED_MILLIS)
            source.queue.failure()?.let {
                source.close()
                throw it
            }
            if (!arrived) {
                source.close()
                throw SourceUnusable("SOURCE_FILE_DECODE_STALLED")
            }
            return source
        }
    }
}
