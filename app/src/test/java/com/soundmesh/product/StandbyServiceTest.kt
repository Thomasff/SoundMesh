package com.soundmesh.product

import com.soundmesh.core.RoomCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line the standing service is drawn around: what a handset can do while nobody is
 * looking at it.
 */
class StandbyServiceTest {
    /**
     * Everything but measuring, and measuring is refused out loud elsewhere rather than skipped.
     *
     * Asserted command by command rather than by shape, because the cost of the two mistakes is
     * not the same: a command wrongly on the "needs a screen" side is a feature that quietly does
     * not work when the phone is in a pocket, and one wrongly on the other side is a handset
     * trying to start an activity it is not allowed to start, in silence.
     */
    @Test
    fun obeysEverythingExceptMeasuringWhileTheScreenIsAway() {
        assertTrue(canObeyWhileAway(RoomCommand.PLAY))
        assertTrue(canObeyWhileAway(RoomCommand.STOP))
        assertTrue(canObeyWhileAway(RoomCommand.SET_VOLUME))
        assertTrue(canObeyWhileAway(RoomCommand.RESTORE_VOLUME))

        assertFalse(canObeyWhileAway(RoomCommand.MEASURE_ROOM))
        assertFalse(canObeyWhileAway(RoomCommand.MEASURE_OVERHEAD))
    }

    /** And this is what makes the list above a list of all of them rather than of the ones I recalled. */
    @Test
    fun everyCommandOnTheChannelHasBeenAskedThisQuestion() {
        assertEquals(6, RoomCommand.entries.size)
    }
}
