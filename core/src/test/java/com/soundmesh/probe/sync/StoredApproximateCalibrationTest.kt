package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The correction a handset carries without anybody having measured this pair.
 *
 * Its whole reason to exist is being tellable apart from a measurement, so that is what is
 * checked hardest here.
 */
class StoredApproximateCalibrationTest {
    private val peer = "0123456789abcdef"

    private fun temporaryDir(): File = Files.createTempDirectory("approximate-calibration").toFile()

    @Test
    fun remembersWhatARoomRoundSaid() {
        val directory = temporaryDir()

        StoredApproximateCalibration(directory, peer).write(-35_948L)

        assertEquals(-35_948L, StoredApproximateCalibration(directory, peer).read())
    }

    /** None is not zero: zero is a correction, and it is what an unmeasured pair looks like. */
    @Test
    fun answersNothingBeforeARoomHasBeenMeasured() {
        assertNull(StoredApproximateCalibration(temporaryDir(), peer).read())
    }

    /**
     * Different files, and this is the one thing that must never be convenient to change.
     *
     * A guess written where a measurement lives cannot be told from a measurement afterwards -
     * not on a screen, not in a report, not by the code deciding whether a pair still needs
     * somebody to walk to it.
     */
    @Test
    fun aGuessIsNotKeptWhereAMeasurementIsKept() {
        assertNotEquals(StoredCalibration.FILE_PREFIX, StoredApproximateCalibration.FILE_PREFIX)
        val directory = temporaryDir()

        StoredApproximateCalibration(directory, peer).write(-35_948L)

        assertNull("the guess was read back as a measurement", StoredCalibration(directory, peer).read())
    }

    /** And a measurement leaves the guess where a later reader would find it, until it is dropped. */
    @Test
    fun forgettingLeavesNothingBehind() {
        val directory = temporaryDir()
        StoredApproximateCalibration(directory, peer).write(-35_948L)

        StoredApproximateCalibration(directory, peer).forget()

        assertNull(StoredApproximateCalibration(directory, peer).read())
    }

    /** The peer id becomes a file name, so it is checked before it becomes a path. */
    @Test
    fun refusesAPeerIdThatIsNotOne() {
        assertThrows(IllegalArgumentException::class.java) {
            StoredApproximateCalibration(temporaryDir(), "../../etc")
        }
    }
}
