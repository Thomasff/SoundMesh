package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The distances a room measured that a pair of phones never could: the ones between two handsets
 * neither of which is this one.
 */
class StoredRoomFieldTest {
    @get:Rule val folder = TemporaryFolder()

    private val one = "a1b2c3d4e5f60718"
    private val two = "0918273645abcdef"
    private val three = "1122334455667788"

    private fun store() = StoredRoomField(folder.root)

    @Test
    fun aRoomNobodyHasMeasuredHasNoDistances() {
        assertEquals(emptyMap<Pair<String, String>, Double>(), store().read())
    }

    /** Both orders, because a caller holding two names has no reason to know which sorts first. */
    @Test
    fun whatWasWrittenComesBackEitherWayRound() {
        store().write(mapOf((one to two) to 2.02, (two to three) to 1.48))

        val field = store().read()

        assertEquals(2.02, field[one to two]!!, 1e-9)
        assertEquals(2.02, field[two to one]!!, 1e-9)
        assertEquals(1.48, field[three to two]!!, 1e-9)
        assertNull(field[one to three])
    }

    /**
     * A room is the unit that was measured, so a room is the unit that is replaced. Where the
     * phones were standing last week is not evidence about where they are standing now.
     */
    @Test
    fun aSecondRoomReplacesTheFirstRatherThanBeingAddedToIt() {
        store().write(mapOf((one to two) to 2.02, (two to three) to 1.48))

        store().write(mapOf((one to two) to 3.50))

        val field = store().read()
        assertEquals(3.50, field[one to two]!!, 1e-9)
        assertNull(field[two to three])
    }

    /**
     * Including a room that measured nothing. Leaving the last field in place would let the
     * drawing check go on firing on a room that has since been rearranged.
     */
    @Test
    fun aRoomThatMeasuredNothingClearsWhatWasThere() {
        store().write(mapOf((one to two) to 2.02))

        store().write(emptyMap())

        assertEquals(emptyMap<Pair<String, String>, Double>(), store().read())
    }

    /**
     * A pair the room could not read is dropped rather than written as a hole: what a reader wants
     * is the distances there are, and which ones went unanswered is a fact about that run.
     */
    @Test
    fun aPairWithNoAnswerIsNotWrittenDown() {
        store().write(mapOf((one to two) to 2.02, (two to three) to null, (one to three) to -1.0))

        assertEquals(setOf(one to two, two to one), store().read().keys)
    }

    /**
     * The names reach a file, and they arrive from a socket by way of a plan. A line this cannot
     * trust is skipped rather than taking the rest of the field down with it - a field is written
     * whole, so half of one is a room read as smaller than it is, which is worse than none.
     */
    @Test
    fun aLineItCannotTrustIsSkippedAndTheRestStands() {
        File(folder.root, StoredRoomField.FILE_NAME).writeText(
            listOf(
                "$one $two 2.02",
                "../../etc/passwd $two 1.0",
                "$one $three",
                "$two $three not-a-number",
                "$one $one 1.0",
                "$two $three 1.48"
            ).joinToString("\n")
        )

        val field = store().read()

        assertEquals(setOf(one to two, two to one, two to three, three to two), field.keys)
    }

    /** Its own file, so nothing a room writes can cost a pair its standing correction. */
    @Test
    fun itKeepsToItsOwnFile() {
        StoredSeparation(folder.root, two).write(9.0)

        store().write(mapOf((one to two) to 2.02))

        assertEquals(9.0, StoredSeparation(folder.root, two).read()!!, 1e-9)
        assertTrue(File(folder.root, StoredRoomField.FILE_NAME).isFile)
        assertFalse(StoredRoomField.FILE_NAME.startsWith(StoredSeparation.FILE_PREFIX))
    }
}
