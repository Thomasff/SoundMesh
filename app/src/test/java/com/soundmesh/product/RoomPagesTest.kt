package com.soundmesh.product

import com.soundmesh.core.RoomExcuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The position calibration as three pages: the room's volume, where the listener sits, where the
 * phones stand. What decides whether the buttons at the bottom can be pressed.
 */
class RoomPagesTest {
    @Test
    fun `the volume page goes on only once nobody is too quiet`() {
        assertTrue(roomForwardOpen(1, running = false, tooQuiet = emptyList(), stepOneDone = false, stepTwoDone = false))
        assertFalse(roomForwardOpen(1, running = false, tooQuiet = listOf("X10"), stepOneDone = false, stepTwoDone = false))
    }

    @Test
    fun `the listener page goes on once its step is measured or skipped`() {
        assertFalse(roomForwardOpen(2, running = false, tooQuiet = emptyList(), stepOneDone = false, stepTwoDone = false))
        assertTrue(roomForwardOpen(2, running = false, tooQuiet = emptyList(), stepOneDone = true, stepTwoDone = false))
    }

    @Test
    fun `the last page finishes only once the phones are measured`() {
        assertFalse(roomForwardOpen(3, running = false, tooQuiet = emptyList(), stepOneDone = true, stepTwoDone = false))
        assertTrue(roomForwardOpen(3, running = false, tooQuiet = emptyList(), stepOneDone = true, stepTwoDone = true))
    }

    @Test
    fun `nothing moves while a round runs`() {
        assertFalse(roomForwardOpen(2, running = true, tooQuiet = emptyList(), stepOneDone = true, stepTwoDone = true))
        assertFalse(roomForwardOpen(3, running = true, tooQuiet = emptyList(), stepOneDone = true, stepTwoDone = true))
        assertFalse(roomBackOpen(3, running = true))
    }

    @Test
    fun `there is no way back from the first page`() {
        assertFalse(roomBackOpen(1, running = false))
        assertTrue(roomBackOpen(2, running = false))
    }

    /** A round's answer stays on the page of the step it measured; with none yet, on any page. */
    @Test
    fun `a result is drawn on the page of the step it came from`() {
        assertTrue(resultBelongsOn(2, ran = 1))
        assertFalse(resultBelongsOn(3, ran = 1))
        assertTrue(resultBelongsOn(3, ran = 2))
        assertTrue(resultBelongsOn(2, ran = null))
    }

    /** Heard says nothing; not heard, or never came, says so; a reason given is said instead. */
    @Test
    fun `a sound check speaks only against the devices it did not hear`() {
        val check = SoundCheck(
            tookPart = listOf("one", "two", "host"),
            heard = setOf("one", "host"),
            excuses = mapOf("three" to RoomExcuse.ASLEEP)
        )
        assertEquals(
            mapOf("two" to "quiet", "three" to "ASLEEP", "four" to "quiet"),
            unheardLines(check, listOf("host", "one", "two", "three", "four"), { it.name }, "quiet")
        )
    }
}
