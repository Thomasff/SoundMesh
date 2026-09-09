package com.soundmesh.probe.sync

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.soundmesh.core.StreamingResampler
import java.io.File

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
class StreamingChunkSource private constructor(private val song: File) {
    private val queue = ChunkQueue(READ_AHEAD_CHUNKS, POLL_MILLIS, STARVED_MILLIS)
    private val cutter = ChunkCutter(CHUNK_BYTES)

    private val thread = Thread({ run() }, "song-decoder")

    /** One full chunk, or null once this source is closed. Blocks while the decoder catches up. */
    fun readChunk(): ByteArray? = queue.take()

    /** How many chunks the renderer had to wait for. Zero on a session that kept up. */
    fun lateChunks(): Int = queue.lateChunks()

    fun close() {
        queue.stop()
        thread.interrupt()
        runCatching { thread.join(JOIN_MILLIS) }
    }

    private fun run() {
        try {
            play(song)
            // Once, and then the song is over. It used to start again here, which is what the
            // whole-buffer source does by resetting a read position - and from a room that is a
            // song with no end and no way to reach one short of stopping the session.
            //
            // Stopping the queue is not emptying it: what is already decoded is still handed over,
            // and the consumer is answered with null only once it runs out. That is how the host
            // learns the difference between a song that ended and a source that broke.
            //
            // This is the one place a folder of songs would answer differently, and opening the
            // next file rather than seeking back is what makes that the same code: the next song
            // may be at another rate entirely, so it would need its own decoder and its own
            // converter regardless.
            queue.stop()
        } catch (interrupted: InterruptedException) {
            Log.i(LOG_TAG, "the decoder was asked to stop")
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "the decoder stopped", error)
            queue.fail(error)
        }
    }

    private fun play(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
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
        while (!queue.stopped) {
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
        if (!queue.stopped && converted < CHUNK_BYTES) throw SourceUnusable("SOURCE_FILE_TOO_SHORT")
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
        fun open(file: File): StreamingChunkSource {
            if (!file.isFile) throw SourceUnusable("SOURCE_FILE_MISSING")
            val source = StreamingChunkSource(file)
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
