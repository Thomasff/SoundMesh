package com.soundmesh.core

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialLayoutTest {
    /**
     * The four cardinal directions, read the way a person would say them. Right is positive
     * because the pan control's right is positive, and two conventions for "right" in one
     * subsystem is how a slider ends up steering the wrong way.
     */
    @Test
    fun aHandsetsDirectionIsReadClockwiseFromStraightAhead() {
        val layout = SpatialLayout(
            listOf(
                SpatialPosition("front", 0.0, 1.0),
                SpatialPosition("right", 1.0, 0.0),
                SpatialPosition("left", -1.0, 0.0),
                SpatialPosition("behind", 0.0, -1.0)
            )
        )

        assertEquals(0.0, layout.azimuthOf("front"), 1e-12)
        assertEquals(PI / 2, layout.azimuthOf("right"), 1e-12)
        assertEquals(-PI / 2, layout.azimuthOf("left"), 1e-12)
        assertEquals(PI, layout.azimuthOf("behind"), 1e-12)
    }

    /** Only the direction is read, so a drawing scaled up is the same room. */
    @Test
    fun scalingTheWholeDrawingChangesNoDirection() {
        val near = SpatialLayout(listOf(SpatialPosition("a", 0.3, 0.4)))
        val far = SpatialLayout(listOf(SpatialPosition("a", 3.0, 4.0)))

        assertEquals(near.azimuthOf("a"), far.azimuthOf("a"), 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aLayoutWithNoHandsetsIsRefused() {
        SpatialLayout(emptyList())
    }

    /** Two icons under one name would make every lookup answer about whichever came first. */
    @Test(expected = IllegalArgumentException::class)
    fun twoHandsetsUnderOneNameAreRefused() {
        SpatialLayout(listOf(SpatialPosition("a", 1.0, 0.0), SpatialPosition("a", -1.0, 0.0)))
    }

    /** A handset on the listener has no direction, and every rule here is about direction. */
    @Test(expected = IllegalArgumentException::class)
    fun aHandsetOnTopOfTheListenerIsRefused() {
        SpatialLayout(listOf(SpatialPosition("a", 0.0, 0.0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun askingAboutAHandsetTheDrawingDoesNotShowThrows() {
        SpatialLayout(listOf(SpatialPosition("a", 1.0, 0.0))).azimuthOf("b")
    }

    @Test
    fun theLayoutSaysWhichHandsetsItHolds() {
        val layout = SpatialLayout(listOf(SpatialPosition("a", 1.0, 0.0)))

        assertTrue(layout.contains("a"))
        assertFalse(layout.contains("b"))
    }

    /**
     * A handset drawn further away has to play louder to arrive alongside the rest, because sound
     * falls off with distance and the rules above are about what the listener hears, not about what
     * a speaker emits. Twice as far is twice the pressure needed.
     */
    @Test
    fun aHandsetDrawnTwiceAsFarAwayHasToPlayTwiceAsLoud() {
        val layout = SpatialLayout(
            listOf(SpatialPosition("near", 0.0, 1.0), SpatialPosition("far", 0.0, 2.0))
        )

        assertEquals(2.0, layout.distanceGainOf("far") / layout.distanceGainOf("near"), 1e-12)
    }

    /**
     * The drawing has no units and this does not give it any. What the correction reads is the
     * **ratio** between one handset and another, so a room drawn small and the same room drawn
     * across the whole screen are the same room - which is what lets a person draw one without
     * being asked how many metres away anything is.
     */
    @Test
    fun drawingTheSameRoomLargerChangesNothing() {
        val small = SpatialLayout(
            listOf(SpatialPosition("a", 0.3, 0.4), SpatialPosition("b", -0.6, 0.0))
        )
        val large = SpatialLayout(
            listOf(SpatialPosition("a", 2.1, 2.8), SpatialPosition("b", -4.2, 0.0))
        )

        for (peerId in listOf("a", "b")) {
            assertEquals(small.distanceGainOf(peerId), large.distanceGainOf(peerId), 1e-12)
        }
    }

    /**
     * An icon dragged almost onto the listener asks for a correction the room should not make.
     *
     * Two reasons, and neither is the arithmetic. Close in, a hand-drawn position is mostly error -
     * a centimetre of drawing is a large fraction of a small radius. And a real room is nothing like
     * free field: past a metre or two the reflections carry most of the level and it stops falling
     * off the way this computes, so a large correction is confidently wrong in the direction of
     * silencing a handset the listener can see on the screen.
     */
    @Test
    fun aHandsetDrawnAlmostOnTheListenerIsNotTakenAllTheWayDown() {
        val layout = SpatialLayout(
            listOf(SpatialPosition("touching", 0.0, 0.001), SpatialPosition("far", 0.0, 1.0))
        )

        assertEquals(SpatialLayout.CLOSEST_SHARE, layout.distanceGainOf("touching"), 1e-12)
    }

    /** The furthest handset is the one asked for everything it has; nothing is asked for more. */
    @Test
    fun theHandsetDrawnFurthestAwayPlaysFlatOut() {
        val layout = SpatialLayout(
            listOf(SpatialPosition("near", 0.0, 1.0), SpatialPosition("far", 0.0, 3.0))
        )

        assertEquals(1.0, layout.distanceGainOf("far"), 1e-12)
    }
}
