package com.soundmesh.core

import kotlin.math.exp
import kotlin.math.pow

/**
 * The room the sound is in: everything a listener hears that did not come straight from the source.
 *
 * This is the thing two earlier numbers were standing in for, and building it retires both of them.
 * `DistanceShelf` took the top off a receding source because a distant sound is duller - the
 * mechanism for that is reverberation, and the dulling belongs inside the tail rather than across
 * the whole output, which is what [DAMPING_HZ] does here. A plain output filter softened
 * the distance law from six decibels a doubling to about three and a half, because a real room's
 * reverberation does not fall off the way its direct sound does - with a reverberation present that
 * floor is there in fact, so the law underneath it can go back to being the plain one.
 *
 * **Only the wet.** [left] and [right] return the reverberation alone and never mix any of the
 * source back in. The direct sound stays a bypass in [SpatialShaper], untouched and not delayed by
 * one sample, and that is the whole of why this is safe to add to a project built on handsets
 * agreeing about when a sound leaves them. What must agree is the direct sound, and it still does,
 * exactly.
 *
 * **And the wet must not agree.** In a real room the reverberation arriving at two different points
 * is uncorrelated - that is what makes a room enveloping rather than a mono echo with a listener in
 * front of it. So each handset draws its own comb lengths from its own [peerId], nothing is sent
 * between them, and two handsets' tails are two different signals with the same statistics. This is
 * the same argument the retired decorrelator was written on, and it is the one place a roomful of handsets can
 * do something a pair of speakers cannot. An earlier note in the on-device queue said every
 * handset's latency through this had to match or synchronisation would be destroyed; that was
 * wrong, and wrong in the direction that makes this look harder than it is.
 *
 * **The structure is Schroeder-Moorer, and the tunings are Freeverb's** - eight parallel damped
 * comb filters into four series allpasses, public domain, thirty years in the field. Written here
 * rather than taken as a dependency because tests run on the JVM and anything with a native library
 * cannot be one; but the delay lengths are copied rather than invented, because there is no perfect
 * criterion for "sounds like a room and not like a pipe" and those mutually-prime sample counts are
 * the hard-won part. That is this project's own rule for when to borrow, applied the other way
 * round from the decorrelator, whose magnitude was exactly one and so could be checked here.
 *
 * What is not Freeverb is the feedback: each comb gets its own, worked out from [REVERB_SECONDS] and
 * its own length, so that every comb decays over the same time. One feedback for combs of different
 * lengths - which is what Freeverb does - decays the short ones faster and is a good part of why it
 * rings.
 */
class RoomReverb(val peerId: String, sampleRate: Int) {
    private val leftBank: Bank
    private val rightBank: Bank

    init {
        require(sampleRate > 0) { "delays need a rate to become samples: $sampleRate" }
        val seed = NameSeed.of(peerId)
        val stretch = sampleRate / REFERENCE_RATE
        // One independent draw per comb, as the decorrelator does, so that two names agreeing about
        // one comb says nothing about the rest of them.
        val spread = IntArray(COMB_SAMPLES.size) { NameSeed.pick(seed, it, SPREAD_SAMPLES + 1) }
        leftBank = Bank(
            IntArray(COMB_SAMPLES.size) { lengthOf(COMB_SAMPLES[it] + spread[it], stretch) },
            IntArray(ALLPASS_SAMPLES.size) { lengthOf(ALLPASS_SAMPLES[it], stretch) },
            sampleRate
        )
        rightBank = Bank(
            // The same offsets plus a fixed shift, which is how Freeverb makes its two channels
            // differ. The two channels are one room heard at two points, so they want a smaller
            // difference than two handsets do - they share the draw and separate by a constant.
            IntArray(COMB_SAMPLES.size) {
                lengthOf(COMB_SAMPLES[it] + spread[it] + STEREO_SHIFT_SAMPLES, stretch)
            },
            IntArray(ALLPASS_SAMPLES.size) {
                lengthOf(ALLPASS_SAMPLES[it] + STEREO_SHIFT_SAMPLES, stretch)
            },
            sampleRate
        )
    }

    /** Which comb lengths this handset drew, in samples, so that two of these can be told apart. */
    val combSamples: List<Int> get() = leftBank.combLengths.toList()

