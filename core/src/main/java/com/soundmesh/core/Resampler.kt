package com.soundmesh.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Converts decoded PCM to the one format the renderer plays: interleaved 16 bit little endian
 * stereo at a fixed rate.
 *
 * This runs once, off the real time path, over a bounded prefix that is already in memory, so it
 * can afford a windowed sinc rather than the linear interpolation that a per-chunk converter would
 * be pushed towards. What it buys is the top of the band: linear interpolation leaves an image of
 * a 10 kHz tone only about 18 dB down, and music is where that is audible.
 *
 * Audio that already is the target format is returned as the same array. That is not an
 * optimisation - it is the guarantee that adding this step changed nothing for the 48 kHz sources
 * every archived measurement was made with.
 */
object Resampler {
    /**
     * [pcm] read as [inChannels] interleaved 16 bit samples at [inRate], returned as stereo at
     * [outRate]. Mono is shared by both output channels; anything wider is the caller's to refuse.
     */
    fun toStereo(pcm: ByteArray, inRate: Int, inChannels: Int, outRate: Int): ByteArray {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
        require(inChannels == 1 || inChannels == 2) { "only mono and stereo are handled" }
        if (inRate == outRate && inChannels == CHANNELS) return pcm
        val inFrames = pcm.size / (inChannels * BYTES_PER_SAMPLE)
        if (inFrames == 0) return ByteArray(0)
        if (inRate == outRate) return share(pcm, inFrames)

        val outFrames = (inFrames.toLong() * outRate / inRate).toInt()
        val out = ByteArray(outFrames * CHANNELS * BYTES_PER_SAMPLE)
        val bank = bank(inRate, outRate)
        for (channel in 0 until CHANNELS) {
            val source = channel(pcm, inChannels, if (inChannels == 1) 0 else channel, inFrames)
            resample(source, out, channel, outFrames, inRate, outRate, bank)
        }
        return out
    }

    /** Mono at the right rate: every sample belongs in both channels, unaltered. */
    private fun share(pcm: ByteArray, frames: Int): ByteArray {
        val out = ByteArray(frames * CHANNELS * BYTES_PER_SAMPLE)
        for (frame in 0 until frames) {
            val at = frame * BYTES_PER_SAMPLE
            val to = frame * CHANNELS * BYTES_PER_SAMPLE
            out[to] = pcm[at]
            out[to + 1] = pcm[at + 1]
            out[to + 2] = pcm[at]
            out[to + 3] = pcm[at + 1]
        }
        return out
    }

    private fun channel(pcm: ByteArray, inChannels: Int, channel: Int, frames: Int): FloatArray {
        val samples = FloatArray(frames)
        for (frame in 0 until frames) {
            val at = (frame * inChannels + channel) * BYTES_PER_SAMPLE
            samples[frame] = (((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort()).toFloat()
        }
        return samples
    }

    private fun resample(
        source: FloatArray,
        out: ByteArray,
        channel: Int,
        outFrames: Int,
        inRate: Int,
        outRate: Int,
        bank: FloatArray
    ) {
        val last = source.size - 1
        for (frame in 0 until outFrames) {
            // Where this output frame falls in the input, kept in whole numbers so a long prefix
            // cannot accumulate the drift that repeated addition of a fractional step would.
            val position = frame.toLong() * inRate
            val base = (position / outRate).toInt()
            val phase = ((position % outRate) * PHASES / outRate).toInt()
            val row = phase * TAPS
            val start = base - HALF + 1
            var sum = 0.0f
            for (tap in 0 until TAPS) {
                // The prefix is read past both ends by half a filter. Holding the edge sample
                // rather than reading silence keeps the wrap seam the transient it already was.
                sum += source[(start + tap).coerceIn(0, last)] * bank[row + tap]
            }
            val value = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val at = (frame * CHANNELS + channel) * BYTES_PER_SAMPLE
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }

    /**
     * [PHASES] shifted copies of one low pass, so the inner loop is a dot product and never a
     * transcendental. Rounding an output instant to the nearest of them misplaces it by at most
     * half a phase - 1/1024 of a sample, some twenty nanoseconds, against a renderer that counts
     * in whole frames.
     *
     * Coming down from a higher rate the cutoff follows the output, which is what stops content
     * that will not fit from folding back in. Going up it stays just under the input's own
     * ceiling: everything the source actually carries is below it, and the room left over is
     * what lets a filter this short stay flat across the band.
     */
    internal fun bank(inRate: Int, outRate: Int): FloatArray {
        val cutoff = ROLLOFF * minOf(1.0, outRate.toDouble() / inRate)
        val bank = FloatArray(PHASES * TAPS)
        val row = DoubleArray(TAPS)
        for (phase in 0 until PHASES) {
            val fraction = phase.toDouble() / PHASES
            var sum = 0.0
            for (tap in 0 until TAPS) {
                val x = fraction + HALF - 1 - tap
                row[tap] = cutoff * sinc(cutoff * x) * blackman(x / HALF)
                sum += row[tap]
            }
            // Normalised per phase so silence stays silent and a steady level stays steady,
            // whichever phase each output frame happens to land on.
            for (tap in 0 until TAPS) bank[phase * TAPS + tap] = (row[tap] / sum).toFloat()
        }
        return bank
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun blackman(u: Double): Double = 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2.0 * PI * u)

    // Shared with StreamingResampler, which renders this same filter one piece at a time. The
    // bank in particular: a bank designed even slightly differently would still pass every test
    // that says a tone keeps its pitch, so there must not be a second one.
    internal const val CHANNELS = 2
    private const val BYTES_PER_SAMPLE = 2
    internal const val TAPS = 32
    internal const val HALF = TAPS / 2
    internal const val PHASES = 512
    private const val ROLLOFF = 0.90
}
