package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoredCalibrationTest {
    private fun temporaryDir(): File = Files.createTempDirectory("stored-calibration").toFile()

    @Test
    fun remembersWhatTheLastRunMeasured() {
        val directory = temporaryDir()

        StoredCalibration(directory).write(-34_773L)

        assertEquals(-34_773L, StoredCalibration(directory).read())
    }

    /** A handset that has never been paired has no correction, and zero is not the same as none. */
    @Test
    fun answersNothingBeforeTheFirstRun() {
        assertNull(StoredCalibration(temporaryDir()).read())
    }

    /**
     * A half written file is worse than no file: it would be read as a correction and applied to
     * every emission. The value is small enough to write in one go, but the read still refuses
     * anything it cannot parse whole rather than salvaging a prefix.
     */
    @Test
    fun refusesAFileItCannotReadWhole() {
        val directory = temporaryDir()
        StoredCalibration(directory).write(-34_773L)
        File(directory, StoredCalibration.FILE_NAME).writeText("-347")
        File(directory, StoredCalibration.FILE_NAME).appendText("hello")

        assertNull(StoredCalibration(directory).read())
    }

    @Test
    fun replacesTheCorrectionRatherThanAppendingToIt() {
        val directory = temporaryDir()
        val stored = StoredCalibration(directory)

        stored.write(-34_957L)
        stored.write(-34_773L)

        assertEquals(-34_773L, stored.read())
    }
}
