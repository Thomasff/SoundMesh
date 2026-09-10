package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

class SpatialRoomTest {
    private val a = "a1b2c3d4e5f60718"
    private val b = "0918273645abcdef"
    private val c = "1122334455667788"

    private fun radiusOf(icon: RoomIcon) = hypot(
        (icon.x - SpatialRoom.CENTRE).toDouble(), (icon.y - SpatialRoom.CENTRE).toDouble()
    )

    /**
     * The flip, which is the only thing in this file that can be wrong in a way nobody hears. An
     * icon dragged above the listener is a phone in front of them; get the sign wrong and the room
     * is mirrored front to back, which sounds like a working room playing a different drawing.
     */
    @Test
    fun anIconAboveTheListenerIsAPhoneInFrontOfThem() {
        val layout = SpatialRoom.layoutOf(listOf(RoomIcon(a, 0.5f, 0.2f)))!!

        assertEquals(0.0, layout.azimuthOf(a), 1e-9)
    }

    /** And the other flip. Right on the screen has to be right in the room, or so is the panning. */
    @Test
    fun anIconRightOfTheListenerIsAPhoneToTheirRight() {
        val layout = SpatialRoom.layoutOf(listOf(RoomIcon(a, 0.8f, 0.5f)))!!

        assertEquals(PI / 2.0, layout.azimuthOf(a), 1e-9)
    }

    @Test
    fun twoHandsetsStartEvenlySplitAboutStraightAhead() {
        val icons = SpatialRoom.defaultIcons(listOf(a, b))
        val layout = SpatialRoom.layoutOf(icons)!!

        // 1e-6, not tighter: the icons are Float, so a comparison below float's own precision
        // would be testing the rounding rather than the symmetry. A mirrored room is out by pi.
        assertEquals(-layout.azimuthOf(a), layout.azimuthOf(b), 1e-6)
        assertTrue("the first handset should start on the left", layout.azimuthOf(a) < 0)
    }

    /**
     * In front, because the phones are: somebody put them on a table and is looking at them, and a
     * default that starts half the room behind asks them to undo it before they can start.
     */
    @Test
    fun everybodyStartsInFrontOfTheListener() {
        val layout = SpatialRoom.layoutOf(SpatialRoom.defaultIcons(listOf(a, b, c)))!!

        for (peer in listOf(a, b, c)) {
            assertTrue("$peer started behind the listener", abs(layout.azimuthOf(peer)) <= PI / 2.0 + 1e-9)
        }
    }

    @Test
    fun oneHandsetStartsStraightAhead() {
        val layout = SpatialRoom.layoutOf(SpatialRoom.defaultIcons(listOf(a)))!!

        assertEquals(0.0, layout.azimuthOf(a), 1e-9)
    }

    /**
     * A handset on the listener has no direction, so a drawing cannot hold one. Clamped rather
     * than refused: a finger passing over the middle would otherwise make the room undrawable
     * mid-drag, and what a person would see is the whole panel failing as they moved.
     */
    @Test
    fun anIconDraggedOntoTheListenerIsPushedBackOut() {
        val clamped = SpatialRoom.clamped(RoomIcon(a, 0.51f, 0.49f))

        assertEquals(SpatialRoom.MIN_RADIUS.toDouble(), radiusOf(clamped), 1e-6)
    }

    /** Exactly on the listener there is no direction to push back along, so it goes in front. */
    @Test
    fun anIconExactlyOnTheListenerGoesInFront() {
        val layout = SpatialRoom.layoutOf(listOf(RoomIcon(a, SpatialRoom.CENTRE, SpatialRoom.CENTRE)))!!

        assertEquals(0.0, layout.azimuthOf(a), 1e-9)
    }

    /** An icon well clear of the middle is left exactly where the finger put it. */
    @Test
    fun anIconClearOfTheListenerIsNotMoved() {
        val placed = RoomIcon(a, 0.9f, 0.1f)

        assertEquals(placed, SpatialRoom.clamped(placed))
    }

