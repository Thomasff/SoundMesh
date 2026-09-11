package com.soundmesh.product

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Moving the icons onto what the room measured, keeping everything the measurement cannot know.
 *
 * A person is good at one half of this drawing and bad at the other. Where the room is, which way
 * it faces, which side is left, roughly where each handset sits - all of that they can see, and
 * none of it can be measured: every distance this system takes is handset to handset, and the
 * listener has never been measured at all. What they are bad at is the half a tape measure is good
 * at - that this one is one and a half times further out than that one. So the measurement is
 * asked for the shape and the drag is kept for everything else, which is what the second term of
 * the objective below is for.
 *
 * What it minimises, over the handset positions and one scale:
 *
 *     sum over measured pairs ( drawn apart - scale * measured metres )^2
 *         + priorWeight * sum over handsets ( moved from where it was drawn )^2
 *
 * The scale is a variable rather than a constant because the drawing has no units and never has -
 * see SpatialRoom.layoutOf, which throws scale away on the way out. Fixing it would make
 * [priorWeight] mean something different on a big drawing than on a small one.
 *
 * The first term alone is blind to where the room sits, which way it faces and which way round it
 * is. Every gain in the system reads exactly those three, because every gain is measured from the
 * listener. So the second term is not a tuning knob bolted on for taste - it is the only thing
 * holding the answer still, which is why a weight of zero is refused rather than treated as
 * trusting the measurement completely.
 *
 * It reads the same two things [RoomCheck] does and they have to run in that order: a drawing with
 * two icons on the wrong handsets has to be caught **before** this, because this fits positions to
 * labels and would move the icons onto each other's measured places without a word. Afterwards is
 * too late in a second way as well - once this has run, drawn and measured agree by construction,
 * and the check can never fire again.
 */
object RoomFit {
    /**
     * How far the least the measurements can disagree with any room before they are refused.
     *
     * Each surviving distance passed a MAD gate of [SEPARATION_AGREEMENT_METRES], so that is what
     * one claims about itself. If no arrangement of handsets can get every edge inside that, the
     * edges are not disagreeing with the drawing, they are disagreeing with each other, and there
     * is no room to move the icons onto.
     */
    const val WORST_EDGE_METRES = SEPARATION_AGREEMENT_METRES

    /**
     * How hard the drawing pulls back, against one measured edge pulling with weight one.
     *
     * Chosen by measuring, in RoomFitWeightTest: synthetic rooms, a sketch wrong by a stated
     * amount, distances noisy by a stated amount, and the thing scored is the gain
     * SpatialLayout.distanceGainOf would hand out - the only number any of this reaches.
     */
    const val DEFAULT_PRIOR_WEIGHT = 0.3

    /**
     * The prior used only to ask whether the distances describe a room at all.
     *
     * Small rather than zero: the question is what the edges can achieve on their own, and the
     * prior is what stops the answer from being a perfectly good room that has wandered off and
     * turned over. Residuals do not care where a room sits, so this costs the answer nothing.
     */
    private const val GAUGE_WEIGHT = 1e-6

    private const val ROUNDS = 400
    private const val SETTLED = 1e-10

