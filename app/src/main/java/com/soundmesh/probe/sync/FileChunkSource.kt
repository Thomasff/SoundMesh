package com.soundmesh.probe.sync

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.soundmesh.core.Resampler
import com.soundmesh.core.SourceBudget
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Decodes an audio file once, up front, and hands it out as chunks on a loop.
 *
 * Decoding into memory rather than streaming keeps every decoder call out of the host's real-time
 * loop: once the file is open, [readChunk] is arithmetic over a byte array. How much gets decoded
 * is the difference between the two entry points, and it is the only difference: [open] takes the
 * 60 s prefix every archived alignment measurement was made with, [openWhole] takes the song.
 *
 * The seam where the audio wraps is a waveform discontinuity, and it is left in on purpose. Both
 * handsets emit it from the same chunk at the same instant, so it is a shared transient - far
 * easier to hear misalignment in than sustained music is.
 */
/**
 * [length] is where the audio ends, always a whole number of chunks and never more than one chunk
 * short of [pcm]. Held as a length rather than trimmed off with a copy because a whole song is
 * tens of megabytes and the tail being dropped is under four kilobytes of it.
 */
class FileChunkSource internal constructor(private val pcm: ByteArray, private val length: Int) {
    private var position = 0

    /** The decoded audio, in whole chunks - what the run report says it is playing. */
    val chunkCount: Int get() = length / CHUNK_BYTES

    /**
     * Another reader over the same decoded audio, starting at the beginning.
     *
     * The audio is the expensive thing and the position is not, so this is what makes a decoded
     * song reusable: see [DecodedSong]. The array is shared rather than copied because nothing
     * here ever writes to it, and copying it would spend the seventy-odd megabytes this exists to
     * avoid spending twice.
     */
    fun rewound(): FileChunkSource = FileChunkSource(pcm, length)

    /** One full chunk, wrapping back to the start when it runs out. */
    fun readChunk(): ByteArray {
        val chunk = ByteArray(CHUNK_BYTES)
        var filled = 0
        while (filled < CHUNK_BYTES) {
            val take = minOf(CHUNK_BYTES - filled, length - position)
            pcm.copyInto(chunk, filled, position, position + take)
            filled += take
            position += take
            if (position >= length) position = 0
        }
        return chunk
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val PREFIX_SECONDS = 60
        private const val DEQUEUE_TIMEOUT_MICROS = 10_000L
        // Half a millisecond of patience for every millisecond of audio asked for, which is the
        // harness's own 30 s at 60 s of audio and scales with a whole song rather than expiring
        // partway through one. Decoding runs about four milliseconds per second of audio on these
        // handsets, so this is a stall detector and not a race.
        private const val DECODE_BUDGET_MILLIS_PER_SECOND = 500L
        private const val MIN_SAMPLE_RATE = 8_000
        private const val MAX_SAMPLE_RATE = 96_000
        private val CHUNK_BYTES = SyncRenderer.FRAMES_PER_CHUNK * SyncRenderer.CHANNELS * BYTES_PER_SAMPLE

        /**
         * Decodes the prefix and converts it to the renderer's format, or throws with a code the
         * report can carry.
         *
         * The decoder's own output format is what the conversion is driven from, not the
         * container's: a rate or channel count other than the renderer's would still decode and
         * still play, at the wrong speed or as the wrong channels, while every timing number in
         * the run stayed sane.
         *
         * A source that already is 48 kHz stereo comes back out of [Resampler] as the same bytes.
         * That is what keeps every archived alignment run comparable with the ones after this.
         */
        fun open(file: File): FileChunkSource = read(file, whole = false)

        /**
         * The whole song rather than its first minute, refused outright if it will not fit.
         *
         * The harness keeps [open] and its 60 s for two reasons that outlive any one run: a report
         * says `sourceChunks` and the archive compares that number across 186 measurements, and a
         * 400 s run of a 214 s song would otherwise stop playing halfway through the schedule.
         * What a measurement is made against should not change because a product feature landed.
         */
        fun openWhole(file: File): FileChunkSource = read(file, whole = true)

        private fun read(file: File, whole: Boolean): FileChunkSource {
            if (!file.isFile) throw SourceUnusable("SOURCE_FILE_MISSING")
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
                val seconds = if (whole) allowedSeconds(format) else PREFIX_SECONDS
                extractor.selectTrack(track)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val prefix = decodePrefix(extractor, codec, seconds, declaredMicros(format))
                    val pcm = Resampler.toStereo(
                        prefix.pcm, prefix.sampleRate, prefix.channels, SyncRenderer.SAMPLE_RATE
                    )
                    if (pcm.size < CHUNK_BYTES) throw SourceUnusable("SOURCE_FILE_TOO_SHORT")
                    return FileChunkSource(pcm, pcm.size - pcm.size % CHUNK_BYTES)
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            } finally {
                runCatching { extractor.release() }
            }
        }

