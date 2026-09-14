package com.soundmesh.core

import kotlin.math.hypot

/**
 * Splits a spectrum into what is holding still and what just happened.
 *
 * Meant to be handed to [SlidingSpectrum] as the thing that changes each frame. What it does with
 * that frame is the median-filtering separation published by Fitzgerald in 2010 and refined by
 * Driedger and Mueller in 2014, and it is copied rather than invented on purpose. A separation has
 * no oracle - the only judge is an ear, and an ear reports "a bit disappointing" for every fault
 * there is. [Fourier] in the same package was written by hand for exactly the opposite reason.
 *
 * The idea in one sentence: on a picture of the sound with time across and pitch up, **a held note
 * is a horizontal line and a drum hit is a vertical one**. So take a median along each row and the
 * drums flatten out, leaving the notes; take a median along each column and the notes flatten out,
 * leaving the drums. A median rather than an average because a drum hit is an outlier, and an
 * average would carry a piece of it into the answer while a median steps straight over it.
 *
 * **The two halves add back up to the mix, exactly.** Each bin's share is `H/(H+P)` against
 * `P/(H+P)`, two numbers that sum to one whatever the sound is doing, so a handset asking for all
 * of both gets the mix it would have had if none of this existed - the same property [Crossover]
 * was built around, and for the same reason: it is what lets a listener wind the knob back.
 *
 * **The look back is backwards only.** The published method centres its median on the frame being
 * decided, which means knowing frames that have not been heard yet. Waiting for them would add
 * [ACROSS_TIME] / 2 frames of delay on top of the window, and the property being relied on survives
 * without it: a drum hit is an outlier against the frames before it just as surely as against the
 * frames around it.
 *
 * One of these per channel, for the same reason as [Crossover]: two channels are two sounds.
 */
class HarmonicPercussive(
    val size: Int,
    private val acrossTime: Int = ACROSS_TIME,
    private val acrossBins: Int = ACROSS_BINS
) {
    init {
        require(size >= 4 && (size and (size - 1)) == 0) { "a window is a power of two: $size" }
        require(acrossTime >= 1 && acrossTime % 2 == 1) {
            "an odd number of frames has a middle: $acrossTime"
        }
        require(acrossBins >= 1 && acrossBins % 2 == 1) {
            "an odd number of bins has a middle: $acrossBins"
        }
    }

    /**
     * Only up to halfway.
     *
     * The sound going in is real, so the top half of every spectrum is the bottom half reflected,
     * and so is everything computed from it. Deciding the top half separately would cost twice the
     * medians to reach the same answer; it is copied across at the end instead.
     */
    private val bins = size / 2 + 1

    private val past = Array(acrossTime) { DoubleArray(bins) }
    private var newest = -1
    private var heard = 0

    private val steady = DoubleArray(bins)
    private val sudden = DoubleArray(bins)
    private val gathered = DoubleArray(maxOf(acrossTime, acrossBins))

    private var harmonicShare = 1.0
    private var percussiveShare = 1.0

    /**
     * How much of each half this handset is playing.
     *
     * Not capped at one. Both at one is the mix untouched; one at zero is that half alone; and a
     * number above one is a half turned up, which is a thing a room might want and not a thing this
     * class has any business refusing.
     */
    fun keep(harmonic: Double, percussive: Double) {
        require(harmonic >= 0.0 && harmonic.isFinite()) { "a share is not negative: $harmonic" }
        require(percussive >= 0.0 && percussive.isFinite()) { "a share is not negative: $percussive" }
        harmonicShare = harmonic
        percussiveShare = percussive
    }

    /** Takes one frame as [SlidingSpectrum] hands it over, and leaves this handset's part behind. */
    fun change(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "this is built for $size bins, not ${re.size}" }
        remember(re, im)
        alongEachRow()
        alongEachColumn()
        for (bin in 0 until bins) {
            val share = shareOf(bin)
            re[bin] *= share
            im[bin] *= share
            // The reflection. Bin zero and the halfway bin are their own mirror and are skipped.
            val mirror = size - bin
            if (mirror in bins until size) {
                re[mirror] *= share
                im[mirror] *= share
            }
        }
    }

    private fun remember(re: DoubleArray, im: DoubleArray) {
        newest = (newest + 1) % acrossTime
        val now = past[newest]
        for (bin in 0 until bins) now[bin] = hypot(re[bin], im[bin])
        if (heard < acrossTime) heard++
    }

    /** The median along time at each pitch: what was already there before this frame arrived. */
    private fun alongEachRow() {
        for (bin in 0 until bins) {
            for (back in 0 until heard) {
                gathered[back] = past[Math.floorMod(newest - back, acrossTime)][bin]
            }
            steady[bin] = middleOf(heard)
        }
    }

    /**
     * The median across pitch within this frame: what is happening at every pitch at once.
     *
     * The ends are held rather than wrapped. Wrapping would let the lowest bins be decided by the
     * highest ones, which are a different sound entirely; repeating the edge says only that the
     * neighbourhood runs out, which is true.
     */
    private fun alongEachColumn() {
        val now = past[newest]
        val reach = acrossBins / 2
        for (bin in 0 until bins) {
            for (step in 0 until acrossBins) {
                gathered[step] = now[(bin + step - reach).coerceIn(0, bins - 1)]
            }
            sudden[bin] = middleOf(acrossBins)
        }
    }

    /**
     * What of this bin survives.
     *
     * Squared, which is what makes the split decisive rather than a gentle lean: a bin the two
     * estimates disagree about by a factor of two is handed over four to one. Silence is the one
     * case with no answer at all, and there the harmonic share is used - it changes nothing, since
     * there is nothing there to scale, and it avoids dividing nothing by nothing.
     */
    private fun shareOf(bin: Int): Double {
        val held = steady[bin] * steady[bin]
        val hit = sudden[bin] * sudden[bin]
        val both = held + hit
        if (both <= 0.0) return harmonicShare
        return (harmonicShare * held + percussiveShare * hit) / both
    }

    /**
     * The middle of the first [count] things gathered, sorting them where they lie.
     *
     * Insertion sort because [count] is seventeen: nothing cleverer wins at that length, and this
     * runs a couple of thousand times per frame on an audio thread, so it allocates nothing.
     */
    private fun middleOf(count: Int): Double {
        for (at in 1 until count) {
            val carried = gathered[at]
            var behind = at - 1
            while (behind >= 0 && gathered[behind] > carried) {
                gathered[behind + 1] = gathered[behind]
                behind--
            }
            gathered[behind + 1] = carried
        }
        return gathered[count / 2]
    }

    private companion object {
        /**
         * How many frames back the held notes are looked for over.
         *
         * Seventeen frames at a quarter-window hop is about four windows of history - long enough
         * that a drum hit is one value in seventeen and cannot move the middle one, short enough
         * that a note changing is followed rather than averaged away. The figure is the one
         * librosa uses.
         */
        const val ACROSS_TIME = 17

        /** The same, across pitch. Odd, so that the neighbourhood has a middle to be. */
        const val ACROSS_BINS = 17
    }
}
