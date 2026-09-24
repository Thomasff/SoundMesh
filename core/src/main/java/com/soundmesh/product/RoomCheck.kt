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
 * It looks from every handset in turn rather than only from this one, and that is the whole of
 * what a measured room adds over a measured pair. A pair of phones can only ever produce the
 * distance between the two of them, so until a room ran, every length this had went from here
 * to somewhere - and two handsets swapped with each other at the same distance from here are
 * invisible in that set. From a third phone they are not: it stands somewhere else, so the two
 * are two different lengths to it.
 *
 * The comparison stays between two lengths that share an end. That is not caution about the
 * arithmetic, it is about what can be said afterwards: two lengths sharing a handset disagree
 * in a way that names two icons and asks whether they are the right way round, and two lengths
 * with four different ends between them disagree in a way nobody can act on.
 *
 * It asks the measurement for an ordering rather than a length, which sounds like the cheaper
 * of the two and is not: an ordering the wrong way round is an accusation. What that costs is
 * written under [CLEARLY_LONGER], and which runs are allowed to produce a distance at all is
 * decided in PeerCalibrateActivity.separationToStore.
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
     *
     * Wide is not free, and widening it further does not help. A drawing that is right is
     * accused when the measured ratio inverts the drawn one, which takes the two distances'
     * errors differing by a factor of this squared - 2.25 - and takes less than that as the
     * drawing grows more decisive. So the margin is a demand on the measurement, and raising
     * it raises the demand on the drawing in exactly the same step. Measured 09-11: nine
     * readings of one unchanged two metre gap, clear line of sight, spanned 5.11 to 11.86 m
     * read from the loudest lag - a ratio of 2.32, past the bound with the drawing exactly
     * right - and 1.97 to 2.19 m read from the first arrival, a ratio of 1.11.
     */
    const val CLEARLY_LONGER = 1.5

    /**
     * Two handsets the drawing puts the wrong way round, or null when nothing contradicts.
     *
     * [measuredMetres] is keyed by the two handsets a distance is between, and is expected to
     * hold both orders of every pair it knows - see [com.soundmesh.probe.sync.StoredRoomField],
     * which is where most of them come from. A pair with no measurement simply takes no part,
     * which is the ordinary state: a room can hold handsets that nothing has ever measured.
     */
    fun contradiction(
        icons: List<RoomIcon>,
        measuredMetres: Map<Pair<String, String>, Double>
    ): Pair<String, String>? {
        for (from in icons) {
            seenFrom(icons, from, measuredMetres)?.let { return it }
        }
        return null
    }

    /**
     * The same question asked from one handset: of the peers it has a measured distance to, are
     * two of them drawn in the opposite order to the one that was measured.
     *
     * The first viewpoint that finds something answers, rather than the worst or the most
     * frequent. What comes out of here is one sentence asking a person to look at two icons, and
     * a second sentence about the same swap seen from somewhere else adds nothing to it.
     */
    private fun seenFrom(
        icons: List<RoomIcon>,
        from: RoomIcon,
        measuredMetres: Map<Pair<String, String>, Double>
    ): Pair<String, String>? {
        val drawn = icons
            .filter { it.peerId != from.peerId }
            .mapNotNull { icon ->
                val metres = measuredMetres[from.peerId to icon.peerId] ?: return@mapNotNull null
                if (metres <= 0.0) return@mapNotNull null
                val apart = hypot((icon.x - from.x).toDouble(), (icon.y - from.y).toDouble())
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
