package com.soundmesh.core

import kotlin.math.roundToInt

/**
 * The same conversion [Resampler] does, fed in pieces instead of all at once.
 *
 * A whole song has to be in memory before [Resampler.toStereo] can be called, and that was the
 * only reason there was ever a ceiling on how long a song could be: the whole of it plus its
 * conversion had to fit in what a phone gives one app, which came to six minutes of 44.1 kHz
 * stereo. Handing the decoder's output over piece by piece removes the ceiling entirely - what is
 * held here is the filter's own reach plus whatever piece is in hand, whatever the song is.
 *
 * **The output has to be the same song, byte for byte.** Every archived alignment measurement was
 * made against audio that came out of [Resampler], and the numbers are compared across 186 of
 * them. Two things make a piece boundary somewhere that could quietly stop being true:
 *
 * - the filter reads sixteen samples either side of every output frame, so a frame near the end
 *   of a piece needs input that has not arrived yet. It is held back rather than filled in from
 *   the edge - edge clamping is right at the true end of a song and a fabricated sample anywhere
 *   else;
 * - the instant of output frame n is worked out from n itself, in whole numbers, rather than by
 *   adding a fractional step - which is what stops a long song from accumulating drift, and which
 *   means the counting must not restart at a piece boundary. Hence one running frame number for
 *   the whole song rather than one per piece.
 *
 * The filter bank itself is [Resampler]'s, not a second copy: a bank designed even slightly
 * differently would still pass every test that says a tone keeps its pitch. What is written twice
 * is the indexing loop, and that is what StreamingResamplerTest compares directly - five rate
 * pairs against eight piece sizes, every build.
 *
 * One producer, in order, exactly as the decoder hands audio over. Not thread-safe.
 */
