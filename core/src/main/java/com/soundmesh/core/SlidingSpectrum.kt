package com.soundmesh.core

import kotlin.math.PI
import kotlin.math.cos

/**
 * A stream taken apart into overlapping windows, handed over to be changed, and put back together.
 *
 * The second thing in this audio path that remembers anything, and it remembers far more than
 * [Crossover] does: a whole window of what it has heard, a whole window of what it has decided, and
 * where it is between frames. Everything upstream of here is arithmetic on the sample in hand.
 *
 * **It hands back what it was given, delayed.** With [held] samples of wait and nothing changed in
 * between, the sound that comes out is the sound that went in, to rounding - that is what the hop
 * and the windowing below are chosen for, and it is the only property of this class worth testing
 * directly. Everything a listener would complain about if it were wrong (a hum at the frame rate, a
 * sound that beats, edges on every note) shows up as a failure of exactly that one check.
 *
 * The window is a raised cosine and it is applied **twice**, once before the transform and once
 * after. Windowing on the way out is what stops a changed spectrum from putting a step at every
 * frame edge: a frame whose contents were altered no longer joins up with its neighbours, and
 * without the second window that join is a click fifty times a second. Four of these windows,
 * squared, overlapping by three quarters, add up to exactly 1.5 everywhere - which is why the hop
 * is a quarter of the window and not something a caller gets to choose. At any other hop they add
 * up to something that ripples, and the ripple is heard as a tone at the frame rate that nobody
 * would ever trace back to a window length.
 *
 * Mono. Two channels means two of these, for the same reason [Crossover] keeps two sets of poles:
 * they are two different sounds and neither one's history belongs to the other.
 *
 * The wait is real and it is the same for everybody. [held] samples at 48 kHz is 43 ms for a 2048
 * window, and the room only stays together because **every handset holds back the same number of
 * samples** - this runs identically on each of them, so it does. A version of this where the delay
 * depended on anything local would put back exactly the error the whole project exists to remove.
 */
class SlidingSpectrum(
    val size: Int,
    private val change: (re: DoubleArray, im: DoubleArray) -> Unit = { _, _ -> }
) {
    init {
        require(size >= MIN_SIZE && (size and (size - 1)) == 0) {
            "a window has to be a power of two of at least $MIN_SIZE: $size"
        }
    }

    /**
     * How far behind the sound coming out is from the sound going in.
     *
     * One window less one sample: a window cannot be transformed until its last sample has
     * arrived, and everything in it is waiting on that sample. Nothing is added on top of it -
     * the last frame that touches an output sample is the one that starts on it.
     */
    val held: Int = size - 1

    private val hop = size / OVERLAP_COUNT
    private val fourier = Fourier(size)

    /** The raised cosine, periodic rather than symmetric, because these have to tile. */
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / size) }

    /** The last [size] samples handed in, and what has been decided about them so far. */
    private val heard = DoubleArray(size)
    private val decided = DoubleArray(size)

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    private var count = 0L
    private var sinceFrame = 0

    /**
     * Takes one sample and returns the one that is now finished, which is [held] samples older.
     *
     * Returns zero until a whole window has been heard. That is not a special case in the
     * arithmetic - the window before the stream started is silence, and silence is what the
     * buffers already hold - it is only that nothing has finished yet.
     */
    fun pass(sample: Double): Double {
        heard[(count % size).toInt()] = sample
        count++
        if (++sinceFrame == hop) {
            sinceFrame = 0
            foldInAFrame()
        }
        // The slot now holding the oldest thing in the ring is the sample every frame that could
        // touch it has already touched. Read and cleared in one go, because the same slot is what
        // the next window's worth of frames will add into.
        val at = (count % size).toInt()
        val finished = decided[at]
        decided[at] = 0.0
        return if (count >= size) finished / OVERLAP_SUM else 0.0
    }

    /** Drops everything heard and everything decided, leaving this as it was built. */
    fun forget() {
        heard.fill(0.0)
        decided.fill(0.0)
        count = 0
        sinceFrame = 0
    }

    /**
     * Transforms the window that just became whole, offers it to be changed, and adds it back in.
     *
     * The window runs from `count - size` to `count`, which for the first few frames of a stream
     * reaches back before it began. Those samples read as silence out of a ring nobody has written
     * yet, which is what they are.
     */
    private fun foldInAFrame() {
        val start = count - size
        for (at in 0 until size) {
            re[at] = heard[Math.floorMod(start + at, size.toLong()).toInt()] * window[at]
            im[at] = 0.0
        }
        fourier.forward(re, im)
        change(re, im)
        fourier.inverse(re, im)
        for (at in 0 until size) {
            decided[Math.floorMod(start + at, size.toLong()).toInt()] += re[at] * window[at]
        }
    }

    private companion object {
        /** How many windows cover any one sample. Fixed, because it is what makes them add to a constant. */
        const val OVERLAP_COUNT = 4

        /** What [OVERLAP_COUNT] raised cosines, squared, add up to. */
        const val OVERLAP_SUM = 1.5

        /** Smaller than this and there is no hop left to slide by. */
        const val MIN_SIZE = 4
    }
}
