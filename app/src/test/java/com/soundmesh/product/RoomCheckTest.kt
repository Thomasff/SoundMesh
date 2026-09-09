package com.soundmesh.product

import org.junit.Assert.assertEquals
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

    /** Measured: `near` is a metre away and `far` is four. */
    private val measured = mapOf(near to 1.0, far to 4.0)

    /**
     * The mistake this exists for: two icons dragged onto the wrong phones. Its symptom is a
     * rotation running backwards, or a pan that moves the wrong way, with nothing on screen out of
     * place - which is why it is worth a check that does not depend on anybody noticing.
     */
    @Test
    fun aDrawingThatPutsTheFarPhoneNearIsContradicted() {
        // Drawn the wrong way round: the phone measured four metres away is drawn closest.
        val swapped = drawing(nearAt = 0.30f, farAt = 0.06f)

        assertEquals(far to near, RoomCheck.contradiction(swapped, host, measured))
    }

    @Test
    fun aDrawingThatAgreesWithTheMeasurementIsLeftAlone() {
        assertNull(RoomCheck.contradiction(drawing(0.06f, 0.30f), host, measured))
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
        assertNull(RoomCheck.contradiction(drawing(nearAt = 0.22f, farAt = 0.20f), host, measured))
    }

    /** And the other way: measurements too close together to order cannot contradict anything. */
    @Test
    fun measurementsTooCloseTogetherSayNothing() {
        val similar = mapOf(near to 2.0, far to 2.2)

        assertNull(RoomCheck.contradiction(drawing(0.30f, 0.06f), host, similar))
    }

    /**
     * Two handsets have one edge between them, and one measured length against one drawn length is
     * a scale - which the drawing deliberately does not carry. The check needs a third phone
     * before it has anything to compare, and saying so is the whole of what it can do here.
     */
    @Test
    fun aTwoHandsetRoomCannotBeCheckedAtAll() {
        val pair = listOf(RoomIcon(host, 0.5f, 0.5f), RoomIcon(near, 0.8f, 0.5f))

        assertNull(RoomCheck.contradiction(pair, host, mapOf(near to 1.0)))
    }

    /** A handset this one has never calibrated with takes no part rather than blocking the check. */
    @Test
    fun anUnmeasuredHandsetSimplyTakesNoPart() {
        val stranger = "99887766554433aa"
        val room = drawing(0.30f, 0.06f) + RoomIcon(stranger, 0.5f, 0.9f)

        assertEquals(far to near, RoomCheck.contradiction(room, host, measured))
    }

    @Test
    fun aRoomWithNoIconForThisHandsetCannotBeChecked() {
        val withoutHost = drawing(0.30f, 0.06f).filter { it.peerId != host }

        assertNull(RoomCheck.contradiction(withoutHost, host, measured))
    }
}