    @Test
    fun anEmptyRoomDrawsNothing() {
        assertNull(SpatialRoom.layoutOf(emptyList()))
        assertEquals(emptyList<RoomIcon>(), SpatialRoom.defaultIcons(emptyList()))
    }

    /**
     * Scale has no consumer: every gain depends on direction alone. Pinned as a test rather than
     * left as a comment, because the day something starts reading a distance is the day this stops
     * being true and nothing else would say so.
     */
    @Test
    fun aDrawingEnlargedIsTheSameRoom() {
        val small = SpatialRoom.layoutOf(listOf(RoomIcon(a, 0.55f, 0.45f), RoomIcon(b, 0.45f, 0.48f)))!!
        val large = SpatialRoom.layoutOf(listOf(RoomIcon(a, 0.90f, 0.10f), RoomIcon(b, 0.10f, 0.34f)))!!

        assertEquals(small.azimuthOf(a), large.azimuthOf(a), 1e-6)
        assertEquals(small.azimuthOf(b), large.azimuthOf(b), 1e-6)
    }
/**
     * The roster is re-read several times a second. A drawing rebuilt from it each time would
     * throw away every drag the moment anything else in the room changed, and what a person would
     * see is their arrangement springing back while they were still looking at it.
     */
    @Test
    fun aHandsetThatIsStillHereKeepsWhereItWasPut() {
        val dragged = RoomIcon(a, 0.81f, 0.17f)

        val room = SpatialRoom.reconciled(listOf(dragged), listOf(a, b))

        assertEquals(dragged, room.first())
        assertEquals(listOf(a, b), room.map { it.peerId })
    }

    @Test
    fun aHandsetThatLeftIsDroppedFromTheDrawing() {
        val room = SpatialRoom.reconciled(SpatialRoom.defaultIcons(listOf(a, b, c)), listOf(a, c))

        assertEquals(listOf(a, c), room.map { it.peerId })
    }

    @Test
    fun aHandsetThatJustJoinedLandsWhereTheDefaultWouldPutIt() {
        val room = SpatialRoom.reconciled(emptyList(), listOf(a, b))

        assertEquals(SpatialRoom.defaultIcons(listOf(a, b)), room)
    }

    /**
     * A name arriving twice draws one icon, because a drawing is what this is for.
     *
     * The roster it reads is built from connections rather than from handsets, and a handset that
     * dropped and came back was in it twice - which SpatialLayout refuses to draw, by construction
     * and rightly, so the refusal arrived as the host's process ending. The roster is fixed where
     * it is built; this is the drawing declining to be the place a bad roster becomes a crash.
     */
    @Test
    fun aNameThatArrivesTwiceIsOneIcon() {
        val room = SpatialRoom.reconciled(emptyList(), listOf(a, b, a))

        assertEquals(listOf(a, b), room.map { it.peerId })
    }

    /** And the placed icon is kept, not replaced by the default the duplicate would carry. */
    @Test
    fun aDuplicateDoesNotMoveTheIconAlreadyPlaced() {
        val dragged = RoomIcon(a, 10f, 20f)

        val room = SpatialRoom.reconciled(listOf(dragged), listOf(a, b, a))

        assertEquals(dragged, room.first())
    }

    /**
     * A handset that left has to leave the assignment along with its icon.
     *
     * This is not tidiness. A rule naming a handset the drawing does not show is one SpatialField
     * refuses to build, and the screen publishes on a five-a-second refresh with that refusal caught
     * and logged rather than thrown - so a name left behind here does not crash anything, it stops
     * the room being published at all, silently, until somebody notices the phones went flat.
     *
     * The same shape as the drawing needing [SpatialRoom.reconciled]: two places holding one roster.
     */
    @Test
    fun aHandsetThatLeftStopsCarryingTheSides() {
        assertEquals(setOf(b), SpatialRoom.reconciledSides(setOf(a, b), listOf(b, c)))
    }

    /** A handset still here keeps the part it was given, or every roster change would undo the choice. */
    @Test
    fun aHandsetStillHereKeepsThePartItWasGiven() {
        assertEquals(setOf(a), SpatialRoom.reconciledSides(setOf(a), listOf(a, b, c)))
    }
}
