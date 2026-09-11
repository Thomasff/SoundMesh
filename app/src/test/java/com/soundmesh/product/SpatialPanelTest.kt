package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which phone a finger meant.
 *
 * Getting this wrong swaps two handsets, which is the one defect in this screen a listener cannot
 * diagnose by ear: a swapped pair sounds exactly like a working room that was drawn differently.
 */
class SpatialPanelTest {
    private val a = "a1b2c3d4e5f60718"
    private val b = "0918273645abcdef"

    private val room = listOf(RoomIcon(a, 0.3f, 0.3f), RoomIcon(b, 0.7f, 0.3f))

    @Test
    fun aFingerOnAnIconMeansThatIcon() {
        assertEquals(a, nearestPeerId(room, 0.31f, 0.29f))
        assertEquals(b, nearestPeerId(room, 0.69f, 0.31f))
    }

    /**
     * Between two icons the nearer one wins, rather than the first one in the list. A tie-break by
     * order would make the room's own drawing order decide which phone moves, and that order comes
     * from whoever connected first.
     */
    @Test
    fun betweenTwoIconsTheNearerOneWins() {
        // A close pair, because the midpoint of the wide one is out of reach of both - which is
        // the reach working, and not what this test is about.
        val close = listOf(RoomIcon(a, 0.45f, 0.3f), RoomIcon(b, 0.55f, 0.3f))

        assertEquals(a, nearestPeerId(close, 0.49f, 0.3f))
        assertEquals(b, nearestPeerId(close, 0.51f, 0.3f))
    }

    /**
     * A finger on empty canvas picks nothing up. Without the reach, a tap anywhere would teleport
     * whichever icon happened to be least far away - including one on the far side of the room.
     */
    @Test
    fun aFingerOnNothingPicksUpNothing() {
        assertNull(nearestPeerId(room, 0.5f, 0.95f))
        assertNull(nearestPeerId(emptyList(), 0.3f, 0.3f))
    }

    /** Just inside the reach still counts, so the edge of the grab is where it says it is. */
    @Test
    fun theReachIsAsWideAsItSays() {
        assertEquals(a, nearestPeerId(listOf(room[0]), 0.3f, (0.3 + GRAB_RADIUS * 0.99).toFloat()))
        assertNull(nearestPeerId(listOf(room[0]), 0.3f, (0.3 + GRAB_RADIUS * 1.01).toFloat()))
    }

    /**
     * The field holds both orders of every pair, so that a caller with two names need not know
     * which sorts first. A list that took it at face value would print every distance twice.
     */
    @Test
    fun eachMeasuredPairIsReadOutOnce() {
        val measured = mapOf((a to b) to 2.02, (b to a) to 2.02)

        // Kept in the order the two names sort in, which is the one order that does not depend
        // on who connected first.
        assertEquals(listOf((b to a) to 2.02), measuredLines(room, measured))
    }

    /**
     * A field outlives the room it was measured in - it is replaced when the next room is
     * measured, not when somebody goes home - so a distance to a handset that is no longer in the
     * drawing is a line about nothing the person can look at.
     */
    @Test
    fun aDistanceToAHandsetNoLongerInTheDrawingIsNotReadOut() {
        val gone = "1122334455667788"
        val measured = mapOf(
            (a to b) to 2.02, (b to a) to 2.02,
            (a to gone) to 3.00, (gone to a) to 3.00
        )

        assertEquals(listOf((b to a) to 2.02), measuredLines(room, measured))
    }

    // One room of three, drawn right, so that the offer depends on nothing but the rules below.
    private val c = "1122334455667788"
    private val three = listOf(
        RoomIcon(a, 0.50f, 0.20f),
        RoomIcon(b, 0.26f, 0.68f),
        RoomIcon(c, 0.82f, 0.60f)
    )
    private val threeMeasured = mapOf(
        (a to b) to 2.6833, (b to a) to 2.6833,
        (a to c) to 2.5613, (c to a) to 2.5613,
        (b to c) to 2.8284, (c to b) to 2.8284
    )

    // What an overhead round would have said about the same three, with the person a metre behind
    // the middle of them - which is where people sit and where the drawing cannot say they are.
    private val threeFromTheListener = mapOf(a to 2.0, b to 1.5811, c to 1.5811)

    /**
     * The offer takes the listener along when there is one, which is the whole of what an overhead
     * round buys: the drawing says where the handsets are and it can never say where the person is.
     */
    @Test
    fun `the fit is offered what the overhead round measured`() {
        val without = fitOffer(RoomState(icons = three, measuredMetres = threeMeasured))!!
        val with = fitOffer(
            RoomState(
                icons = three,
                measuredMetres = threeMeasured,
                listenerMetres = threeFromTheListener
            )
        )!!

        assertNotEquals(without, with)
    }

    /** A distance to somebody who went home is a line about nothing the person can look at. */
    @Test
    fun `a listener distance to a handset no longer in the drawing is not read out`() {
        val lines = listenerLines(listOf(three[0]), threeFromTheListener)

        assertEquals(listOf(a to 2.0), lines)
    }