class StreamingResampler(
    private val inRate: Int,
    private val inChannels: Int,
    private val outRate: Int
) {
    init {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
        require(inChannels == 1 || inChannels == 2) { "only mono and stereo are handled" }
    }

    /** Audio that already is the target format: bytes through, exactly as [Resampler] hands back. */
    private val passesThrough = inRate == outRate && inChannels == Resampler.CHANNELS

    /** Mono at the right rate: a copy into both channels, no filter and so no history. */
    private val shares = inRate == outRate && inChannels == 1

    private val bank: FloatArray? = if (inRate == outRate) null else Resampler.bank(inRate, outRate)

    /** Input samples, one array per source channel, holding absolute frames from [heldFrom]. */
    private val held: Array<FloatArray> = Array(inChannels) { FloatArray(0) }
    private var heldFrom = 0L

    /** Input frames that have arrived, and output frames already handed out. */
    private var arrived = 0L
    private var produced = 0L

    /**
     * The bytes of an input frame a piece ended in the middle of.
     *
     * A decoder's output buffers are whatever it felt like producing and owe nothing to frame
     * boundaries. Dropping the remainder per piece would lose part of a sample at every boundary,
     * quietly and by a different amount each time; the whole-buffer version only ever drops one
     * at the true end of the song, and so does this.
     */
    private var spare = ByteArray(0)

    /** What is being kept, in input frames. Read by the test that says a long song costs no more. */
    internal val heldFrames: Int get() = held[0].size

    /** Whatever of this piece can be converted without inventing samples that have not arrived. */
    fun write(pcm: ByteArray): ByteArray = when {
        passesThrough -> pcm
        shares -> shared(pcm)
        else -> converted(pcm)
    }

    /**
     * The tail, once the song is over: the frames that were waiting for input that will not come.
     *
     * Here and only here the filter reads past the end and holds the last sample, which is what
     * the whole-buffer version does at this same boundary. A trailing part of a frame is dropped,
     * also as it always was.
     */
    fun finish(): ByteArray {
        if (passesThrough || shares) return ByteArray(0)
        return render(arrived * outRate / inRate)
    }

    private fun shared(pcm: ByteArray): ByteArray {
        val bytes = if (spare.isEmpty()) pcm else spare + pcm
        val samples = bytes.size / BYTES_PER_SAMPLE
        spare = bytes.copyOfRange(samples * BYTES_PER_SAMPLE, bytes.size)
        val out = ByteArray(samples * Resampler.CHANNELS * BYTES_PER_SAMPLE)
        for (sample in 0 until samples) {
            val at = sample * BYTES_PER_SAMPLE
            val to = sample * Resampler.CHANNELS * BYTES_PER_SAMPLE
            out[to] = bytes[at]
            out[to + 1] = bytes[at + 1]
            out[to + 2] = bytes[at]
            out[to + 3] = bytes[at + 1]
        }
        return out
    }

    private fun converted(pcm: ByteArray): ByteArray {
        take(pcm)
        // The highest input frame the filter is allowed to reach is the newest one that has
        // arrived, less its own half width. Beyond that it would be reading the edge of a piece.
        val out = render(renderableUntil(arrived - 1 - Resampler.HALF))
        trim()
        return out
    }

    /** Unpacks whole input frames into the history, carrying any part-frame to the next piece. */
    private fun take(pcm: ByteArray) {
        val bytes = if (spare.isEmpty()) pcm else spare + pcm
        val frameBytes = inChannels * BYTES_PER_SAMPLE
        val frames = bytes.size / frameBytes
        spare = bytes.copyOfRange(frames * frameBytes, bytes.size)
        if (frames == 0) return
        for (channel in 0 until inChannels) {
            val kept = held[channel].size
            val grown = FloatArray(kept + frames)
            held[channel].copyInto(grown)
            for (frame in 0 until frames) {
                val at = (frame * inChannels + channel) * BYTES_PER_SAMPLE
                grown[kept + frame] =
                    (((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort()).toFloat()
            }
            held[channel] = grown
        }
        arrived += frames
    }

    /**
     * How many output frames could be produced if the filter may reach no further than [highest].
     *
     * Output frame n reads input around `floor(n * inRate / outRate)`, so what is wanted is the
     * count of n whose position lands at or below [highest] - that many input frames expressed in
     * output frames, rounded up because the last position is reached partway through.
     */
    private fun renderableUntil(highest: Long): Long {
        if (highest < 0) return produced
        return ((highest + 1) * outRate + inRate - 1) / inRate
    }

    private fun render(until: Long): ByteArray {
        if (until <= produced) return ByteArray(0)
        val last = arrived - 1
        val out = ByteArray(((until - produced) * Resampler.CHANNELS * BYTES_PER_SAMPLE).toInt())
        var at = 0
        var frame = produced
        while (frame < until) {
            // Whole numbers, from the frame number itself - see this class's own note on why the
            // counting may not restart at a piece boundary.
            val position = frame * inRate
            val base = position / outRate
            val row = ((position % outRate) * Resampler.PHASES / outRate).toInt() * Resampler.TAPS
            val start = base - Resampler.HALF + 1
            for (channel in 0 until Resampler.CHANNELS) {
                val source = held[if (inChannels == 1) 0 else channel]
                var sum = 0.0f
                for (tap in 0 until Resampler.TAPS) {
                    val index = (start + tap).coerceIn(0L, last) - heldFrom
                    sum += source[index.toInt()] * bank!![row + tap]
                }
                val value = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val to = at + channel * BYTES_PER_SAMPLE
                out[to] = (value and 0xFF).toByte()
                out[to + 1] = ((value shr 8) and 0xFF).toByte()
            }
            at += Resampler.CHANNELS * BYTES_PER_SAMPLE
            frame++
        }
        produced = until
        return out
    }

    /**
     * Drops the input the next output frame can no longer reach.
     *
     * Never below frame zero, because the filter reads past the start of the song and holds the
     * first sample there - so while that is still in reach there is nothing to drop.
     */
    private fun trim() {
        val keepFrom = maxOf(0L, produced * inRate / outRate - Resampler.HALF + 1)
        if (keepFrom <= heldFrom) return
        val drop = (keepFrom - heldFrom).toInt()
        for (channel in 0 until inChannels) {
            held[channel] = held[channel].copyOfRange(drop, held[channel].size)
        }
        heldFrom = keepFrom
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
    }
}
