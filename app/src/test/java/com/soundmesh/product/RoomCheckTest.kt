package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoomCheckTest {
    private val host = "a1b2c3d4e5f60718"
    private val near = "0918273645abcdef"
    private val far = "1122334455667788"

    /** The host in the middle, one icon close beside it and one well away. */
    private fun drawing(nearAt: Float, farAt: Float) = listOf(
        RoomIcon(host, 0.5f, 0.5f),
        RoomIcon(near, 0.5f + nearAt, 0.5f),
        RoomIcon(far, 0.5f - farAt, 0.5f)
    )

    /** Distances, held both ways round the way the stored field hands them over. */
    private fun field(vararg between: Triple<String, String, Double>): Map<Pair<String, String>, Double> {
        val metres = LinkedHashMap<Pair<String, String>, Double>()
        for ((a, b, apart) in between) {
            metres[a to b] = apart
            metres[b to a] = apart
        }
        return metres
    }

    /** Measured: `near` is a metre from the host and `far` is four. */
    private val measured = field(Triple(host, near, 1.0), Triple(host, far, 4.0))

    /**
     * The mistake this exists for: two icons dragged onto the wrong phones. Its symptom is a
     * rotation running backwards, or a pan that moves the wrong way, with nothing on screen out of
     * place - which is why it is worth a check that does not depend on anybody noticing.
     */
    @Test
    fun aDrawingThatPutsTheFarPhoneNearIsContradicted() {
        // Drawn the wrong way round: the phone measured four metres away is drawn closest.
        val swapped = drawing(nearAt = 0.30f, farAt = 0.06f)

        assertEquals(far to near, RoomCheck.contradiction(swapped, measured))
    }

    @Test
    fun aDrawingThatAgreesWithTheMeasurementIsLeftAlone() {
        assertNull(RoomCheck.contradiction(drawing(0.06f, 0.30f), measured))
    }

    /**
     * A rough sketch is what was asked for, so only the ordering is trusted, and only when both
     * sides say the two lengths are plainly different. A drawing that is merely imprecise about
     * which is farther is not wrong - it is a sketch - and a warning that fires on those is one a
     * person learns to ignore.
     */
    @Test
    fun aSketchThatIsMerelyImpreciseIsNotCalledWrong() {
        // Drawn slightly the wrong way round - the far phone a shade nearer - which is what an
        // honest sketch of a room looks like. A drawing that merely agreed with the measurement
        // would pass at any margin and would be testing nothing.
        assertNull(RoomCheck.contradiction(drawing(nearAt = 0.22f, farAt = 0.20f), measured))
    }

    /** And the other way: measurements too close together to order cannot contradict anything. */
    @Test
    fun measurementsTooCloseTogetherSayNothing() {
        val similar = field(Triple(host, near, 2.0), Triple(host, far, 2.2))

        assertNull(RoomCheck.contradiction(drawing(0.30f, 0.06f), similar))
    }

    /**
     * Two handsets have one edge between them, and one measured length against one drawn length is
     * a scale - which the drawing deliberately does not carry. The check needs a third phone
     * before it has anything to compare, and saying so is the whole of what it can do here.
     */
    @Test
    fun aTwoHandsetRoomCannotBeCheckedAtAll() {
        val pair = listOf(RoomIcon(host, 0.5f, 0.5f), RoomIcon(near, 0.8f, 0.5f))

        assertNull(RoomCheck.contradiction(pair, field(Triple(host, near, 1.0))))
    }

    /** A pair nothing has ever measured takes no part rather than blocking the check. */
    @Test
    fun anUnmeasuredHandsetSimplyTakesNoPart() {
        val stranger = "99887766554433aa"
        val room = drawing(0.30f, 0.06f) + RoomIcon(stranger, 0.5f, 0.9f)

        assertEquals(far to near, RoomCheck.contradiction(room, measured))
    }

    // -- what a measured room adds over a measured pair ----------------------------------------

    private val third = "99887766554433aa"

    /**
     * The swap this could never have caught before: two handsets exchanged with each other at the
     * same distance from here.
     *
     * Every length a pair of phones can measure starts at one of the two, so until a room ran, all
     * this had were lengths from here to somewhere - and those two handsets are the same length
     * from here either way round. A third phone stands somewhere else, so to it they are two
     * different lengths, and the exchange shows.
     *
     * What comes back is whichever viewpoint noticed first, not a named culprit: a swap leaves
     * several pairs of lengths disagreeing, and the check has only ever offered one sentence
     * asking a person to look at two icons.
     */
    @Test
    fun aSwapInvisibleFromHereIsSeenFromAnotherHandset() {
        // `near` and `far` exchanged, so each stands where the other should. `third` sits beside
        // where `far` really is.
        val swapped = listOf(
            RoomIcon(host, 0.5f, 0.5f),
            RoomIcon(near, 0.7f, 0.5f),
            RoomIcon(far, 0.3f, 0.5f),
            RoomIcon(third, 0.72f, 0.55f)
        )
        val fromHostOnly = field(
            Triple(host, near, 2.0),
            Triple(host, far, 2.0),
            Triple(host, third, 2.1)
        )
        val whole = fromHostOnly + field(
            Triple(near, far, 4.0),
            Triple(near, third, 2.5),
            Triple(far, third, 0.3)
        )

        // Every length that touches this handset agrees with the drawing, because the two swapped
        // handsets are equally far from it.
        assertNull(RoomCheck.contradiction(swapped, fromHostOnly))
        // The rest of the field does not.
        assertNotNull(RoomCheck.contradiction(swapped, whole))
    }

    /**
     * And a contradiction made of two lengths neither of which touches this handset is one it can
     * report. Before a room could be measured there was no such length to have.
     */
    @Test
    fun aContradictionBetweenTwoLengthsThatMissThisHandsetIsStillFound() {
        val room = listOf(
            RoomIcon(host, 0.5f, 0.5f),
            RoomIcon(near, 0.5f, 0.1f),
            RoomIcon(far, 0.9f, 0.9f),
            RoomIcon(third, 0.55f, 0.15f)
        )
        // Nothing has ever measured a distance from the host, and it takes no part on that account.
        val between = field(Triple(near, far, 1.0), Triple(near, third, 4.0))

        // From `near`: `third` was measured four times as far as `far` and is drawn right beside it.
        assertEquals(third to far, RoomCheck.contradiction(room, between))
    }
}