    /**
     * The drawing with the measured handsets moved onto the measured shape, or null when it
     * refuses.
     *
     * It refuses on three counts, and each is a different thing being wrong. No handset with two
     * measured distances means nothing was said about any shape - one distance is a size, and size
     * is what the drawing does not carry. Edges that no arrangement can satisfy mean the
     * measurement contradicts itself rather than the drawing. And an answer that came out mirrored
     * means the fit crossed the one line the data cannot see and the ear cannot hear.
     *
     * [measuredMetres] is keyed by the two handsets a distance is between and is expected to hold
     * both orders, as StoredRoomField writes them. A handset it says nothing about keeps exactly
     * where it was dragged, which falls out of the arithmetic rather than being a case: with no
     * edges pulling on it, all that is left is the prior, and the prior is where it already is.
     */
    fun corrected(
        icons: List<RoomIcon>,
        measuredMetres: Map<Pair<String, String>, Double>,
        priorWeight: Double = DEFAULT_PRIOR_WEIGHT
    ): List<RoomIcon>? {
        require(priorWeight > 0.0) {
            "a fit with no prior is free to rotate and mirror the room, and every gain reads that"
        }
        val edges = edgesAmong(icons, measuredMetres)
        // Shape needs a handset that two measured edges meet at. Without one there are only
        // separate lengths, and a length on its own is a scale.
        val meetingAt = HashMap<String, Int>()
        for (edge in edges) {
            meetingAt[edge.first] = (meetingAt[edge.first] ?: 0) + 1
            meetingAt[edge.second] = (meetingAt[edge.second] ?: 0) + 1
        }
        if (meetingAt.values.none { it >= 2 }) return null

        if (worstEdgeMetres(placed(icons, edges, GAUGE_WEIGHT), edges) > WORST_EDGE_METRES) return null

        // Brought back onto the drawing before the mirror is looked for, not after: the shrink
        // that does it is uniform about the listener, which cannot turn anything over.
        val fitted = contained(placed(icons, edges, priorWeight))
        val moved = edges.flatMap { listOf(it.first, it.second) }.toSet()
        val before = icons.filter { it.peerId in moved }
        val after = fitted.filter { it.peerId in moved }
        if (turnedInsideOut(before, after)) return null
        return fitted
    }

    /**
     * Whether [to] is [from] turned over - not merely moved and turned, but reflected.
     *
     * The standard test: the best-fitting rotation between the two is a reflection exactly when
     * the determinant of the cross-covariance of the two centred sets is negative. A set compared
     * with itself gives a positive-semidefinite matrix and so never trips it.
     *
     * Its own function because it is the guard that matters most and the hardest to provoke on
     * purpose: mirroring needs a drawing flat enough for the fit to fall out of its own basin,
     * and a room that arrives mirrored sounds exactly like a working one.
     */
    internal fun turnedInsideOut(from: List<RoomIcon>, to: List<RoomIcon>): Boolean {
        val after = to.associateBy { it.peerId }
        val paired = from.mapNotNull { was -> after[was.peerId]?.let { was to it } }
        if (paired.size < 3) return false
        val fromMiddleX = paired.sumOf { it.first.x.toDouble() } / paired.size
        val fromMiddleY = paired.sumOf { it.first.y.toDouble() } / paired.size
        val toMiddleX = paired.sumOf { it.second.x.toDouble() } / paired.size
        val toMiddleY = paired.sumOf { it.second.y.toDouble() } / paired.size

        var xx = 0.0
        var xy = 0.0
        var yx = 0.0
        var yy = 0.0
        for ((was, now) in paired) {
            val ux = was.x.toDouble() - fromMiddleX
            val uy = was.y.toDouble() - fromMiddleY
            val vx = now.x.toDouble() - toMiddleX
            val vy = now.y.toDouble() - toMiddleY
            xx += ux * vx
            xy += ux * vy
            yx += uy * vx
            yy += uy * vy
        }
        return xx * yy - xy * yx < 0.0
    }

    /** One measured distance, in metres, between two handsets both on the drawing. */
    private data class Edge(val first: String, val second: String, val metres: Double)

    private fun edgesAmong(
        icons: List<RoomIcon>,
        measuredMetres: Map<Pair<String, String>, Double>
    ): List<Edge> {
        val here = icons.map { it.peerId }.toSet()
        // One way round of each pair: the field holds both, and an edge counted twice would pull
        // twice as hard as the drawing for no reason anybody chose.
        return measuredMetres
            .filter { it.key.first < it.key.second && it.value > 0.0 }
            .filter { it.key.first in here && it.key.second in here }
            .map { Edge(it.key.first, it.key.second, it.value) }
    }

    /** Where the fit put everybody, listener at the origin, in drawing units, with its scale. */
    private class Placement(
        val x: DoubleArray,
        val y: DoubleArray,
        val index: Map<String, Int>,
        val unitsPerMetre: Double
    )

