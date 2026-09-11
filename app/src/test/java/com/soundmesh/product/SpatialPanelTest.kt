package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
