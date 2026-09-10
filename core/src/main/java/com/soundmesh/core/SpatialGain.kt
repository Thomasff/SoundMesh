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
    /** Which handsets carry the sides. Every handset the drawing names and this does not carries the middle. */
    val sideIds: Set<String> = emptySet()
) {
    init {
        require(periodNanos > 0L) { "a circuit takes time: $periodNanos" }
        require(pan in -1.0..1.0) { "pan runs from -1 to +1: $pan" }
        require(separation in 0.0..1.0) { "separation runs from 0 to 1: $separation" }
        // Refused rather than ignored: a name that is in neither the drawing nor an error message is
        // a handset the listener assigned and cannot see the assignment of.
        require(sideIds.all { layout.contains(it) }) {
            "these handsets carry the sides but are not in the drawing: ${sideIds.filterNot { layout.contains(it) }}"
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
        return if (peerId in sideIds) -separation / 2.0 else separation / 2.0
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
            val even = sqrt(1.0 / layout.peerIds.size)
            return StereoGain(even, even)
        }
        val scale = 1.0 / sqrt(power)
        val mine = raw.getValue(peerId)
        return StereoGain(mine.left * scale, mine.right * scale)
    }

    private fun rawGain(peerId: String, hostNanos: Long): StereoGain {
        val azimuth = layout.azimuthOf(peerId)
        return when (mode) {
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
    }

    companion object {
        /** Slow enough to hear as travel rather than as tremolo, fast enough to notice. */
        const val DEFAULT_PERIOD_NANOS = 6_000_000_000L
    }
}
