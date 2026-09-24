package com.soundmesh.desktop.app

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressTest {
    /**
     * The first pixel of travel moves what was grabbed by that pixel. A touch slop here, the
     * handset's, left an icon lying still under the first stretch of a mouse drag (reported
     * 2026-09-24), and jumping its middle to the pointer once it did move is the other way a
     * press that barely travels could shift an icon by half its width.
     */
    @Test
    fun whatWasGrabbedKeepsItsGripFromTheFirstPixel() {
        val press = Press(down = Offset(100f, 100f), grabbedCentre = Offset(90f, 95f))
        assertEquals(Offset(91f, 95f), press.follow(Offset(101f, 100f)))
        assertEquals(Offset(130f, 55f), press.follow(Offset(140f, 60f)))
    }

    @Test
    fun aPressThatHardlyTravelledIsStillAClick() {
        val still = Press(down = Offset(100f, 100f), grabbedCentre = Offset(100f, 100f))
        assertTrue(still.wasClick)
        still.follow(Offset(101f, 101f))
        assertTrue(still.wasClick)

        val moved = Press(down = Offset(100f, 100f), grabbedCentre = Offset(100f, 100f))
        moved.follow(Offset(110f, 100f))
        // Coming back to where it began does not make a drag into a click.
        moved.follow(Offset(100f, 100f))
        assertFalse(moved.wasClick)
    }
}
