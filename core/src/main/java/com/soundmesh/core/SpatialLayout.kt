package com.soundmesh.core

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Where one handset sits, as the listener drew it.
 *
 * Dimensionless on purpose, and still dimensionless now that distance is read. Everything built
 * on this layout is a rule about which handset is louder than which, and what such a rule needs
 * from a distance is the **ratio** to the other handsets, which does not know the scale: drawing
 * the same room larger changes no gain anywhere. Metres would therefore be a number nothing reads,
 * and a number nothing reads is a number nobody notices going wrong.
 *
 * [x] runs to the listener's right and [y] to the listener's front. A screen's y axis runs the
 * other way, so whatever draws this flips it; the model keeps the convention a person would state
 * out loud rather than the one a canvas happens to use.
 */
data class SpatialPosition(val peerId: String, val x: Double, val y: Double)

/**
 * The room as the listener drew it: the listener at the origin, one handset per icon.
 *
 * The listener is not draggable and is not stored. Fixing it on the screen removes the one piece
 * of geometry a person cannot check by looking - you can see whether a phone icon is where the
 * phone is, but you cannot see whether the app believes you are sitting where you are sitting.
 *
 * Calibration measures a separation for each pair it runs, and comparing the drawn edge ratios
 * against the measured ones is how a mislabelled icon gets caught - three identical handsets on a
 * table make that an easy mistake and an unfindable one, because a swapped pair produces a rotation
 * that runs backwards for no visible reason. That check reads the drawing; it does not feed it.
 * What calibration measures is handset to handset; what [distanceGainOf] wants is listener to
 * handset, which no measurement here has ever taken.
 */
class SpatialLayout(val positions: List<SpatialPosition>) {
    init {
        require(positions.isNotEmpty()) { "a layout with no handsets places nothing" }
        require(positions.map { it.peerId }.toSet().size == positions.size) {
            "two handsets share a name: ${positions.map { it.peerId }}"
        }
        // A handset on top of the listener has no direction from the listener, and every rule here
        // is a rule about direction. Refused at construction rather than defaulted to straight
        // ahead, which would be a position the drawing does not show.
        require(positions.none { it.x == 0.0 && it.y == 0.0 }) {
            "a handset cannot sit where the listener sits: it would have no direction"
        }
    }

    val peerIds: List<String> get() = positions.map { it.peerId }

    // Read once. Every distance below is a fraction of it, which is what keeps the drawing free of
    // units: multiply every coordinate by anything and this multiplies with them.
    private val furthest = positions.maxOf { hypot(it.x, it.y) }

    /**
     * Which way [peerId] lies, in radians: 0 straight ahead, +pi/2 to the right, +-pi behind.
     *
     * Clockwise-positive rather than the mathematical convention, so that "more positive is
     * further right" holds for both this and the pan control that drives it.
     */
    fun azimuthOf(peerId: String): Double {
        val position = positions.firstOrNull { it.peerId == peerId }
            ?: throw IllegalArgumentException("no handset named $peerId in this layout")
        return atan2(position.x, position.y)
    }

    /**
     * What [peerId] multiplies by so that what it emits arrives alongside the rest.
     *
     * Every rule here says how loud a handset should be **where the listener is sitting**, and until
     * this existed they all quietly assumed that was the same as how loud it plays. It is not: sound
     * spreads out as it travels, so a handset drawn twice as far away arrives half as loud and has
     * to play twice as loud to make up for it. Dragging an icon nearer or further used to change
     * nothing at all - the radius was read and thrown away, and only the angle survived.
     *
     * **This is the first thing that makes the drawn distances load-bearing.** A room drawn with the
     * angles right and the distances guessed used to render exactly as well as one drawn carefully.
     *
     * Expressed against the furthest handset, so the answer runs from [CLOSEST_SHARE] to 1 and
     * nothing is ever asked for more than it has. How loud the room is overall stays where it was,
     * in the whole-room power normalisation that reads this.
     *
     * **It corrects the level and not the time.** The far handset is also heard late, by about 2.9 ms
     * per metre, and past a millisecond or so the earlier handset takes the image outright however
     * the levels are set. That half cannot be computed from a drawing - it wants a microphone at the
     * listening position - so this is half a fix and has to be described as one.
     */
    fun distanceGainOf(peerId: String): Double {
        val position = positions.firstOrNull { it.peerId == peerId }
            ?: throw IllegalArgumentException("no handset named $peerId in this layout")
        return (hypot(position.x, position.y) / furthest).coerceAtLeast(CLOSEST_SHARE)
    }

    fun contains(peerId: String): Boolean = positions.any { it.peerId == peerId }

    companion object {
        /**
         * How far down the correction is allowed to take the nearest handset: 12 dB, and no further.
         *
         * Not arithmetic - the arithmetic would happily go to silence. Two things stop it. A hand
         * drawn position close to the listener is mostly error, because a few pixels of drawing is a
         * large fraction of a small radius. And a room is nothing like free field: past a metre or
         * two the reflections carry most of what is heard and the level stops falling off the way
         * this computes it, so a large correction is **confidently wrong**, in the direction of
         * silencing a handset the listener can see on the screen and expects to hear.
         *
         * A fraction of the furthest handset rather than a fixed radius, so the cap scales with the
         * drawing exactly as everything else here does.
         */
        const val CLOSEST_SHARE = 0.25
    }
}
