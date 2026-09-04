package com.soundmesh.probe.sync

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.soundmesh.core.Resampler
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Decodes the head of an audio file once, up front, and hands it out as chunks on a loop.
 *
 * Decoding a bounded prefix into memory rather than streaming keeps every decoder call out of the
 * host's real-time loop: once [open] returns, [readChunk] is arithmetic over a byte array. The
 * prefix is long enough to judge two handsets by ear and short enough to hold - 60 s of 48 kHz
 * stereo is 11.5 MB.
 *
 * The seam where the prefix wraps is a waveform discontinuity, and it is left in on purpose. Both
 * handsets emit it from the same chunk at the same instant, so it is a shared transient - far
 * easier to hear misalignment in than sustained music is.
 */
class FileChunkSource private constructor(private val pcm: ByteArray) {
    private var position = 0

    /** The decoded prefix, in whole chunks - what the run report says it is playing. */
    val chunkCount: Int get() = pcm.size / CHUNK_BYTES

    /** One full chunk, wrapping back to the start of the prefix when it runs out. */
    fun readChunk(): ByteArray {
        val chunk = ByteArray(CHUNK_BYTES)
        var filled = 0
        while (filled < CHUNK_BYTES) {
            val take = minOf(CHUNK_BYTES - filled, pcm.size - position)
            pcm.copyInto(chunk, filled, position, position + take)
            filled += take
            position += take
            if (position >= pcm.size) position = 0
        }
        return chunk
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val PREFIX_SECONDS = 60
        private const val DEQUEUE_TIMEOUT_MICROS = 10_000L
        private const val DECODE_BUDGET_MILLIS = 30_000L
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
        fun open(file: File): FileChunkSource {
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
                extractor.selectTrack(track)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val prefix = decodePrefix(extractor, codec)
                    val pcm = Resampler.toStereo(
                        prefix.pcm, prefix.sampleRate, prefix.channels, SyncRenderer.SAMPLE_RATE
                    )
                    if (pcm.size < CHUNK_BYTES) throw SourceUnusable("SOURCE_FILE_TOO_SHORT")
                    return FileChunkSource(pcm.copyOf(pcm.size - pcm.size % CHUNK_BYTES))
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            } finally {
                runCatching { extractor.release() }
            }
        }

        private fun decodePrefix(extractor: MediaExtractor, codec: MediaCodec): Prefix {
            // Sixty seconds of the renderer's format until the decoder says what it is actually
            // producing; a slower rate would otherwise cut the prefix short and a faster one
            // would run it long. The sync API answers before the first output buffer, so the
            // provisional figure never gets to bound anything.
            var limit = PREFIX_SECONDS * SyncRenderer.SAMPLE_RATE * SyncRenderer.CHANNELS * BYTES_PER_SAMPLE
            val decoded = ByteArrayOutputStream(limit)
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + DECODE_BUDGET_MILLIS * 1_000_000L
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
                    limit = PREFIX_SECONDS * sampleRate * channels * BYTES_PER_SAMPLE
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

        private class Prefix(val pcm: ByteArray, val sampleRate: Int, val channels: Int)
    }
}
