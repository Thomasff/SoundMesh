package com.soundmesh.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What a handset multiplies its two channels by. Both channels are scaled, never delayed. */
data class StereoGain(val left: Double, val right: Double)

/**
 * The three things a room full of handsets can be made to do.
 *
 * All three are amplitude rules, and that is not a simplification - it is the only kind of rule
 * this system can carry. A listener locates a sound mainly by the time difference between their
 * ears, whose whole range is +-690 us, and the alignment between two handsets is good to about
 * 1 ms. Placing a sound by delay would therefore be steering with an error larger than the whole
 * control range. Loudness ratios do not care what the clocks are doing.
 */
enum class SpatialMode {
    /** A source that circles the listener on its own, once per period. */
    ROTATE,

    /** A source the listener drags along the arc in front of them. */
    PAN,

    /** No moving source: each handset carries the side of the stereo image it stands on. */
    SPLIT
}

/**
 * The two ways a room can be told to split the song up between its handsets.
 *
 * One at a time, and the same knob and the same list of handsets drive whichever is chosen. Two
 * separations running at once would need four parts named on a screen that has room for two, and
 * would hand a handset the half of an axis its listener never touched.
 */
enum class SplitAxis {
    /** What the two channels share against what they disagree about: the middle of the image against its edges. */
    MIDDLE_SIDES,

    /** What is below the crossover against what is above it. */
    LOW_HIGH
}

/**
 * What one handset takes of the mix it was sent and of the low half of that mix.
 *
 * Two numbers rather than one because the low and high halves are not each other with a sign
 * turned round, the way the middle and the sides are: the high half is defined by subtraction, so
 * the handset carrying it keeps the whole mix and takes the low half away, while the one carrying
 * the low half does the opposite and lets go of the mix.
 */
data class SpectrumMix(val whole: Double, val low: Double)

/**
 * One rule for turning a host instant into every handset's pair of gains.
 *
 * Every chunk already carries the host instant it is to be played at, so a gain expressed as a
 * function of that instant is the same on every handset without anything being sent between them.
 * That is what the alignment work buys here: the sweep stays in step across the room because the
 * handsets agree about the clock, not because a message arrives on time.
 *
 * [ROTATE][SpatialMode.ROTATE] and [PAN][SpatialMode.PAN] are the same law driven two ways - a
 * clock or a slider - because a pan control that did not agree with the rotation about where
 * "right" is would put the same sound in two places depending on which one last touched it.
 */
