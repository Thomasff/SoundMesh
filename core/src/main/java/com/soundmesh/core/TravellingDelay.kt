package com.soundmesh.core

import kotlin.math.floor

/**
 * One handset's output, held back by an amount that is allowed to move while it plays.
 *
 * Every other delay in this project is a constant: [SpatialField.arrivalDelayNanosFor] works out
 * how far behind a handset stands and holds it back by that, once, for as long as the room keeps
 * its shape. That one is applied to the clock - see SyncRenderer's playHostNanos - because a
 * constant can be. This one cannot: a delay that changes has to change **inside** the audio, one
 * frame at a time, or the change is a step in the waveform and a step in a waveform is a click.
 *
 * Two things want it, and they are the same arithmetic pointed at different numbers.
 *
 * **A source that is really moving.** A sound coming towards you arrives earlier and earlier;
 * that is not a metaphor for a shrinking delay line, it is one. Sweeping a handset's delay is
 * therefore the only thing in this project that produces a genuine Doppler shift, because the
 * pitch shift falls out of the arithmetic rather than being added on top: reading a buffer at a
 * rate that is not one is a resampling, and a resampling is a transposition. At the rates the
 * room actually asks for - see SpatialField's travel and shimmer - that is under 1%, which is
 * what a source moving at walking pace does.
 *
 * **A room that will not collapse into one point.** [Decorrelator] already answers this by making
 * the handsets' waveforms differ, and what it cannot do is make them differ *differently over
 * time*. A static difference is heard once and then becomes the room; something that moves goes
 * on being heard. A few milliseconds of slow independent wander per handset is what a chorus is,
 * and a chorus spread across a room rather than across two speakers has nowhere to collapse to.
 *
 * ## The slew limit is the whole safety argument
 *
 * The delay is asked for, never set. Each frame it moves at most [MAX_SLEW_SAMPLES] towards what
 * was asked, which bounds the resampling ratio and therefore bounds the pitch: 1/64 is 1.6%,
 * about a quarter tone, and it is reached only by a change no listener made - a mode switching, a
 * rule arriving, a handset appearing in the drawing and shifting everybody's slot. The movements
 * the features themselves ask for are an order of magnitude slower than the cap, so they pass
 * through it untouched and the cap is invisible to them; it is there for the discontinuities.
 *
 * It buys one more thing, for free. A fresh line holds silence, so a delay it has not yet been
 * fed enough frames to satisfy would read that silence out. Starting at zero and rising by at most
 * 1/64 of a frame per frame, the delay after n frames is at most n/64 - and the two frames an
 * interpolation reads are at n/64 and n/64 + 1, both of which are inside the n + 1 frames that
 * have been written. So there is no priming case and no first-chunk dropout to special case: the
 * line is correct from its first frame. That the margin is exactly one frame is why [step] moves
 * the delay after producing a frame rather than before.
 *
 * ## What it is not
 *
 * Not a spatial cue on its own. Delaying one handset past about a millisecond hands the image
 * outright to whichever one is earlier, whatever the levels say - that is the precedence effect,
 * and docs/feasibility-results/delay-as-a-spatial-cue.md is the 09-14 argument for why steering
 * with it produces a sound that jumps between handsets rather than travels between them. Nothing
 * here refutes that. What this class provides is the machinery; which numbers to feed it, and
 * which of those are worth listening to, is [SpatialField]'s question and then a person's.
 */
class TravellingDelay(sampleRate: Int, longestNanos: Long = LONGEST_NANOS) {
    /** The furthest back this can hold a frame. Anything asked for beyond it is clamped, not refused. */
    val longestSamples: Double

    private val size: Int
    private val leftLine: DoubleArray
    private val rightLine: DoubleArray

    /** Where the next frame is written, so the newest one written sits at `at - 1`. */
    private var at = 0

    private var held = 0.0

    /** The left channel of the frame leaving now, which was written [heldSamples] frames ago. */
    var left = 0.0
        private set

    /** The right channel of the same frame. */
    var right = 0.0
        private set

    /** Where the delay actually is, which is where it has been allowed to get to. */
    val heldSamples: Double get() = held

