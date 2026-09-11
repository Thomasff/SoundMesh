package com.soundmesh.product

import androidx.compose.ui.graphics.Color
import com.soundmesh.core.PeerBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BadgePaletteTest {
    /**
     * Every place [com.soundmesh.core.RoomColours] can hand out has a colour, a label colour and a
     * word for it.
     *
     * The palette's size is written in four places - once in core as a number, three times here as
     * list lengths - and three of the four fail silently when they disagree. A short list throws
     * the first time a handset lands on its end, which is a room of four working and a room of five
     * crashing, days apart.
     */
    @Test
    fun coversEveryPlaceTheRoomCanHandOut() {
        val fallback = Color.Magenta
        for (place in 0 until PeerBadge.COLOURS) {
            assertNotEquals("no colour for place $place", fallback, BadgePalette.colourOf(place, fallback))
            assertNotNull("no word for place $place", BadgePalette.nameOf(place))
        }
    }

    /** Twelve places that look the same are one colour claiming to be twelve. */
    @Test
    fun givesEveryPlaceAColourOfItsOwn() {
        val used = (0 until PeerBadge.COLOURS).map { BadgePalette.colourOf(it, Color.Black) }

        assertEquals(PeerBadge.COLOURS, used.toSet().size)
    }

    /**
     * A handset with no colour yet is ordinary, not broken: the room is read off the control
     * channel, and a name is on screen from the moment it is announced. Falling back rather than
     * throwing is what keeps that from being a crash on the first screen of a session.
     */
    @Test
    fun fallsBackForAHandsetThatHoldsNoPlace() {
        val fallback = Color.Magenta

        assertEquals(fallback, BadgePalette.colourOf(null, fallback))
        assertEquals(fallback, BadgePalette.colourOf(PeerBadge.COLOURS, fallback))
        assertEquals(fallback, BadgePalette.colourOf(-1, fallback))
        assertEquals(fallback, BadgePalette.labelColourOf(null, fallback))
        assertEquals(null, BadgePalette.nameOf(PeerBadge.COLOURS))
    }
}
