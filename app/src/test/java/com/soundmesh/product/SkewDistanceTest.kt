package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gap slider's other half: whether a drag also carries the room away, and by how much.
 *
 * The shape being pinned is the whole claim on screen - level in the middle, quieter towards
 * either end - and the half that goes wrong silently is the near side. A room that only retreats
 * when the gap is signed one way looks right in a screenshot, reads right on the readout, and is
 * simply not what the tick box says.
 */
class SkewDistanceTest {

    @Test
    fun `a gap left to say only which side leaves the room where it stands`() {
        val state = RoomState(skew = 0.5f, skewCarriesDistance = false)

        assertEquals(0f, state.retreat, 1e-6f)
    }

    @Test
    fun `a gap in the middle is the loudest the room gets`() {
        val state = RoomState(skew = 0f, skewCarriesDistance = true)

        assertEquals(0f, state.retreat, 1e-6f)
    }

    @Test
    fun `a room dragged to either side retreats by the same amount`() {
        val others = RoomState(skew = 0.5f, skewCarriesDistance = true)
        val self = RoomState(skew = -0.5f, skewCarriesDistance = true)

        assertEquals(0.5f, others.retreat, 1e-6f)
        assertEquals(0.5f, self.retreat, 1e-6f)
    }

    @Test
    fun `the far end of the gap is the furthest the room goes`() {
        val state = RoomState(skew = -1f, skewCarriesDistance = true)

        assertEquals(1f, state.retreat, 1e-6f)
    }
}
