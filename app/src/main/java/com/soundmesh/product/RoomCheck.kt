package com.soundmesh.product

import kotlin.math.hypot

/**
 * The little a measured distance can say about a drawing.
 *
 * The drawing is the only thing in the system that knows which side is left: three measured
 * distances fix a triangle's shape and say nothing about its orientation or its mirror image, so
 * a measurement can never replace the drag. What it can do is catch one particular mistake - two
 * icons dragged onto the wrong phones - whose symptom is otherwise unattributable, because a room
 * with two handsets swapped sounds exactly like a working room that was drawn differently.
 *
 * What it compares is handset to handset, not listener to handset: the calibration measures the
 * distance between two phones and the drawing gives the distance between two icons. The listener
 * never enters it, which is just as well - nobody has measured where they are sitting.
 *
 * It says nothing at all with fewer than two measured distances, and a two-handset room only ever
 * has one: one measured length against one drawn length is a scale, and scale is the thing the
 * drawing deliberately does not carry. The check needs a third phone to have anything to compare.
 */
object RoomCheck {
    /**
     * How far apart two distances have to be before either is called clearly longer.
     *
     * Wide, and deliberately so. The drawing is a rough sketch by hand - "the shape, not the size"
     * is what it was asked for - so it is only the ordering that can be trusted, and only when
     * both sides agree the two lengths are plainly different. Anything narrower would flag honest
     * sketches, and a warning that fires on correct drawings is one a person learns to ignore.
     */
    const val CLEARLY_LONGER = 1.5

    /**
     * Two handsets the drawing puts the wrong way round, or null when nothing contradicts.
     *
     * [measuredMetres] holds the distances from [selfId] to peers it has been calibrated against.
     * A peer with no measurement simply takes no part, which is the ordinary case: the room can
     * have handsets in it that this one has never run a calibration with.
     */
    fun contradiction(
        icons: List<RoomIcon>,
        selfId: String,
        measuredMetres: Map<String, Double>
    ): Pair<String, String>? {
        val self = icons.firstOrNull { it.peerId == selfId } ?: return null
        val drawn = icons
            .filter { it.peerId != selfId }
            .mapNotNull { icon ->
                val metres = measuredMetres[icon.peerId] ?: return@mapNotNull null
                if (metres <= 0.0) return@mapNotNull null
                val apart = hypot((icon.x - self.x).toDouble(), (icon.y - self.y).toDouble())
                if (apart <= 0.0) return@mapNotNull null
                Triple(icon.peerId, apart, metres)
            }
        // No guard on the count: with fewer than two the loops below have no pair to look at and
        // say nothing, which is the same answer written once instead of twice.
        for (first in drawn.indices) {
            for (second in first + 1 until drawn.size) {
                val (nameA, drawnA, metresA) = drawn[first]
                val (nameB, drawnB, metresB) = drawn[second]
                // Both sides have to be sure, and sure of opposite things. One side merely being
                // undecided is a sketch that is not precise enough to disagree, which is most of
                // them and is not a fault.
                val measuredSaysFarther = metresA / metresB >= CLEARLY_LONGER
                val measuredSaysNearer = metresB / metresA >= CLEARLY_LONGER
                val drawnSaysFarther = drawnA / drawnB >= CLEARLY_LONGER
                val drawnSaysNearer = drawnB / drawnA >= CLEARLY_LONGER
                if (measuredSaysFarther && drawnSaysNearer) return nameA to nameB
                if (measuredSaysNearer && drawnSaysFarther) return nameB to nameA
            }
        }
        return null
    }
}