    /**
     * The fit itself: alternate between the one scale and all the positions until it settles.
     *
     * The scale step is exact - the objective is a quadratic in it. The position step is the
     * Guttman transform with the prior added, which is a weighted average of where each edge would
     * like this handset and where it was drawn; both steps only ever lower the objective, so
     * running out of rounds leaves a worse answer rather than a wrong one.
     */
    private fun placed(icons: List<RoomIcon>, edges: List<Edge>, priorWeight: Double): Placement {
        val index = icons.mapIndexed { at, icon -> icon.peerId to at }.toMap()
        val drawnX = DoubleArray(icons.size) { (icons[it].x - SpatialRoom.CENTRE).toDouble() }
        val drawnY = DoubleArray(icons.size) { (icons[it].y - SpatialRoom.CENTRE).toDouble() }
        val x = drawnX.copyOf()
        val y = drawnY.copyOf()
        val pull = DoubleArray(icons.size)
        for (edge in edges) {
            pull[index.getValue(edge.first)] += 1.0
            pull[index.getValue(edge.second)] += 1.0
        }
        val metresSquared = edges.sumOf { it.metres * it.metres }
        var unitsPerMetre = 1.0
        val nextX = DoubleArray(icons.size)
        val nextY = DoubleArray(icons.size)
        for (round in 0 until ROUNDS) {
            unitsPerMetre = edges.sumOf { edge ->
                val one = index.getValue(edge.first)
                val two = index.getValue(edge.second)
                hypot(x[one] - x[two], y[one] - y[two]) * edge.metres
            } / metresSquared
            for (at in icons.indices) {
                nextX[at] = priorWeight * drawnX[at]
                nextY[at] = priorWeight * drawnY[at]
            }
            for (edge in edges) {
                val one = index.getValue(edge.first)
                val two = index.getValue(edge.second)
                val dx = x[one] - x[two]
                val dy = y[one] - y[two]
                val apart = hypot(dx, dy)
                // Two icons exactly on top of each other have no direction to push apart along,
                // so this round only averages them; the next one has somewhere to go.
                val wantX = if (apart > 0.0) unitsPerMetre * edge.metres * dx / apart else 0.0
                val wantY = if (apart > 0.0) unitsPerMetre * edge.metres * dy / apart else 0.0
                nextX[one] += x[two] + wantX
                nextY[one] += y[two] + wantY
                nextX[two] += x[one] - wantX
                nextY[two] += y[one] - wantY
            }
            var shifted = 0.0
            for (at in icons.indices) {
                val share = pull[at] + priorWeight
                val movedX = nextX[at] / share - x[at]
                val movedY = nextY[at] / share - y[at]
                shifted += movedX * movedX + movedY * movedY
                x[at] += movedX
                y[at] += movedY
            }
            if (shifted < SETTLED) break
        }
        return Placement(x, y, index, unitsPerMetre)
    }

    /** The worst any one edge is off by, in metres, which is what the drawing's scale undoes. */
    private fun worstEdgeMetres(placement: Placement, edges: List<Edge>): Double {
        if (placement.unitsPerMetre <= 0.0) return Double.MAX_VALUE
        return edges.maxOf { edge ->
            val one = placement.index.getValue(edge.first)
            val two = placement.index.getValue(edge.second)
            val apart = hypot(
                placement.x[one] - placement.x[two],
                placement.y[one] - placement.y[two]
            )
            abs(apart / placement.unitsPerMetre - edge.metres)
        }
    }

    /**
     * The answer brought back onto the drawing.
     *
     * Shrunk about the listener rather than nudged, when it does not fit. A shrink about the
     * listener is the one change the room is blind to - every gain reads a direction or a ratio of
     * radii, and both survive it - whereas moving one icon in off the edge would quietly undo the
     * correction that put it there.
     */
    private fun contained(placement: Placement): List<RoomIcon> {
        val reach = placement.index.values.maxOf {
            maxOf(abs(placement.x[it]), abs(placement.y[it]))
        }
        val room = SpatialRoom.CENTRE.toDouble() - MARGIN
        val shrink = if (reach > room) room / reach else 1.0
        return placement.index.entries
            .sortedBy { it.value }
            .map { (peerId, at) ->
                SpatialRoom.clamped(
                    RoomIcon(
                        peerId,
                        SpatialRoom.CENTRE + (placement.x[at] * shrink).toFloat(),
                        SpatialRoom.CENTRE + (placement.y[at] * shrink).toFloat()
                    )
                )
            }
    }

    /** How much of the drawing is kept clear of the edge, so an icon stays whole and draggable. */
    private const val MARGIN = 0.04
}
