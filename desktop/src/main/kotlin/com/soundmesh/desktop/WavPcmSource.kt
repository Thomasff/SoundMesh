package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.Resampler
import com.soundmesh.core.TonePcmSource
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException

/**
 * A file on this machine, decoded up front and handed out by absolute frame index, on a loop.
 *
 * The same shape as [TonePcmSource] and for the same reason: the host asks for a frame range and
 * says nothing about where in the file it is, so the answer has to be arithmetic over an array
 * rather than a position that a caller could get out of step with. Decoding up front also keeps
 * every decoder call out of the streaming loop.
 *
 * **WAV only, deliberately.** What this exists to do is let a run play something with a tune in it
 * instead of a 480 Hz tone, and a WAV does that with nothing bundled and nothing to go stale. When
 * the product has to open whatever a person actually has, the decoder to reach for is the one
 * Windows already ships - Media Foundation, through the same foreign-function route as
 * [WasapiRenderer] - and only the [open] half of this changes.
 *
 * **The loop's seam is left in on purpose**, which is the handset source's reasoning carried over:
 * both machines emit that transient from the same chunk at the same instant, and a shared transient
 * is far easier to hear misalignment in than sustained music is.
 */
class WavPcmSource private constructor(private val pcm: ByteArray) {

    /**
     * Frames in the loop, always a whole number of chunks.
     *
     * The tail is dropped rather than played, so the wrap always lands on a chunk boundary and the
     * seam is at the start of a chunk both machines were sent. A wrap in the middle of a chunk
     * would put that transient at a different offset in every run.
     */
    val frameCount: Int get() = pcm.size / FRAME_BYTES

    /**
     * [frames] frames of stereo starting at [frameIndex], wrapping as many times as it has to.
     *
     * Any index answers, including one a thousand loops past the end: a run is as long as somebody
     * asked for and the file is as long as it is.
     */
    fun fill(frameIndex: Long, frames: Int): ByteArray {
        val out = ByteArray(frames * FRAME_BYTES)
        var filled = 0
        // Modulo before the loop rather than inside it: frameIndex is absolute and a long, and a
        // run that has been going for an hour is still only a few frames into the file.
        var at = (frameIndex.mod(frameCount.toLong())).toInt() * FRAME_BYTES
        while (filled < out.size) {
            val take = minOf(out.size - filled, pcm.size - at)
            pcm.copyInto(out, filled, at, at + take)
            filled += take
            at += take
            if (at >= pcm.size) at = 0
        }
        return out
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNELS = 2
        private const val FRAME_BYTES = CHANNELS * BYTES_PER_SAMPLE

        /**
         * Reads [file] and converts it to the one format everything downstream plays.
         *
         * Two steps, and which does what matters. The sound library normalises how a sample is
         * written - bit depth, endianness, whether eight bit means unsigned - because that is
         * container detail nobody downstream should know about. The rate and the channel count go
         * through [Resampler], the same call the handset makes, so a desktop run and a handset run
         * are made against the same conversion and stay comparable.
         */
        fun open(file: File): WavPcmSource {
            require(file.isFile) { "no file at ${file.absolutePath}" }
            val pcm = try {
                AudioSystem.getAudioInputStream(file).use { opened ->
                    val source = opened.format
                    require(source.channels == 1 || source.channels == 2) {
                        "${source.channels} channels is more than a stereo pipeline can place; " +
                            "export it as mono or stereo"
                    }
                    val samples = AudioSystem.getAudioInputStream(signedSixteenBit(source), opened)
                        .use { it.readBytes() }
                    // Rate and channels are handed on as the file had them. Asking the sound
                    // library to convert those too would put two resamplers in one product, and
                    // only one of them is the one every archived measurement was made with.
                    Resampler.toStereo(
                        samples, source.sampleRate.toInt(), source.channels, TonePcmSource.SAMPLE_RATE
                    )
                }
            } catch (e: UnsupportedAudioFileException) {
                throw IllegalArgumentException(
                    "${file.name} is not a sound file this can open - it reads WAV, AIFF and AU; " +
                        "export the song as a 48 kHz stereo WAV",
                    e
                )
            }
            val whole = pcm.size - pcm.size % (ChunkCodec.FRAMES_PER_CHUNK * FRAME_BYTES)
            require(whole > 0) {
                "${file.name} is shorter than the 20 ms a chunk carries, so there is nothing to " +
                    "loop; the shortest usable file is ${ChunkCodec.FRAMES_PER_CHUNK} frames"
            }
            return WavPcmSource(pcm.copyOf(whole))
        }

        /**
         * [source] with the samples written the way everything downstream reads them, and the rate
         * and channel count untouched.
         *
         * That split is the point: bit depth, endianness and "eight bit means unsigned" are
         * container detail, and this is the one place in the product that should have to know it.
         */
        private fun signedSixteenBit(source: AudioFormat) = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            source.sampleRate,
            Short.SIZE_BITS,
            source.channels,
            source.channels * BYTES_PER_SAMPLE,
            source.sampleRate,
            false
        )
    }
}