        private fun decodePrefix(
            extractor: MediaExtractor,
            codec: MediaCodec,
            seconds: Int,
            declaredMicros: Long?
        ): Prefix {
            // Measured in the renderer's format until the decoder says what it is actually
            // producing; a slower rate would otherwise cut the read short and a faster one would
            // run it long. The sync API answers before the first output buffer, so the provisional
            // figure never gets to bound anything.
            var limit = seconds * SyncRenderer.SAMPLE_RATE * SyncRenderer.CHANNELS * BYTES_PER_SAMPLE
            // Sized for the song rather than for the ceiling: a whole song is allowed anything
            // from under four minutes to eleven depending on its rate, and reserving the ceiling
            // for a three minute one costs tens of megabytes that the conversion is about to want.
            val decoded = ByteArrayOutputStream(expected(limit, declaredMicros))
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + seconds * DECODE_BUDGET_MILLIS_PER_SECOND * 1_000_000L
            var inputDone = false
            var sampleRate = 0
            var channels = 0
            while (decoded.size() < limit) {
                // A decoder that neither accepts input nor produces output would otherwise spin
                // here for the whole run; the run should fail before it plays silence instead.
                if (System.nanoTime() > deadline) throw SourceUnusable("SOURCE_FILE_DECODE_STALLED")
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
                    requireUsableFormat(output)
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    limit = seconds * sampleRate * channels * BYTES_PER_SAMPLE
                } else if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)!!
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    codec.releaseOutputBuffer(outputIndex, false)
                    decoded.write(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            // The sync API delivers INFO_OUTPUT_FORMAT_CHANGED before the first output buffer, so
            // this is unreachable in practice - which is exactly why it throws rather than assumes.
            if (sampleRate == 0) throw SourceUnusable("SOURCE_FILE_FORMAT_UNKNOWN")
            return Prefix(decoded.toByteArray(), sampleRate, channels)
        }

        /**
         * What the decoder is producing, once it is something the conversion can work from.
         *
         * The rate ceiling is memory, not principle: the prefix is held whole, and sixty seconds
         * of 96 kHz stereo is already 23 MB before anything is converted. Wider than stereo is
         * refused rather than folded down, the same way [CaptureChunkSource] refuses a mono
         * fallback - a surround mix guessed into two channels is not what the file said.
         */
        private fun requireUsableFormat(format: MediaFormat) {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            if (channels < 1 || channels > SyncRenderer.CHANNELS) {
                throw SourceUnusable("SOURCE_FILE_CHANNELS_UNUSABLE")
            }
            if (encoding != AudioFormat.ENCODING_PCM_16BIT ||
                sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE
            ) {
                throw SourceUnusable("SOURCE_FILE_FORMAT_UNUSABLE")
            }
        }

        /**
         * How much decoded audio to reserve room for, in the renderer's format.
         *
         * The renderer's rate rather than the source's, because the source's is not known until
         * the decoder answers. It over-reserves for a 44.1 kHz song, which is the harmless
         * direction: the alternative is the array doubling itself while it already holds tens of
         * megabytes.
         */
        private fun expected(limit: Int, declaredMicros: Long?): Int {
            if (declaredMicros == null || declaredMicros <= 0) return limit
            val seconds = (declaredMicros + 999_999L) / 1_000_000L
            val bytes = seconds * SyncRenderer.SAMPLE_RATE * SyncRenderer.CHANNELS * BYTES_PER_SAMPLE
            return minOf(limit.toLong(), maxOf(bytes, CHUNK_BYTES.toLong())).toInt()
        }

        /**
         * How long a whole song of this format may be, refusing this one outright if it is longer.
         *
         * The two answers are the same question asked of the same three declared fields, which is
         * why they are worked out in one place: a refusal at one length and a decode that stops at
         * another is a song that plays with its end missing and nothing on screen to say so.
         *
         * Refused here rather than after decoding, because the length costs nothing to read and
         * the alternative is someone watching a progress spinner for the whole of a song that was
         * never going to be played.
         */
        private fun allowedSeconds(format: MediaFormat): Int {
            val rate = declaredRate(format)
            val channels = declaredChannels(format)
            if (SourceBudget.tooLong(declaredMicros(format), rate, channels)) {
                throw SourceUnusable("SOURCE_FILE_TOO_LONG")
            }
            return SourceBudget.maxSeconds(rate, channels)
        }

        /**
         * The rate the container declares, or the dearest one allowed when it declares none.
         *
         * Silence is not a licence to assume the cheap case. 96 kHz is the most any usable format
         * can cost a second, so guessing it can only shorten what gets decoded, and shortening is
         * the direction that cannot run the heap out. A container this vague declares no duration
         * either, so nothing is refused on the strength of the guess - it only bounds the read.
         */
        private fun declaredRate(format: MediaFormat): Int =
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 } ?: MAX_SAMPLE_RATE
            } else {
                MAX_SAMPLE_RATE
            }

        /** The same, for the channel count. Zero means undeclared, which [SourceBudget] prices. */
        private fun declaredChannels(format: MediaFormat): Int =
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                0
            }

        /** What the container says the song lasts, in MediaFormat's own microseconds. */
        private fun declaredMicros(format: MediaFormat): Long? =
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null

        private class Prefix(val pcm: ByteArray, val sampleRate: Int, val channels: Int)
    }
}
