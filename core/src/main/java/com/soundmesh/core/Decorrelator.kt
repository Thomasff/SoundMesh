package com.soundmesh.core

/**
 * Makes one handset's copy of the mix stop being the same waveform as every other handset's.
 *
 * The problem it exists for: with several handsets playing one mix, the ear does not hear several
 * sources. It hears one, at whichever handset reached it first, and the rest arrive as colouration.
 * That is the precedence effect doing exactly what it is for, and no amount of level or delay
 * argues with it - a delayed copy of a waveform is still that waveform, which is what the 09-14
 * retraction in delay-as-a-spatial-cue.md is about. What breaks the fusion is the copies no longer
 * being copies.
 *
 * So: an allpass filter. It changes when each frequency leaves without changing how loud it is, and
 * a different one per handset leaves every handset playing the same music with a different phase
 * history. Magnitude untouched is not a design goal here but an identity - a section
 * (g + z^-M)/(1 + g z^-M) has a numerator and a denominator of equal magnitude at every frequency,
 * so the response is exactly one and the check in DecorrelatorTest is arithmetic rather than taste.
 * That is why this is written here instead of ported: it can be verified, and a 30-year-old
 * published algorithm we write ourselves and can verify beats one we cannot.
 *
 * Each handset draws its own delays from its own [peerId] and nothing is sent between them. There
 * is nothing to agree about: two handsets sounding different is the entire point, so the one thing
 * a shared rule would buy - agreement - is the one thing not wanted.
 *
 * **What this costs, and the limit it is held under.** An allpass spreads an impulse out in time,
 * and spreading an attack out is how a decorrelator turns into a bad reverb. The struck/held axis
 * was deleted on 2026-09-14 for sounding exactly like that, so the length is not a free parameter:
 * [GAIN] and [DELAYS_MS] are chosen so that 95% of an impulse is out within forty milliseconds,
 * under the echo threshold for percussive material, and DecorrelatorTest fails if they are not.
 *
 * None of that says it sounds enveloping. Nothing measurable does. That is a knob from zero and a
 * person in a room.
 */
class Decorrelator(val peerId: String, sampleRate: Int) {
    private val leftLines: Array<DoubleArray>
    private val rightLines: Array<DoubleArray>
    private val leftAt: IntArray
    private val rightAt: IntArray

    /** Which delay each stage drew, in samples, so that two of these can be told apart. */
    val delaySamples: List<Int>

    init {
        require(sampleRate > 0) { "milliseconds need a rate to become samples: $sampleRate" }
        val seed = NameSeed.of(peerId)
        val picks = IntArray(DELAYS_MS.size)
        var parity = 0
        for (stage in 0 until DELAYS_MS.size - 1) {
            // One independent draw per stage rather than one draw spread across stages, so that
            // two names agreeing about an early stage says nothing about the later ones.
            picks[stage] = NameSeed.pick(seed, stage, DELAYS_MS[stage].size)
            parity += picks[stage]
        }
        // The last stage is not drawn, it is the check digit - see [DELAYS_MS] for why.
        picks[picks.size - 1] = parity % DELAYS_MS[picks.size - 1].size
        val lengths = IntArray(picks.size) { stage ->
            // At least one sample: a delay line of length zero is not a short filter, it is an
            // index out of bounds on a handset in the middle of a song.
            maxOf(1, Math.round(DELAYS_MS[stage][picks[stage]] * sampleRate / 1000.0).toInt())
        }
        delaySamples = lengths.toList()
        leftLines = Array(lengths.size) { DoubleArray(lengths[it]) }
        rightLines = Array(lengths.size) { DoubleArray(lengths[it]) }
        leftAt = IntArray(lengths.size)
        rightAt = IntArray(lengths.size)
    }

    /**
     * The left channel of [sample], run through the first [stages] sections.
     *
     * [stages] rather than a wet/dry amount, because a wet/dry amount is the one shape this
     * effect cannot take: mixing a phase-shifted copy back with the original is a comb filter,
     * and colouring the sound is the single thing a decorrelator must not do. Every whole number
     * of sections is exactly allpass, so the knob moves between settings that are all colourless
     * rather than between two that are and a middle that is not.
     */
    fun left(sample: Double, stages: Int): Double = advance(leftLines, leftAt, sample, stages)

    /** The right channel. Separate state, because the two channels are two sounds. */
    fun right(sample: Double, stages: Int): Double = advance(rightLines, rightAt, sample, stages)

