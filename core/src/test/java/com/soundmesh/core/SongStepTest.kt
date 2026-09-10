package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SongStepTest {
    @Test
    fun aStepEitherWayInTheMiddleOfTheListIsJustTheNeighbour() {
        assertEquals(2, songAfterStep(current = 1, by = 1))
        assertEquals(0, songAfterStep(current = 1, by = -1))
    }

    /**
     * Rather than wrapping round to the first one.
     *
     * A folder that ended used to start again, and `02fdad3` took that out on the grounds that a
     * room could not reach the end of the evening. Pressing next on the last song is the same
     * question asked by hand, so it gets the same answer.
     */
    @Test
    fun aStepPastTheLastSongEndsTheList() {
        assertEquals(3, songAfterStep(current = 2, by = 1))
    }

    /**
     * There is nothing before the first song, and the press has to do something.
     *
     * Playing it again is what every other player does with it, and it is the only answer that is
     * not "the button you pressed did nothing" - which on a phone reads as the app having missed
     * the touch.
     */
    @Test
    fun aStepBackFromTheFirstSongPlaysItAgain() {
        assertEquals(0, songAfterStep(current = 0, by = -1))
    }
}
