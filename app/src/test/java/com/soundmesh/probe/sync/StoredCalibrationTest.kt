package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoredCalibrationTest {
    private val peer = "0123456789abcdef"
    private val otherPeer = "fedcba9876543210"

    private fun temporaryDir(): File = Files.createTempDirectory("stored-calibration").toFile()

    private fun fileFor(directory: File, peerId: String) =
        File(directory, "${StoredCalibration.FILE_PREFIX}$peerId")

    @Test
    fun remembersWhatTheRunsSoFarMeasured() {
        val directory = temporaryDir()

        StoredCalibration(directory, peer).write(-34_773L, 4)

        assertEquals(Calibration(-34_773L, 4), StoredCalibration(directory, peer).read())
    }

    /** A handset that has never been paired has no correction, and zero is not the same as none. */
    @Test
    fun answersNothingBeforeTheFirstRun() {
        assertNull(StoredCalibration(temporaryDir(), peer).read())
    }

    /**
     * The whole point of the key. A long history against one partner is the slowest state in which
     * to notice a different one, so a new partner must start from nothing rather than inherit it.
     */
    @Test
    fun keepsOnePeersHistoryOutOfAnothers() {
        val directory = temporaryDir()

        StoredCalibration(directory, peer).write(-34_966L, 4)

        assertNull(StoredCalibration(directory, otherPeer).read())
        assertEquals(Calibration(-34_966L, 4), StoredCalibration(directory, peer).read())
    }

    /**
     * The un-keyed file is left where it is and never read. Which peer it belonged to is exactly
     * the silent guess this loop refuses everywhere else.
     */
    @Test
    fun ignoresTheFileTheUnkeyedVersionLeftBehind() {
        val directory = temporaryDir()
        File(directory, "calibration-offset-us").writeText("-34966 4")

        assertNull(StoredCalibration(directory, peer).read())
    }

    /**
     * A half written file is worse than no file: it would be read as a correction and applied to
     * every emission. The value is small enough to write in one go, but the read still refuses
     * anything it cannot parse whole rather than salvaging a prefix.
     */
    @Test
    fun refusesAFileItCannotReadWhole() {
        val directory = temporaryDir()
        StoredCalibration(directory, peer).write(-34_773L, 4)
        fileFor(directory, peer).writeText("-347")
        fileFor(directory, peer).appendText("hello 4")

        assertNull(StoredCalibration(directory, peer).read())
    }

    /** A count is what damps the loop, so a file that carries no readable one is not usable. */
    @Test
    fun refusesACountItCannotRead() {
        val directory = temporaryDir()
        fileFor(directory, peer).writeText("-34773 many")

        assertNull(StoredCalibration(directory, peer).read())
    }

    /**
     * The peer id can arrive off a scanned screen and it is the file name, so a value that could
     * name something else is refused before it becomes a path rather than after.
     */
    @Test
    fun refusesAPeerIdThatCouldNameSomethingElse() {
        val directory = temporaryDir()

        assertThrows(IllegalArgumentException::class.java) { StoredCalibration(directory, "../../secret") }
        assertThrows(IllegalArgumentException::class.java) { StoredCalibration(directory, "") }
    }

    /** The address-on-the-command-line path never learns who it reached, and says so by name. */
    @Test
    fun letsARunThatNeverIdentifiedItsHostKeepACorrection() {
        val directory = temporaryDir()
        val anonymous = StoredCalibration(directory, StoredCalibration.ANONYMOUS_PEER)

        anonymous.write(-34_773L, 2)

        assertEquals(Calibration(-34_773L, 2), anonymous.read())
        assertNull(StoredCalibration(directory, peer).read())
    }

    @Test
    fun replacesTheCorrectionRatherThanAppendingToIt() {
        val directory = temporaryDir()
        val stored = StoredCalibration(directory, peer)

        stored.write(-34_957L, 1)
        stored.write(-34_773L, 2)

        assertEquals(Calibration(-34_773L, 2), stored.read())
    }
}
