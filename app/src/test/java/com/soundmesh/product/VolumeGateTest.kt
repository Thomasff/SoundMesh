package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate in front of a calibration round.
 *
 * A round measures every handset against every other one by ear, so one phone nobody can hear does
 * not produce a worse answer - it produces an answer about the room's noise floor, which looks
 * exactly like an answer about the room.
 */
class VolumeGateTest {
    private fun row(name: String, percent: Int, asked: Int? = null) =
        VolumeRow(
            peerId = name,
            name = name,
            percent = percent,
            index = percent,
            max = 100,
            stream = "MEDIA",
            asked = asked
        )

    @Test
    fun `a room that is all loud enough stops nobody`() {
        assertEquals(
            emptyList<String>(),
            tooQuietFor(listOf(row("Magic6", 60), row("X10", 10)))
        )
    }

    /** Ten is the floor and not the first refusal: at ten a handset passes, at nine it does not. */
    @Test
    fun `the floor itself passes`() {
        assertEquals(emptyList<String>(), tooQuietFor(listOf(row("X10", QUIET_FLOOR_PERCENT))))
        assertEquals(listOf("X10"), tooQuietFor(listOf(row("X10", QUIET_FLOOR_PERCENT - 1))))
    }

    /**
     * The one that matters. `setStreamVolume` has been seen on this project's own handsets to take
     * a value, throw nothing and move nothing - so a gate judged on what was sent would wave
     * through the single handset that is actually silent, which is the whole fault it exists for.
     */
    @Test
    fun `a handset that was told to be loud and did not move is still too quiet`() {
        assertEquals(
            listOf("HONOR Pad V9"),
            tooQuietFor(listOf(row("Magic6", 60, asked = 60), row("HONOR Pad V9", 8, asked = 60)))
        )
    }

    /** Names rather than a count, because what a person does about it is walk to one phone. */
    @Test
    fun `every quiet handset is named, in the order they are drawn`() {
        assertEquals(
            listOf("X10", "HONOR Pad V9"),
            tooQuietFor(listOf(row("X10", 2), row("Magic6", 60), row("HONOR Pad V9", 8)))
        )
    }

    /** A host nobody has joined has nobody to hear it: a round there runs its minute for nothing. */
    @Test
    fun `a host alone cannot start a round`() {
        assertFalse(roundCanStart(running = false, tooQuiet = emptyList(), alone = true))
        assertTrue(roundCanStart(running = false, tooQuiet = emptyList(), alone = false))
    }

    @Test
    fun `a round running or a handset too quiet stops the next one as before`() {
        assertFalse(roundCanStart(running = true, tooQuiet = emptyList(), alone = false))
        assertFalse(roundCanStart(running = false, tooQuiet = listOf("X10"), alone = false))
    }

    /**
     * Leaving puts the room back only where this screen is what moved it. The playing screen sets
     * a room volume too and has a restore of its own, and both put back the level from before the
     * app ever touched the handset - so restoring unconditionally would throw away the level
     * somebody chose for the music, from a screen that is not about music.
     */
    @Test
    fun `a calibration that moved the volume puts it back`() {
        assertTrue(restoresOnLeaving(changedBefore = false, changedNow = true))
    }

    @Test
    fun `a calibration that found the volume already moved leaves it alone`() {
        assertFalse(restoresOnLeaving(changedBefore = true, changedNow = true))
    }

    @Test
    fun `a calibration that moved nothing restores nothing`() {
        assertFalse(restoresOnLeaving(changedBefore = false, changedNow = false))
    }
}