data class SpatialField(
    val mode: SpatialMode,
    val layout: SpatialLayout,
    /** How long one circuit takes. Only read by [SpatialMode.ROTATE]. */
    val periodNanos: Long = DEFAULT_PERIOD_NANOS,
    /** Where the listener has dragged the source: -1 hard left, +1 hard right. Only read by [SpatialMode.PAN]. */
    val pan: Double = 0.0,
    /** The instant the circuit is measured from, so every handset starts the sweep at the same angle. */
    val epochHostNanos: Long = 0L,
    /**
     * How far apart the room pulls the mix, from 0 (every handset plays all of it) to 1.
     *
     * A knob rather than a switch because it is also the way this degrades. Separating the mix asks
     * much more of the alignment than placing it does: the handsets are no longer playing the same
     * waveform, so the time between them stops being a colouration and becomes the thing that decides
     * where the listener hears the sound. Past about a millisecond the earlier handset takes the
     * image outright and the separation is not merely spoiled but gone. Winding this down lands the
     * room back on the mix it was already playing, which is a worse effect rather than a broken one.
     */
    val separation: Double = 0.0,
    /** Which way the mix is pulled apart. The knob and [otherHalfIds] mean whatever this says they mean. */
    val splitAxis: SplitAxis = SplitAxis.MIDDLE_SIDES,
    /**
     * Where the low half stops, in hertz. Only read by [SplitAxis.LOW_HIGH].
     *
     * Not ramped when it moves, and it does not need to be: changing where a filter divides does
     * not move the signal already inside it, so the output stays continuous through a drag.
     */
    val crossoverHz: Double = DEFAULT_CROSSOVER_HZ,
    /**
     * Which handsets carry the far half of whichever split [splitAxis] names - the sides, or the
     * high. Every handset the drawing names and this does not carries the near half.
     */
    val otherHalfIds: Set<String> = emptySet()
) {
    init {
        require(periodNanos > 0L) { "a circuit takes time: $periodNanos" }
        require(pan in -1.0..1.0) { "pan runs from -1 to +1: $pan" }
        require(separation in 0.0..1.0) { "separation runs from 0 to 1: $separation" }
        require(crossoverHz in LOWEST_CROSSOVER_HZ..HIGHEST_CROSSOVER_HZ) {
            "a crossover has to be somewhere a person can hear: $crossoverHz"
        }
        // Refused rather than ignored: a name that is in neither the drawing nor an error message is
        // a handset the listener assigned and cannot see the assignment of.
        require(otherHalfIds.all { layout.contains(it) }) {
            "these handsets carry the sides but are not in the drawing: ${otherHalfIds.filterNot { layout.contains(it) }}"
        }
    }

    /** Where the source is at [hostNanos], as an azimuth. Meaningless for [SpatialMode.SPLIT]. */
    fun sourceAzimuthAt(hostNanos: Long): Double = when (mode) {
        SpatialMode.ROTATE -> {
            // floorMod rather than %, so the angle a reader sees while debugging runs forwards
            // from zero for an instant before the epoch. It cannot change a gain: the two differ
            // by exactly one period, which is exactly one turn.
            val elapsed = Math.floorMod(hostNanos - epochHostNanos, periodNanos)
            2.0 * PI * elapsed / periodNanos
        }
        // The slider covers the frontal half circle only. Behind the listener is reachable by
        // rotation but not by dragging: a control whose two ends meet has no ends.
        SpatialMode.PAN -> pan * PI / 2.0
        SpatialMode.SPLIT -> 0.0
    }

    /**
     * How much of the other channel [peerId] folds into each of its own, before any placement gain.
     *
     * A mix is two channels because someone placed each instrument by how loudly it appears in each.
     * Anything they placed in the middle appears in both identically, so adding the channels keeps it
     * and subtracting them cancels it exactly; anything they placed to a side survives the subtraction
     * and is thinned by the addition. That is the whole mechanism, and it is one addition per sample.
     *
     * Both halves are the same shape with one sign changed, so a single signed number says which part
     * a handset carries and how much of it: the handset keeps 1 - abs(fold) of its own channel and
     * folds [fold] of the other one in. At +1/2 that is exactly the middle on both channels, at -1/2
     * exactly the sides, and at 0 the mix untouched. Nothing else in this class has to know.
     *
     * Two things this cannot do, both of which have to reach the listener as words rather than as a
     * surprise. It divides by **where a sound was placed**, never by what instrument it is - the kick
     * and the bass sit in the middle with the voice and leave with it. And material the two channels
     * agree on has no sides at all, so a handset given the sides of a mono recording is silent.
     *
     * The two parts are also not equally loud - in most music the middle carries far more energy than
     * the sides - and nothing here corrects for that. Whether it should is a question about what a
     * room sounds like, so it waits for a listener rather than for an argument.
     */
    fun foldFor(peerId: String): Double {
        require(layout.contains(peerId)) { "no handset named $peerId in this layout" }
        if (splitAxis != SplitAxis.MIDDLE_SIDES) return 0.0
        return if (peerId in otherHalfIds) -separation / 2.0 else separation / 2.0
    }

    /**
     * How much of the mix and of its low half [peerId] plays, before any placement gain.
     *
     * The other axis, and a different kind of division from the fold. The middle and the sides are
     * arithmetic on the sample in hand; low and high are what a sound has been doing for the last
     * few milliseconds, so this half of the feature needs a filter that remembers - see [Crossover],
     * which is where the remembering lives. Nothing here holds any state.
     *
     * Written as a crossfade from the whole mix toward this handset own half, so the knob lands in
     * the same place on both axes: at zero every handset plays what it was sent, at one it plays
     * only its half, and in between it is the two mixed in that proportion. The high half is the mix
     * with the low half taken out of it rather than a filter of its own, which is what makes the two
     * halves add back up to exactly what was sent no matter what the filter does to the low one.
     *
     * The same two warnings as the fold apply in their own shape. This divides by **frequency**, not
     * by instrument: a voice and a guitar both live on both sides of any crossover and will be heard
     * from both handsets. And what a handset speaker can actually produce is not the same as what
     * this hands it - the low half of a mix on a speaker that cannot go low is quieter than the
     * arithmetic says, which is a thing to measure rather than to argue about.
     */
    fun spectrumFor(peerId: String): SpectrumMix {
        require(layout.contains(peerId)) { "no handset named $peerId in this layout" }
        if (splitAxis != SplitAxis.LOW_HIGH) return SpectrumMix(1.0, 0.0)
        // The low half lets go of the mix as it takes the filter on; the high half keeps the mix
        // and subtracts. Summed over the pair that is one mix and no filter left over.
        val trim = highTrim()
        return if (peerId in otherHalfIds) SpectrumMix(trim, -separation * trim)
        else SpectrumMix(1.0 - separation, separation)
    }

    /**
     * How far down the high half plays, so that dragging the split low tilts the room toward it.
     *
     * Dragging the split down takes the low handset's content away twice over: the band it keeps
     * narrows, and what is left of it sits further below the frequency its own speaker stops
     * being able to make a sound at. On 2026-09-10, with the skirt newly steepened, a listener
     * dragged the split to 100 Hz and the low handset went to very nearly nothing. That is the
     * split working and the feature vanishing at the same time: somebody who drags it down there
     * is asking to hear the low part, not to switch a handset off.
     *
     * Six decibels an octave below the default, which is exactly "halve the split, double the
     * difference" - a ratio of frequencies, no logarithm needed. Neutral at and above the default,
     * so every test that asserts the two halves add back up goes on holding where it is written.
     *
     * A trim rather than a lift on the low half, which is what this was first built as. That
     * crackled within minutes of reaching a listener: at a split of 500 Hz the lift was 1.6x,
     * four decibels, and a modern master has nowhere to put four decibels. No cap would have
     * saved it, because the smallest lift the slider can ask for already clipped - and the gains
     * here multiply, so distance compensation and the room power normalisation were on top of it.
     * Trimming cannot do that to anybody: every sample it produces is smaller than the one this
     * same arrangement produced before it existed, and that arrangement had been listened to.
     * What it costs is a room that gets quieter as the split goes down, which a volume key fixes
     * and clipping does not.
     *
     * Riding on [separation] rather than on the crossover alone: with the knob at nothing there
     * is no split, and a handset sitting twelve decibels down because of a slider that is doing
     * nothing would have no control on screen saying so.
     *
     * Still capped, now for taste rather than for damage: past a quarter the room is being turned
     * down rather than tilted, and the far side of the split is a band the speaker cannot carry.
     */
    private fun highTrim(): Double {
        val tilt = (DEFAULT_CROSSOVER_HZ / crossoverHz).coerceIn(1.0, MAX_LOW_TILT)
        return 1.0 / (1.0 + (tilt - 1.0) * separation)
    }

    /**
     * What [peerId] plays its two channels at, at [hostNanos].
     *
     * Normalised so the whole room emits the same power whatever the rule is doing: without it a
     * source crossing the gap between two handsets would sound like the volume dipping rather than
     * like the source moving, and switching modes would change how loud the music is.
     */
    fun gainAt(peerId: String, hostNanos: Long): StereoGain {
        require(layout.contains(peerId)) { "no handset named $peerId in this layout" }
        val raw = layout.peerIds.associateWith { rawGain(it, hostNanos) }
        val power = raw.values.sumOf { (it.left * it.left + it.right * it.right) / 2.0 }
        // A source diametrically opposite every handset in the room is a direction this layout
        // cannot render. Sharing the sound out evenly is a wrong position; silence is a dropout,
        // which is worse and which a listener would blame on the network.
        if (power <= 0.0) {
            // Evenly at the listener rather than evenly at the handsets, which for a room whose
            // handsets are all the same distance off is the same arithmetic this used to do.
            val reaches = layout.peerIds.associateWith { layout.distanceGainOf(it) }
            val evenScale = sqrt(reaches.values.sumOf { it * it })
            val even = reaches.getValue(peerId) / evenScale
            return StereoGain(even, even)
        }
        val scale = 1.0 / sqrt(power)
        val mine = raw.getValue(peerId)
        return StereoGain(mine.left * scale, mine.right * scale)
    }

    /**
     * What the rule asks of [peerId] before the room is normalised, distance included.
     *
     * The distance correction multiplies whatever the mode decided rather than being a mode of its
     * own, because it answers a different question: the mode says how loud this handset should be
     * heard, and this says what it has to play to be heard that loudly from where it is standing.
     * Every mode wants it, so it sits outside the branch.
     */
    private fun rawGain(peerId: String, hostNanos: Long): StereoGain {
        val azimuth = layout.azimuthOf(peerId)
        val reach = layout.distanceGainOf(peerId)
        val placed = when (mode) {
            SpatialMode.ROTATE, SpatialMode.PAN -> {
                // Raised cosine of the angular gap: full facing the source, nothing facing away.
                // For the two-handset case this is exactly constant-power panning; for more it is
                // softer than picking the two handsets that bracket the source and panning between
                // them, which is the sharper rule to reach for if the image turns out mushy. Chosen
                // first because it is defined for every direction, including ones no pair brackets.
                val weight = (1.0 + cos(sourceAzimuthAt(hostNanos) - azimuth)) / 2.0
                StereoGain(weight, weight)
            }
            // How far to the side a handset stands is how much of that side it carries. A room
            // where every handset is straight ahead splits weakly, which is the truth about that
            // room rather than a defect: a stereo image cannot be wider than the handsets are.
            SpatialMode.SPLIT -> {
                val sideways = sin(azimuth)
                StereoGain(sqrt((1.0 - sideways) / 2.0), sqrt((1.0 + sideways) / 2.0))
            }
        }
        return StereoGain(placed.left * reach, placed.right * reach)
    }

    companion object {
        /** Slow enough to hear as travel rather than as tremolo, fast enough to notice. */
        const val DEFAULT_PERIOD_NANOS = 6_000_000_000L

        /**
         * Where the split starts out, chosen for handset speakers rather than for music theory.
         *
         * A crossover down where a subwoofer would sit hands one handset a part its speaker cannot
         * make a sound with, so the room separates on paper and plays as one handset and one silent
         * one. This sits above that, where both sides of the split are things a phone can emit.
         *
         * How far above is a guess until somebody measures a handset: the roll-off is somewhere
         * around a few hundred hertz by reputation, and reputation is not a measurement.
         */
        const val DEFAULT_CROSSOVER_HZ = 800.0

        /** Wide enough to be worth dragging, narrow enough that both ends are still a split. */
        const val LOWEST_CROSSOVER_HZ = 100.0
        const val HIGHEST_CROSSOVER_HZ = 5_000.0

        /** Twelve decibels. See [highTrim]: past this the room is being turned down, not tilted. */
        const val MAX_LOW_TILT = 4.0
    }
}