    /** What the room sends back on the left, given [sample] going into it. Wet only. */
    fun left(sample: Double): Double = leftBank.advance(sample)

    /** The right channel: its own state and its own lengths, because two channels are two sounds. */
    fun right(sample: Double): Double = rightBank.advance(sample)

    /** One channel's eight combs and four allpasses, with everything they remember. */
    private class Bank(val combLengths: IntArray, allpassLengths: IntArray, sampleRate: Int) {
        private val combLines = Array(combLengths.size) { DoubleArray(combLengths[it]) }
        private val combAt = IntArray(combLengths.size)
        private val damped = DoubleArray(combLengths.size)
        private val feedback = DoubleArray(combLengths.size) {
            feedbackFor(combLengths[it], sampleRate)
        }
        private val allpassLines = Array(allpassLengths.size) { DoubleArray(allpassLengths[it]) }
        private val allpassAt = IntArray(allpassLengths.size)

        /** How much each comb keeps of what it heard last time round: see [DAMPING_HZ]. */
        private val keep = exp(-2.0 * Math.PI * DAMPING_HZ / sampleRate)

        /**
         * What the combs have to be fed at so that what leaves the whole bank cannot be louder
         * than what went in.
         *
         * A comb with feedback f has a gain of 1/(1 - f) at the frequencies where its own delay
         * comes back in phase, and eight of them in parallel add. That is the worst case, not the
         * typical one - on real material the combs come back at different frequencies and the tail
         * sits well below the source - and the worst case is the one worth buying, because this
         * project has already shipped four decibels of lift that clipped real music within
         * minutes. Taken away rather than added, which is the only arithmetic that cannot overflow
         * anything downstream.
         *
         * **The allpasses are in this bound and the first draft left them out**, which measured as
         * a tail seventeen decibels louder than the arithmetic above predicted. Freeverb's allpass
         * is not one: (-1 + z^-M)/(1 - g z^-M) has a numerator and a denominator that do not match,
         * peaking at 2/(1 + g) where the delay comes back inverted. Four of those in series is a
         * factor of three that a bound over the combs alone knows nothing about. A bound that does
         * not cover the whole chain is not a bound, and the impulse it was checked on was the one
         * input that happened not to show it.
         */
        private val intake =
            1.0 / (feedback.sumOf { 1.0 / (1.0 - it) } * allpassPeak(allpassLengths.size))

        fun advance(sample: Double): Double {
            val into = sample * intake
            var summed = 0.0
            for (comb in combLines.indices) {
                val line = combLines[comb]
                val index = combAt[comb]
                val heard = line[index]
                // The damping is inside the loop, not across the output, and that placement is the
                // point: what a wall takes off it takes off once per bounce, so the top disappears
                // over the length of the tail rather than being missing from the first millisecond.
                // That is the difference between a room and a tone control, and it is exactly what
                // the shelf this replaces could not do.
                damped[comb] = heard * (1.0 - keep) + damped[comb] * keep
                line[index] = into + damped[comb] * feedback[comb]
                combAt[comb] = if (index + 1 == line.size) 0 else index + 1
                summed += heard
            }
            var carried = summed
            for (stage in allpassLines.indices) {
                val line = allpassLines[stage]
                val index = allpassAt[stage]
                val heard = line[index]
                line[index] = carried + heard * ALLPASS_FEEDBACK
                allpassAt[stage] = if (index + 1 == line.size) 0 else index + 1
                carried = heard - carried
            }
            return carried
        }
    }