    init {
        require(sampleRate > 0) { "nanoseconds need a rate to become frames: $sampleRate" }
        require(longestNanos > 0L) { "a delay line with no length holds nothing: $longestNanos" }
        longestSamples = samplesFor(longestNanos, sampleRate)
        // Two frames of margin: a fractional delay reads the frame either side of where it lands,
        // and the newest frame written is already one step behind the write cursor.
        size = floor(longestSamples).toInt() + 2
        leftLine = DoubleArray(size)
        rightLine = DoubleArray(size)
    }

    /**
     * Takes one frame in, moves the delay towards [wantedSamples], and puts one frame out.
     *
     * The frame that comes out is the one that went in [heldSamples] frames ago, read with linear
     * interpolation between the two frames it falls between. Linear rather than anything longer
     * because the error it makes is a gentle loss at the top of the band that varies with where
     * between two frames the delay currently sits - which is inaudible at these depths and is
     * exactly the colouration every hardware chorus has ever had - while a longer interpolator
     * would be a filter whose response changes as the delay moves, which is a harder thing to
     * reason about for no gain anybody could hear.
     *
     * Exact at a delay of zero: the frame just written is read straight back, bit for bit, with no
     * interpolation and no rounding. That matters because zero is what every handset is asked for
     * whenever neither feature is switched on, and a knob at off has to cost nothing.
     */
    fun step(inLeft: Double, inRight: Double, wantedSamples: Double) {
        leftLine[at] = inLeft
        rightLine[at] = inRight
        val newest = at
        at = if (at + 1 == size) 0 else at + 1

        val whole = floor(held).toInt()
        val fraction = held - whole
        val near = index(newest - whole)
        if (fraction == 0.0) {
            left = leftLine[near]
            right = rightLine[near]
        } else {
            val far = index(newest - whole - 1)
            left = leftLine[near] + (leftLine[far] - leftLine[near]) * fraction
            right = rightLine[near] + (rightLine[far] - rightLine[near]) * fraction
        }

        // Moved after the frame has been produced, not before it. The very first frame is then
        // read at a delay of exactly zero - the frame just written, no interpolation - and the
        // delay only starts moving once there is a second frame for it to interpolate against.
        // With the move ahead of the read the first frame came out 1.6% short, because a delay of
        // 1/64 reads the newest frame and the one before it, and on frame zero the one before it
        // is the silence the buffer was allocated full of. One frame, inaudible, and wrong for a
        // reason that would have been very hard to find later.
        val wanted = wantedSamples.coerceIn(0.0, longestSamples)
        held += (wanted - held).coerceIn(-MAX_SLEW_SAMPLES, MAX_SLEW_SAMPLES)
    }

    private fun index(raw: Int): Int {
        val wrapped = raw % size
        return if (wrapped < 0) wrapped + size else wrapped
    }

    companion object {
        /**
         * How far a delay may move per frame: 1/64 of a frame, which is 1.6% and about a quarter
         * tone of transposition while it is moving.
         *
         * Not a taste setting. It is the bound on what a discontinuity is allowed to sound like,
         * and the two things it protects against pull opposite ways: too fast and a rule change is
         * a click or a chirp, too slow and a fifteen millisecond correction takes a second and a
         * half to arrive, during which the room is playing a delay nobody asked for. At 1/64 that
         * correction takes 0.6 s and sounds like a tape wobble, which is the failure this feature
         * should have - the one that reads as the sound moving rather than as the app breaking.
         */
        const val MAX_SLEW_SAMPLES = 1.0 / 64.0

        /**
         * The longest delay any of this can ask for: thirty milliseconds.
         *
         * Past about thirty the ear stops hearing a delayed copy as part of the same sound and
         * starts hearing a second one, which is an echo and is a different feature nobody asked
         * for. Everything here works far below it - the wander is single milliseconds and even
         * the travel cap is half of this - so the number is a guard rather than a range, in the
         * same spirit as [SpatialField.MAX_ARRIVAL_DELAY_NANOS]: it decides what a wrong input
         * can do, not what a right one does.
         */
        const val LONGEST_NANOS = 30_000_000L

        /** Nanoseconds as a fractional count of frames. Fractional on purpose - see [step]. */
        fun samplesFor(nanos: Long, sampleRate: Int): Double =
            nanos.toDouble() * sampleRate / 1_000_000_000.0
    }
}
