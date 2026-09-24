package com.soundmesh.product

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Moving the icons onto what the room measured, keeping everything the measurement cannot know.
 *
 * A person is good at one half of this drawing and bad at the other. Where the room is, which way
 * it faces, which side is left, roughly where each handset sits - all of that they can see, and
 * none of it can be measured by a room that only takes handset to handset distances. What they are
 * bad at is the half a tape measure is good
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
/**
 * A fitted room, and the one thing the drawing has never carried: how large it is.
 *
 * The drawing is dimensionless and every gain is happy with that - a gain is a ratio of two
 * radii, so the same room drawn larger renders identically. The arrival delay is the first
 * reader that is not a ratio: two metres is 5.8 ms whatever the drawing looks like.
 */
data class FittedRoom(
    val icons: List<RoomIcon>,
    /** How many metres one unit of the drawing is, or zero when this fit cannot say. */
    val metresPerUnit: Double
)

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

    /**
     * The listener, while the fit is running, under a name no handset can be called.
     *
     * Handset names are sixteen lowercase hexadecimal characters (HostId), so this collides with
     * nothing, and it never leaves this file: it goes in as one more node with one more set of
     * edges, and comes out as the origin everything else is measured from.
     */
    private const val LISTENER = "listener"

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
        listenerMetres: Map<String, Double> = emptyMap(),
        priorWeight: Double = DEFAULT_PRIOR_WEIGHT
    ): List<RoomIcon>? = fitted(icons, measuredMetres, listenerMetres, priorWeight)?.icons

    /**
     * The same answer with the scale it was found at, which [corrected] throws away.
     *
     * Two functions rather than one return type everywhere because almost nothing wants the
     * scale: the drawing does not, the gains do not, and the check that reads the drawing
     * against the measurement does not. One reader does, and it is new.
     */
    fun fitted(
        icons: List<RoomIcon>,
        measuredMetres: Map<Pair<String, String>, Double>,
        listenerMetres: Map<String, Double> = emptyMap(),
        priorWeight: Double = DEFAULT_PRIOR_WEIGHT
    ): FittedRoom? {
        require(priorWeight > 0.0) {
            "a fit with no prior is free to rotate and mirror the room, and every gain reads that"
        }
        val toListener = listenerEdges(icons, listenerMetres)
        // The listener joins the fit as an ordinary node when it was measured, and the room comes
        // out shifted so that it lands back in the middle of the drawing. Nothing downstream has to
        // learn about it: the middle is where every gain already measures from.
        val nodes =
            if (toListener.isEmpty()) icons
            else icons + RoomIcon(LISTENER, SpatialRoom.CENTRE, SpatialRoom.CENTRE)
        val edges = edgesAmong(icons, measuredMetres) + toListener
        // Shape needs a handset that two measured edges meet at. Without one there are only
        // separate lengths, and a length on its own is a scale.
        val meetingAt = HashMap<String, Int>()
        for (edge in edges) {
            meetingAt[edge.first] = (meetingAt[edge.first] ?: 0) + 1
            meetingAt[edge.second] = (meetingAt[edge.second] ?: 0) + 1
        }
        if (meetingAt.values.none { it >= 2 }) return null

        if (worstEdgeMetres(placed(nodes, edges, GAUGE_WEIGHT), edges) > WORST_EDGE_METRES) return null

        // Brought back onto the drawing before the mirror is looked for, not after: the shrink
        // that does it is uniform about the listener, which cannot turn anything over.
        val placement = centredOnListener(placed(nodes, edges, priorWeight))
        val shrink = shrinkOf(placement)
        val fitted = contained(placement, shrink).filterNot { it.peerId == LISTENER }
        val moved = edges.flatMap { listOf(it.first, it.second) }.toSet()
        val before = icons.filter { it.peerId in moved }
        val after = fitted.filter { it.peerId in moved }
        if (turnedInsideOut(before, after)) return null
        return FittedRoom(fitted, metresPerUnit(placement, shrink, toListener.isNotEmpty()))
    }

    /**
     * How many metres one unit of this drawing turned out to be, or zero when it may not be used.
     *
     * Zero unless the overhead round measured the listener, and that is a decision rather than a
     * missing case. A room measured handset to handset knows perfectly well how large it is; what
     * it does not know is where anybody is sitting, because the listener is assumed to be in the
     * middle of the handsets. The only reader of this is the arrival delay, and a delay measured
     * from an assumed listener holds the wrong handset back - which adds the error it was meant
     * to remove rather than merely failing to remove it.
     *
     * The shrink is carried in because [contained] may have scaled the whole answer down to fit
     * the screen, and a scale read before that shrink is wrong by exactly it.
     */
    private fun metresPerUnit(placement: Placement, shrink: Double, listenerMeasured: Boolean): Double {
        if (!listenerMeasured) return 0.0
        val unitsPerMetre = placement.unitsPerMetre * shrink
        if (unitsPerMetre <= 0.0 || !unitsPerMetre.isFinite()) return 0.0
        return 1.0 / unitsPerMetre
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

    /**
     * What the overhead round said, as edges, or nothing when it did not say enough.
     *
     * Two at least. One distance to the listener is a circle around one handset, and which point
     * on it gets picked would come from the drawing - which is the drawing answering the one
     * question the overhead round was run to stop it answering.
     */
    private fun listenerEdges(icons: List<RoomIcon>, listenerMetres: Map<String, Double>): List<Edge> {
        val here = icons.map { it.peerId }.toSet()
        val reachable = listenerMetres.filter { it.key in here && it.value > 0.0 }
        if (reachable.size < 2) return emptyList()
        return reachable.map { Edge(LISTENER, it.key, it.value) }
    }

    /**
     * The same room, slid over so that the measured listener is the origin again.
     *
     * A translation, so every measured length survives it untouched; what changes is the only
     * thing playback actually reads, which is where each handset lies **from the listener**. This
     * is where drawing yourself in the middle of the handsets stops being the answer.
     */
    private fun centredOnListener(placement: Placement): Placement {
        val at = placement.index[LISTENER] ?: return placement
        val byX = placement.x[at]
        val byY = placement.y[at]
        return Placement(
            DoubleArray(placement.x.size) { placement.x[it] - byX },
            DoubleArray(placement.y.size) { placement.y[it] - byY },
            placement.index,
            placement.unitsPerMetre
        )
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
    private fun contained(placement: Placement, shrink: Double): List<RoomIcon> {
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

    /** How much [contained] has to shrink the answer by, or one when it already fits. */
    private fun shrinkOf(placement: Placement): Double {
        val reach = placement.index.values.maxOf {
            maxOf(abs(placement.x[it]), abs(placement.y[it]))
        }
        val room = SpatialRoom.CENTRE.toDouble() - MARGIN
        return if (reach > room) room / reach else 1.0
    }

    /** How much of the drawing is kept clear of the edge, so an icon stays whole and draggable. */
    private const val MARGIN = 0.04
}
