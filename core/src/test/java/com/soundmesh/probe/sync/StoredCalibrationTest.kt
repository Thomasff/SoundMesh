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

    /**
     * The way out of a pair that has jammed.
     *
     * A run only folds into a constant that already exists when it passed, which stops a bad run
     * making a good constant worse - but the first run is exempt, because a pair nobody has
     * measured has to adopt something. So a first run that lands badly is stored whole, every
     * later run then fails against it, and nothing in the loop can ever move it again.
     *
     * That is not a rare corner. Five verification runs on a good link on 2026-09-08 put the
     * cluster mean at 0.624 ± 0.388 against a 1.0 gate: about one run in six crosses it on
     * scatter alone, with nothing wrong. Forgetting is the only exit.
     */
    @Test
    fun forgettingLeavesThePairUnmeasuredRatherThanAtZero() {
        val directory = temporaryDir()
        val stored = StoredCalibration(directory, peer)
        stored.write(-34_773L, 4)

        stored.forget()

        assertNull("a forgotten pair still carries a correction", stored.read())
    }

    /** Forgetting is per pair, for the same reason the file is: one partner is not the other. */
    @Test
    fun forgettingOnePairLeavesTheOtherStanding() {
        val directory = temporaryDir()
        StoredCalibration(directory, peer).write(-34_773L, 4)
        StoredCalibration(directory, otherPeer).write(-35_108L, 15)

        StoredCalibration(directory, peer).forget()

        assertNull(StoredCalibration(directory, peer).read())
        assertEquals(Calibration(-35_108L, 15), StoredCalibration(directory, otherPeer).read())
    }

    /**
     * The screen offers this whenever there is something to forget, and what is on screen can be
     * one run behind what is on disk. Asking twice is not an error.
     */
    @Test
    fun forgettingAPairThatWasNeverMeasuredChangesNothing() {
        val directory = temporaryDir()
        val stored = StoredCalibration(directory, peer)

        stored.forget()
        stored.forget()

        assertNull(stored.read())
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
