package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * One number for a room of handsets that do not agree on how many steps a volume has.
 *
 * Fifteen on one, sixteen on the next, twenty-five on a third. An index set across a room is a
 * different loudness on every handset in it, which is why what travels is a percentage.
 */
class HandsetVolumeTest {
    private fun temporaryDir(): File = Files.createTempDirectory("handset-volume").toFile()

    @Test
    fun putsAPercentageOnWhateverScaleThisHandsetHas() {
        assertEquals(0, indexFor(0, 15))
        assertEquals(15, indexFor(100, 15))
        // Nine and not eight: integer division would quietly put the room a step low everywhere.
        assertEquals(9, indexFor(60, 15))
        assertEquals(10, indexFor(60, 16))
        assertEquals(15, indexFor(60, 25))
    }

    /** Nothing on a screen or on the wire can ask for a step that does not exist. */
    @Test
    fun neverNamesAStepThisHandsetDoesNotHave() {
        for (max in listOf(1, 7, 15, 16, 25)) {
            for (percent in listOf(-40, 0, 1, 50, 99, 100, 140)) {
                val index = indexFor(percent, max)
                assertTrue("$percent% of $max gave $index", index in 0..max)
            }
        }
    }

    /** And back, for saying on a screen what a handset landed on rather than what it was asked. */
    @Test
    fun saysWhereAStepSitsOnTheScaleItIsOn() {
        assertEquals(60, percentOf(9, 15))
        assertEquals(100, percentOf(15, 15))
        assertEquals(0, percentOf(0, 15))
        // A stream with no steps at all is not a division by zero.
        assertEquals(0, percentOf(0, 0))
    }

    /**
     * The value worth keeping is the one from before this app, not the one from before the last
     * drag of a slider. Written once per stream, and only the first write counts.
     */
    @Test
    fun remembersWhereItFoundThingsAndNotWhereItLeftThem() {
        val directory = temporaryDir()
        val before = StoredVolumeBefore(directory)

        before.remember("MEDIA", 8)
        before.remember("MEDIA", 3)
        before.remember("ALARM", 11)

        assertEquals(mapOf("MEDIA" to 8, "ALARM" to 11), before.taken())
    }

    /**
     * On disk rather than in memory, because the moment it is most needed is after the app has
     * been killed - which is exactly when a field would be gone and somebody's phone would be
     * left on whatever a room set it to.
     */
    @Test
    fun survivesTheAppAndGoesAwayWhenItIsPutBack() {
        val directory = temporaryDir()
        StoredVolumeBefore(directory).remember("MEDIA", 8)

        assertEquals(mapOf("MEDIA" to 8), StoredVolumeBefore(directory).taken())

        StoredVolumeBefore(directory).forget()

        assertTrue(StoredVolumeBefore(directory).taken().isEmpty())
    }

    @Test
    fun answersNothingBeforeAnythingHasBeenTouched() {
        assertTrue(StoredVolumeBefore(temporaryDir()).taken().isEmpty())
    }

    /** A half-written line is not a volume to put anybody's phone back to. */
    @Test
    fun ignoresALineItCannotRead() {
        val directory = temporaryDir()
        File(directory, StoredVolumeBefore.FILE).writeText("MEDIA\nALARM loud\nMEDIA 8\n")

        assertEquals(mapOf("MEDIA" to 8), StoredVolumeBefore(directory).taken())
        assertFalse(StoredVolumeBefore(directory).taken().containsKey("ALARM"))
    }
}