    companion object {
        /**
         * How long the room takes to fall sixty decibels: half a second.
         *
         * An ordinary furnished living room, which is what every listening test in this project has
         * been done in. Concert halls are two seconds and bathrooms are one; a room with curtains,
         * a sofa and a carpet is between a third and two thirds, and half is the middle of that.
         *
         * Reached by giving each comb its own feedback rather than sharing one, so all eight decay
         * together. [feedbackFor] is that arithmetic and ReverbTest measures the result rather than
         * trusting it.
         */
        const val REVERB_SECONDS = 0.5

        /**
         * Where the room stops handing the top back, in hertz: eight kilohertz.
         *
         * A one-pole lowpass inside each comb's feedback path, so every bounce loses a little more
         * of the top and the tail is dull long before it is gone. That is the actual mechanism
         * behind a distant sound being duller - a listener across a room is hearing mostly
         * reflections, and each reflection has left some of its top on a wall, a curtain or a sofa.
         *
         * Eight rather than the two kilohertz the shelf this replaces used, and the difference is
         * not a disagreement: the shelf had one pass at the signal and had to take the whole of the
         * room's effect off in it, while this is applied once per bounce and there are seventeen
         * bounces in half a second. A corner low enough to be a room in one pass would leave nothing
         * above a kilohertz by the end of the tail.
         */
        const val DAMPING_HZ = 8000.0

        /**
         * The most of the output the room is ever allowed to be, as a fraction.
         *
         * A crossfade rather than an addition - the dry comes down by exactly what the wet goes up
         * by - so that turning the room up cannot make a sample larger than the same arrangement
         * made without it. The [Bank.intake] above bounds the wet by the source; this bounds the
         * sum. Between them, the one failure mode this project has actually shipped cannot happen
         * here.
         *
         * Half. **The "nobody will go near the top" this was written with turned out to be wrong**,
         * and it is worth leaving the correction here rather than quietly restating it: a listener
         * on 2026-09-17 picked four tenths, which is four fifths of the way up. The top is a
         * quarter above the one setting anybody has ever chosen, not the generous margin this
         * comment first claimed.
         *
         * Left where it is anyway. Widening it would move what every stored setting means for the
         * sake of where a finger sits on a slider, and the one number that has been listened to is
         * comfortably inside it. But nothing here should be read as saying there is room to spare
         * - if a later room wants more of itself than this, the constant, not the listener, is what
         * is wrong.
         */
        const val MOST_WET = 0.5

        /** What the wet is scaled by at a given [reverb] setting: the crossfade's own fraction. */
        fun wetFor(reverb: Double): Double = reverb.coerceIn(0.0, 1.0) * MOST_WET

        /**
         * What one comb of [samples] has to feed back to fall sixty decibels in [REVERB_SECONDS].
         *
         * A comb hands its contents round every `samples / rate` seconds multiplied by this, so over
         * the reverberation time it goes round `rate * REVERB_SECONDS / samples` times and each
         * pass has to account for its share of a thousandth.
         */
        fun feedbackFor(samples: Int, sampleRate: Int): Double {
            require(samples > 0) { "a comb needs a length: $samples" }
            val passes = sampleRate * REVERB_SECONDS / samples
            return 10.0.pow(-3.0 / passes)
        }

        /** The rate Freeverb's tunings are quoted at; ours are stretched from these. */
        private const val REFERENCE_RATE = 44100.0

        /** Freeverb's eight comb lengths, in samples at [REFERENCE_RATE]. */
        private val COMB_SAMPLES = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)

        /** Freeverb's four allpass lengths, in samples at [REFERENCE_RATE]. */
        private val ALLPASS_SAMPLES = intArrayOf(556, 441, 341, 225)

        /** Fixed, as Freeverb has it: an allpass's feedback is not a parameter worth having. */
        private const val ALLPASS_FEEDBACK = 0.5

        /**
         * The most a chain of [stages] of Freeverb's allpass can multiply anything by.
         *
         * One stage is (-1 + z^-M)/(1 - g z^-M), which is largest where the delayed copy comes back
         * inverted - numerator two, denominator one plus g. Named and derived rather than measured,
         * because it is what the intake above divides by and a headroom nobody can re-derive is the
         * sort of constant this project has watched go stale.
         */
        fun allpassPeak(stages: Int): Double = (2.0 / (1.0 + ALLPASS_FEEDBACK)).pow(stages)

        /** How far the right channel's lengths sit from the left's, in samples. Freeverb's number. */
        private const val STEREO_SHIFT_SAMPLES = 23

        /**
         * How far a handset may move its combs from the published lengths, in samples.
         *
         * Two milliseconds at the reference rate, which is enough that two handsets' tails are
         * different signals and little enough that the tunings keep the mutual-prime spacing they
         * were chosen for. Wider would drift combs onto each other's multiples, which is the
         * failure that makes a reverberation ring on one note.
         */
        private const val SPREAD_SAMPLES = 88

        /** A published length at this room's rate, never shorter than a single sample. */
        private fun lengthOf(reference: Int, stretch: Double): Int =
            maxOf(1, Math.round(reference * stretch).toInt())
    }
}
