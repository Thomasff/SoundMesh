package com.soundmesh.core

import kotlin.math.atan2

/**
 * Where one handset sits, as the listener drew it.
 *
 * Dimensionless on purpose. Every effect built on this layout is a rule about which handset is
 * louder than which, and loudness ratios depend on direction alone - scaling the whole drawing
 * changes no gain anywhere. Metres would therefore be a number nothing reads, and a number nothing
 * reads is a number nobody notices going wrong.
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
 * Distance is deliberately not measured against this. Calibration already reports a separation for
 * each pair it runs, and comparing the drawn edge ratios against the measured ones is how a
 * mislabelled icon gets caught - three identical handsets on a table make that an easy mistake and
 * an unfindable one, because a swapped pair produces a rotation that runs backwards for no visible
 * reason. That check reads the drawing; it does not feed it.
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

    fun contains(peerId: String): Boolean = positions.any { it.peerId == peerId }
}
