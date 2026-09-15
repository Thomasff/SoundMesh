package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two decisions a volume row makes that are not drawing: where a finger is pointing, and which
 * of the numbers in play to put under it.
 */
class ThinSliderTest {
    @Test
    fun `a finger is read as a share of the track it is on`() {
        assertEquals(0, trackPercent(0f, 200f))
        assertEquals(50, trackPercent(100f, 200f))
        assertEquals(100, trackPercent(200f, 200f))
    }

    /**
     * A horizontal drag goes on being reported after the finger has left the track, so the end of
     * a drag off the right-hand edge arrives as a number past the end of the scale.
     */
    @Test
    fun `and never past either end of it`() {
        assertEquals(100, trackPercent(208f, 200f))
        assertEquals(0, trackPercent(-14f, 200f))
        // A row that has not been measured yet. Dividing by it is the only other thing to do.
        assertEquals(0, trackPercent(40f, 0f))
    }

    @Test
    fun `while a finger is down the dot is where the finger is`() {
        assertEquals(80, volumeShown(dragging = 80, asked = 20, before = 35, reported = 35))
    }

    /**
     * The flicker reported on 2026-09-15: let go of the dot and it jumped back to where the drag
     * started, then returned a moment later. What is between those two moments is the handset's
     * own reading crossing the room, and the number asked for stands in for it until it lands.
     */
    @Test
    fun `after letting go the asked-for number stands while the handset is still answering`() {
        assertEquals(20, volumeShown(dragging = null, asked = 20, before = 35, reported = 35))
    }

    /**
     * And it is a hold, not a claim. Any movement at all in the reported reading ends it - the
     * asked-for value, the nearest step this handset actually has, or somebody at that phone
     * pressing a volume key while the answer was in the air.
     */
    @Test
    fun `and gives way the moment the handset says anything else`() {
        assertEquals(20, volumeShown(dragging = null, asked = 20, before = 35, reported = 20))
        assertEquals(18, volumeShown(dragging = null, asked = 20, before = 35, reported = 18))
        assertEquals(60, volumeShown(dragging = null, asked = 20, before = 35, reported = 60))
    }

    @Test
    fun `with nothing asked for it is simply what the handset says`() {
        assertEquals(35, volumeShown(dragging = null, asked = null, before = null, reported = 35))
    }
}
