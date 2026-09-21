package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomGridTest {
    @Test
    fun landsOnAGridPointAtOrAfterTheInstantAskedFor() {
        for (offset in listOf(1L, 7L, RoomGrid.GRID_NANOS / 3, RoomGrid.GRID_NANOS - 1)) {
            val asked = 40 * RoomGrid.GRID_NANOS + offset
            val instant = RoomGrid.nextInstant(asked)

            assertEquals(0L, instant % RoomGrid.GRID_NANOS)
            assertTrue("$instant is before $asked", instant >= asked)
            assertTrue(instant - asked < RoomGrid.GRID_NANOS)
        }
    }

    @Test
    fun aGridPointIsItsOwnAnswer() {
        val instant = 40 * RoomGrid.GRID_NANOS

        assertEquals(instant, RoomGrid.nextInstant(instant))
    }

    /**
     * The whole reason this is floor division. `nanoTime` may count from a negative origin, and
     * the remainder operator rounds toward zero there, which hands back a grid point in the past.
     */
    @Test
    fun negativeInstantsStillGetAGridPointInTheFuture() {
        for (asked in listOf(-1L, -RoomGrid.GRID_NANOS - 1, -3 * RoomGrid.GRID_NANOS + 5)) {
            val instant = RoomGrid.nextInstant(asked)

            assertEquals(0L, instant % RoomGrid.GRID_NANOS)
            assertTrue("$instant is before $asked", instant >= asked)
            assertTrue(instant - asked < RoomGrid.GRID_NANOS)
        }
    }
}