    private fun advance(lines: Array<DoubleArray>, at: IntArray, sample: Double, stages: Int): Double {
        var carried = sample
        for (stage in 0 until stages.coerceIn(0, lines.size)) {
            val line = lines[stage]
            val index = at[stage]
            val delayed = line[index]
            // The delay line holds the section's own feedback, not its input or its output: that is
            // what makes the pole and the zero reciprocal, and reciprocal is what "allpass" means.
            val inner = carried - GAIN * delayed
            line[index] = inner
            at[stage] = if (index + 1 == line.size) 0 else index + 1
            carried = GAIN * inner + delayed
        }
        return carried
    }

    companion object {
        /**
         * How much of each section reflects back, which is the knob between "no effect" and "reverb".
         *
         * The tail of one section is an impulse train decaying by this factor every [DELAYS_MS]
         * milliseconds, so the length of the whole thing is set here as much as by the delays.
         * At this setting 95% of one section's energy is out within two or three of its own delays;
         * two thirds would need four, and four times the longest delay is a slapback.
         */
        const val GAIN = 0.55

        /**
         * How many sections a knob reading [diffusion] asks for, from none to all of them.
         *
         * The wire carries a fraction rather than a count so the ladder can be changed without a
         * protocol version, and zero maps to zero sections exactly - a setting of off has to be
         * off, not a transform that happens to be nearly an identity. See the 09-14 window that
         * was paid for one.
         */
        fun stagesFor(diffusion: Double): Int =
            Math.round(diffusion.coerceIn(0.0, 1.0) * STAGES).toInt()

        /**
         * What a stream has to be turned down by before it is handed to this, as a factor.
         *
         * An allpass preserves energy, not peak: rearranging the phases piles some of them up.
         * Measured 2026-09-14 against the longest filter here - pink noise, which is the closest
         * of the three to a loud master, peaks 0.3 to 1.1 dB higher; white noise 3.6 to 5.6; a
         * square wave 6.6. Two decibels covers real material with margin and costs a listener a
         * nudge of the volume key, where relying on the clamp instead would cost them clipping on
         * every loud passage. Taken away rather than added, because there is no headroom to add
         * into: a handset already carrying a whole side of the mix is above unity before this.
         *
         * Exactly one when no section is running, so a room with this knob at zero is bit for bit
         * the room it was before this existed.
         */
        fun headroomFor(stages: Int): Double = if (stages <= 0) 1.0 else HEADROOM

        /** Two decibels. */
        private const val HEADROOM = 0.794

        /**
         * The pools each stage draws its delay from, in milliseconds, shortest stage first.
         *
         * Four stages: one is audibly a single echo, and each further one costs tail for less
         * decorrelation than the one before it.
         *
         * The pools do not overlap, so a handset always gets four different delays and the stages
         * cannot line up into one long one.
         *
         * **The last stage is a check digit, not a draw**, and that is the part worth explaining.
         * Four free draws leave a family of pairs that agree about three stages and differ by one
         * step in the fourth - and those two handsets are barely decorrelated at all. Measured
         * 2026-09-14: the worst such pair peaks at **0.51** of full correlation, over the 0.5 this
         * is asked to stay under, while an ordinary pair sits near 0.14. It is rare, about one
         * room in fifty, and it fails gracefully - two handsets sounding like each other is where
         * every handset started. It is also avoidable for four lines, so it is avoided: drawing
         * three stages and deriving the fourth from their sum makes every two different filters
         * differ in **at least two** stages, and the worst pair that construction still permits
         * measures **0.40**. Eight choices over three free stages is 512 filters.
         */
        val DELAYS_MS: Array<DoubleArray> = arrayOf(
            doubleArrayOf(1.2, 1.4, 1.5, 1.7, 1.8, 2.0, 2.1, 2.3),
            doubleArrayOf(2.4, 2.6, 2.9, 3.1, 3.3, 3.5, 3.8, 4.0),
            doubleArrayOf(4.1, 4.4, 4.7, 5.0, 5.2, 5.5, 5.8, 6.1),
            doubleArrayOf(6.2, 6.5, 6.8, 7.1, 7.4, 7.7, 7.9, 8.1)
        )

        /**
         * How many sections there are, which is also the top of the knob.
         *
         * Declared under [DELAYS_MS] and not beside the knob it belongs to, because a companion
         * initialises in the order it is written and this one reads the array above it.
         */
        val STAGES = DELAYS_MS.size

    }
}
