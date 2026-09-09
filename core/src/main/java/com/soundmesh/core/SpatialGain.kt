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
    val epochHostNanos: Long = 0L
) {
    init {
        require(periodNanos > 0L) { "a circuit takes time: $periodNanos" }
        require(pan in -1.0..1.0) { "pan runs from -1 to +1: $pan" }
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
