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
}