    /** Nothing to offer about a room nothing has measured, which is most rooms. */
    @Test
    fun aRoomNothingHasMeasuredIsOfferedNothing() {
        assertNull(fitOffer(RoomState(icons = three)))
    }

    @Test
    fun aMeasuredRoomIsOfferedAFit() {
        assertNotNull(fitOffer(RoomState(icons = three, measuredMetres = threeMeasured)))
    }

    /**
     * And nothing while the check is pointing at two icons.
     *
     * The fit moves positions to match labels. Two icons on the wrong handsets is the labels
     * being wrong, so fitting one would move each icon onto the other one's measured place -
     * confidently, and without saying anything. The swap has to be settled by the person first.
     */
    @Test
    fun aRoomWithTwoIconsTheWrongWayRoundIsOfferedNothing() {
        // c drawn right beside a and b far off, against a measurement saying the opposite.
        val swapped = listOf(
            RoomIcon(a, 0.20f, 0.30f),
            RoomIcon(b, 0.80f, 0.75f),
            RoomIcon(c, 0.28f, 0.38f)
        )
        val contradicting = mapOf(
            (a to b) to 1.0, (b to a) to 1.0,
            (a to c) to 2.5, (c to a) to 2.5,
            (b to c) to 2.0, (c to b) to 2.0
        )
        // Both halves stated, or this passes for the wrong reason: the check does see it, and
        // the fit on its own would have gone ahead. What refuses is the order they run in.
        assertNotNull(RoomCheck.contradiction(swapped, contradicting))
        assertNotNull(RoomFit.corrected(swapped, contradicting))

        assertNull(fitOffer(RoomState(icons = swapped, measuredMetres = contradicting)))
    }

    /** And nothing twice for the same drawing: a fit of a fit is no longer anybody's opinion. */
    @Test
    fun aDrawingAlreadyFittedIsOfferedNothing() {
        val state = RoomState(icons = three, measuredMetres = threeMeasured, fitted = true)

        assertNull(fitOffer(state))
    }

    /** Until a finger moves something, which makes it somebody's opinion again. */
    @Test
    fun movingAnIconOpensTheOfferAgain() {
        val state = RoomState(icons = three, measuredMetres = threeMeasured, fitted = true)

        val moved = withIconMoved(state, RoomIcon(c, 0.70f, 0.55f))

        assertNotNull(fitOffer(moved))
        assertEquals(RoomIcon(c, 0.70f, 0.55f), moved.icons.first { it.peerId == c })
        assertEquals(three[0], moved.icons[0])
    }

    /**
     * The one line on the screen that says the delay is switched on. Everything else about the
     * feature is inaudible on purpose: it exists to make two handsets sound like one.
     */
    @Test
    fun `what each handset waits is read out, in the order the room is drawn in`() {
        val lines = delayLines(three, metresPerUnit = 1.0)

        assertEquals(three.map { it.peerId }, lines.map { it.first })
        // c is the furthest of the three and so waits for nobody; the other two wait for it.
        assertEquals(0.0, lines.first { it.first == c }.second, 0.0)
        assertTrue(lines.first { it.first == a }.second > 0.0)
    }

    /** And nothing at all until the overhead round and the fit have both happened. */
    @Test
    fun `a room with no measured scale reads out no delays`() {
        assertEquals(emptyList<Pair<String, Double>>(), delayLines(three, metresPerUnit = 0.0))
    }

    /**
     * Two handsets cannot do the overhead round, and the button is not offered for one.
     *
     * The handset held over somebody's head cannot measure its own distance to that head - it is
     * the head - so the listener comes out of the round with N-1 distances. Two handsets leave
     * one, which is a circle around a single phone and places nobody; three leave two, which
     * places them up to a mirror the drawing settles. What is under the line is not a worse
     * answer, it is no answer, and a minute of somebody standing still holding a phone.
     */
    @Test
    fun `two handsets cannot place a listener and three can`() {
        assertEquals(false, overheadRoundCanPlaceTheListener(three.take(2)))
        assertEquals(true, overheadRoundCanPlaceTheListener(three))
    }

    /**
     * Switching the delay off sends what a room that never measured its listener sends.
     *
     * One message on the wire and one path on every handset, rather than a flag beside the scale
     * that each end has to remember to read. The scale itself is kept, so switching back on costs
     * nothing and the A and the B differ in the delay and in nothing else - which is what makes
     * the comparison mean anything: the only instrument that can judge this is an ear, and an ear
     * cannot tell a correction that works from one that never started.
     */
    @Test
    fun `switching the delay off is the same message as never having measured`() {
        val measured = RoomState(icons = three, metresPerUnit = 0.5)

        assertTrue(delayLines(measured.icons, measured.metresPerUnit).any { it.second > 0.0 })
        assertEquals(
            emptyList<Pair<String, Double>>(),
            delayLines(measured.icons, metresPerUnit = 0.0)
        )
        // And the scale survives the switch, or turning it back on would need another minute of
        // somebody standing still.
        assertEquals(0.5, measured.copy(delayCompensation = false).metresPerUnit, 0.0)
    }
}
