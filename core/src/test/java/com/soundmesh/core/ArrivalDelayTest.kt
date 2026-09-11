package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other half of the distance correction: the near handset waits for the far one.
 *
 * [SpatialLayout.distanceGainOf] has said in its own words since it was written that it corrects
 * the level and not the time, and that the time cannot be computed from a drawing because a
 * drawing has no scale. This is what the overhead round buys: one number saying how large the room
 * is, and with it every arrival time follows.
 */
class ArrivalDelayTest {
    private val near = "a1b2c3d4e5f60718"
    private val far = "0918273645abcdef"
    private val middle = "1122334455667788"

    /** One drawing unit is one metre here, so the coordinates read as metres. */
    private fun room(metresPerUnit: Double, vararg at: Pair<String, Pair<Double, Double>>) =
        SpatialField(
            SpatialMode.SPLIT,
            SpatialLayout(at.map { SpatialPosition(it.first, it.second.first, it.second.second) }),
            metresPerUnit = metresPerUnit
        )

    private fun millis(field: SpatialField, peerId: String) =
        field.arrivalDelayNanosFor(peerId) / 1_000_000.0

    @Test
    fun `the near handset waits out the difference and the far one waits for nobody`() {
        val field = room(1.0, near to (0.0 to 1.0), far to (0.0 to 3.0))

        assertEquals(0.0, millis(field, far), 0.0)
        // Two metres of difference, 343 m/s.
        assertEquals(2.0 / 343.0 * 1000, millis(field, near), 0.01)
    }

    /** It is the difference that is waited out, not the distance: nobody can play in the past. */
    @Test
    fun `every handset is timed against the furthest one and not against the listener`() {
        val field = room(
            1.0,
            near to (0.0 to 1.0),
            middle to (0.0 to 2.0),
            far to (0.0 to 4.0)
        )

        assertEquals(3.0 / 343.0 * 1000, millis(field, near), 0.01)
        assertEquals(2.0 / 343.0 * 1000, millis(field, middle), 0.01)
        assertEquals(0.0, millis(field, far), 0.0)
    }

    /**
     * A room measured handset to handset alone knows its own size and still delays nothing.
     *
     * Not an oversight and not a precision trade: the listener in such a room is assumed to be in
     * the middle of the handsets, which is where nobody sits. Every delay here is measured from the
     * listener, so a guessed listener holds the wrong handset back - which adds the error it was
     * built to remove instead of merely failing to remove it.
     */
    @Test
    fun `a room whose listener was never measured delays nothing`() {
        val field = room(0.0, near to (0.0 to 1.0), far to (0.0 to 3.0))

        assertEquals(0.0, millis(field, near), 0.0)
        assertEquals(0.0, millis(field, far), 0.0)
    }

    /**
     * The drawing is still dimensionless, and this proves the scale is threaded through rather
     * than the coordinates having quietly become metres somewhere.
     */
    @Test
    fun `drawing the same room ten times larger changes no delay`() {
        val small = room(1.0, near to (0.0 to 1.0), far to (0.0 to 3.0))
        val large = room(0.1, near to (0.0 to 10.0), far to (0.0 to 30.0))

        assertEquals(millis(small, near), millis(large, near), 1e-9)
    }

    /**
     * A scale wrong by a factor of a hundred asks a handset to go quiet for a second and a half
     * while every counter still reads healthy. Clipped to the longest wait a room can justify.
     */
    @Test
    fun `a scale gone wrong cannot silence a handset for a second`() {
        val field = room(100.0, near to (0.0 to 1.0), far to (0.0 to 3.0))

        assertEquals(
            SpatialField.MAX_ARRIVAL_DELAY_NANOS,
            field.arrivalDelayNanosFor(near)
        )
    }

    /**
     * The scale reaches the time and nothing else.
     *
     * Worth its own test because the level correction reads the same two radii this does, and a
     * scale that leaked into it would change how loud the room is for a reason nobody asked for -
     * quietly, since a room that is uniformly louder is not a defect anybody can hear as one.
     */
    @Test
    fun `knowing how large the room is changes no gain anywhere`() {
        val without = room(0.0, near to (0.0 to 1.0), far to (0.0 to 3.0))
        val with = room(1.0, near to (0.0 to 1.0), far to (0.0 to 3.0))

        for (peerId in listOf(near, far)) {
            assertEquals(without.gainAt(peerId, 0L), with.gainAt(peerId, 0L))
            assertEquals(
                without.layout.distanceGainOf(peerId),
                with.layout.distanceGainOf(peerId),
                0.0
            )
        }
    }

    /** Half a metre of it is a millisecond and a half, which is the whole budget. */
    @Test
    fun `the delay is worth having at the distances a room actually has`() {
        val field = room(1.0, near to (0.0 to 1.5), far to (0.0 to 2.0))

        assertTrue("${millis(field, near)} ms", millis(field, near) > 1.0)
    }
}
