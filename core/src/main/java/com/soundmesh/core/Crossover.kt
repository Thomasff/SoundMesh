package com.soundmesh.core

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Splits a stereo stream into a low half and, by subtraction, a high one.
 *
 * The first thing in this audio path that remembers anything. Placing a sound and folding the
 * channels together are both arithmetic on the sample in hand, so they need no state and every
 * handset can evaluate them independently; a filter answers with what it has already heard, and
 * that changes what a chunk boundary is. Between two chunks the state has to survive, or every
 * chunk starts cold and the room ticks fifty times a second.
 *
 * One pole per stage, cascaded, and the high half is never computed here - it is whatever is left
 * when the low half is taken away from the sample. That definition is why the two halves add back
 * up to the original **exactly**, sample for sample, whatever the filter does: it is a subtraction,
 * not a second filter that has to be the first one's mirror. A pair of proper filters would be
 * steeper and would not have that property, and the property is the one worth keeping - it is what
 * makes the separation knob wind back to the mix the room was already playing.
 *
 * Two poles is 12 dB per octave, which is a real split rather than a tilt. What it costs is about
 * a decibel of lift in the high half around the crossover, because the low half lags in phase there
 * and the residual is a little larger than the part it is standing in for. When both handsets are
 * playing that cancels exactly. Whether the slope is right is a question for a listener.
 */
class Crossover {
    private val left = DoubleArray(POLES)
    private val right = DoubleArray(POLES)

    /** The low half of [sample] on the left channel, given the last one this channel was handed. */
    fun lowLeft(sample: Double, coefficient: Double): Double = advance(left, sample, coefficient)

    /** The low half of [sample] on the right channel. Separate state: the channels are separate sounds. */
    fun lowRight(sample: Double, coefficient: Double): Double = advance(right, sample, coefficient)

    private fun advance(state: DoubleArray, sample: Double, coefficient: Double): Double {
        var carried = sample
        for (pole in state.indices) {
            state[pole] += coefficient * (carried - state[pole])
            carried = state[pole]
        }
        return carried
    }

    companion object {
        /**
         * Four, because two was heard and was not enough.
         *
         * The comment here used to say two was "steep enough to hear as two different sounds
         * rather than as one slightly dulled one". On 2026-09-10 a listener put both handsets on
         * the low half and dragged the split from 100 Hz to 5000 Hz across one song. At every
         * setting they heard the whole song, darker - their words were "the same sound, a bit
         * muffled" - and at 100 Hz they could still follow the melody, three octaves above the
         * split. Two poles leave a tone two octaves up only 24.6 dB down; four leave it 35.3.
         *
         * Not more than four: each pole costs phase, and what the split is for is handing a
         * listener the band their own handset could never carry - not a laboratory boundary.
         */
        const val POLES = 4

        /**
         * How far each pole moves toward the sample it is handed, for a crossover at [hz].
         *
         * Coerced rather than refused above half the sample rate. A crossover the rate cannot carry
         * is a slider dragged to its end, not a corrupt message, and the honest answer to it is the
         * highest split this rate has - which is a coefficient of one, the filter passing everything
         * straight through and the high half falling silent.
         */
        fun coefficientFor(hz: Double, sampleRate: Int): Double {
            require(hz > 0.0) { "a crossover is a frequency: $hz" }
            require(sampleRate > 0) { "samples need a rate: $sampleRate" }
            return (1.0 - exp(-2.0 * Math.PI * hz * PER_POLE / sampleRate)).coerceIn(0.0, 1.0)
        }

        /**
         * Each pole's own corner, as a multiple of the frequency on the slider.
         *
         * [POLES] identical one-pole sections are each 3 dB down at their own corner, so putting
         * them all at the slider's frequency would leave the low half 3xPOLES dB down there - the
         * split itself would slide downward every time the skirt was steepened, and dragging to
         * 800 would sound like 515 used to. Pushing each corner up by this factor keeps half the
         * tone surviving at the labelled frequency, which is the convention a listener spent an
         * evening calibrating their ear against.
         *
         * At POLES = 2 this is exactly 1.0, so the arrangement it replaces is the case it covers.
         */
        private val PER_POLE = 1.0 / sqrt(2.0.pow(2.0 / POLES) - 1.0)
    }
}
