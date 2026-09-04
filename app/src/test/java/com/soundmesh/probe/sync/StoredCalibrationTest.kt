package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoredCalibrationTest {
    private fun temporaryDir(): File = Files.createTempDirectory("stored-calibration").toFile()

    @Test
    fun remembersWhatTheRunsSoFarMeasured() {
        val directory = temporaryDir()

        StoredCalibration(directory).write(-34_773L, 4)

        assertEquals(Calibration(-34_773L, 4), StoredCalibration(directory).read())
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
        StoredCalibration(directory).write(-34_773L, 4)
        File(directory, StoredCalibration.FILE_NAME).writeText("-347")
        File(directory, StoredCalibration.FILE_NAME).appendText("hello 4")

        assertNull(StoredCalibration(directory).read())
    }

    /** A count is what damps the loop, so a file that carries no readable one is not usable. */
    @Test
    fun refusesACountItCannotRead() {
        val directory = temporaryDir()
        File(directory, StoredCalibration.FILE_NAME).writeText("-34773 many")

        assertNull(StoredCalibration(directory).read())
    }

    /**
     * The file written before the loop averaged anything held the offset alone. It was one run's
     * measurement, so it counts as one - reading it as a longer history would under-weight every
     * run that follows it.
     */
    @Test
    fun readsAFileFromBeforeTheCountExistedAsASingleObservation() {
        val directory = temporaryDir()
        File(directory, StoredCalibration.FILE_NAME).writeText("-34821")

        assertEquals(Calibration(-34_821L, 1), StoredCalibration(directory).read())
    }

    @Test
    fun replacesTheCorrectionRatherThanAppendingToIt() {
        val directory = temporaryDir()
        val stored = StoredCalibration(directory)

        stored.write(-34_957L, 1)
        stored.write(-34_773L, 2)

        assertEquals(Calibration(-34_773L, 2), stored.read())
    }
}
