package com.soundmesh.core

import kotlin.math.exp
import kotlin.math.pow

/**
 * Takes the top off what a handset plays, by however far off the source has been put.
 *
 * A distant sound is duller, and everybody knows it, but the usual reason given for it is wrong at
 * the distances this room works in. Air does absorb the top, and over a concert hall it matters; at
 * ten kilohertz it is about a decibel per ten metres, so across a living room - two metres to eight
 * - it is a few tenths of a decibel and nobody has ever heard it. **Air is not why a sound across
 * the room is duller.**
 *
 * What is, is the reverberation. A source close to a listener reaches them mostly as direct sound;
 * a source further off reaches them mostly as reflections, and every reflection has left some of
 * its top on a wall, a curtain or a sofa. Ordinary furnishings absorb far more above a couple of
 * kilohertz than below, so the reverberant field is a duller copy of the direct one, and moving
 * away is being handed more of the duller copy. That is the whole mechanism.
 *
 * So this is **a stand-in for a reverberation this project has not built yet**, not a cue of its
 * own, and that is the honest status of both numbers in it. The real model wants the room's volume
 * and its reverberation time, and nothing here measures either - the same stopping point, for the
 * same reason, as [SpatialField.RECEDE_ROLLOFF]. When the reverberation is built this should be
 * reconsidered rather than kept beside it, or the top will be taken off twice.
 *
 * A shelf rather than a low pass, which is the shape the mechanism asks for. A low pass goes on
 * falling for ever; what a room does is take a roughly fixed amount off everything above where its
 * surfaces start absorbing, and leave it there. One pole, so the corner is gentle: a steeper skirt
 * would be a tone control being heard rather than a room.
 *
 * **It can only ever make a sample smaller.** A one-pole low pass of a bounded signal is bounded by
 * that same signal's peak, so the crossfade below is between the sample and something no larger -
 * this cannot hand anything downstream a value bigger than what it was given. That is written down
 * because gains here multiply and the product has been overlooked before; see the headroom note in
 * [SpatialShaper] and what four decibels of lift did to real music on 2026-09-10.
 */
class DistanceShelf {
    private var left = 0.0
    private var right = 0.0

    /**
     * The left channel of [sample], with [depth] of everything above the corner taken off.
     *
     * Stepped on every frame whether or not anything is being taken off, exactly as
     * [TravellingDelay] is, and for the same reason: a filter that stops being fed is holding
     * whatever was playing when it stopped, and hands that back as a click on the way up. At a
     * depth of nothing this is two arithmetic operations and an exact passthrough.
     */
    fun left(sample: Double, coefficient: Double, depth: Double): Double {
        left += coefficient * (sample - left)
        return sample - depth * (sample - left)
    }

    /** The right channel, kept apart: two channels are two sounds. */
    fun right(sample: Double, coefficient: Double, depth: Double): Double {
        right += coefficient * (sample - right)
        return sample - depth * (sample - right)
    }

    companion object {
        /**
         * Where a room stops handing back what it was given, in hertz.
         *
         * Two kilohertz because that is about where ordinary furnishings start absorbing in
         * earnest - carpet, curtains and upholstery are several times more absorbent at four
         * kilohertz than at five hundred, and fairly flat above. Below it a room gives back very
         * nearly everything, which is why a distant sound is dull rather than quiet-and-thin.
         *
         * Fixed, and it does not move with distance. What changes as a source recedes is how much
         * of what reaches a listener has been round the room, not what the room does to what goes
         * round it. Holding it still also costs nothing to keep smooth: the coefficient is worked
         * out once and a depth that moves is a crossfade, while a corner that moved would be the
         * filter's own shape changing under the signal inside it.
         */
        const val CORNER_HZ = 2000.0

        /**
         * How much of the top a source at the furthest this allows has lost, in decibels.
         *
         * Five, which is the middle of what a living room measures between its direct and its
         * reverberant field above the corner. **Fitted from the mechanism rather than derived, and
         * the second number in this project to be in that position** - see
         * [SpatialField.RECEDE_ROLLOFF], which is the first and was set the same way. If a source
         * dragged all the way out sounds muffled rather than far, this is the number to lower.
         *
         * Counted in decibels and ridden linearly by the retreat, so that it moves evenly to an ear
         * across the drag - the same argument as [SpatialField.RETREAT_DECIBELS], whose travel this
         * one is written against.
         */
        const val FULL_SHELF_DECIBELS = 5.0

        /** How far the pole moves toward each sample it is handed, at [sampleRate]. */
        fun coefficientFor(sampleRate: Int): Double {
            require(sampleRate > 0) { "samples need a rate: $sampleRate" }
            return (1.0 - exp(-2.0 * Math.PI * CORNER_HZ / sampleRate)).coerceIn(0.0, 1.0)
        }

        /**
         * How much of the top is taken off at a given [retreat], as a fraction, 0 to 1.
         *
         * Exactly zero at a retreat of zero, and that matters more than it looks: the whole of the
         * ordinary case runs through here, and a filter that is very nearly a passthrough is not a
         * passthrough. At zero the arithmetic above returns the sample it was handed.
         */
        fun depthFor(retreat: Double): Double {
            val decibels = FULL_SHELF_DECIBELS * retreat.coerceIn(0.0, 1.0)
            return 1.0 - 10.0.pow(-decibels / 20.0)
        }
    }
}
