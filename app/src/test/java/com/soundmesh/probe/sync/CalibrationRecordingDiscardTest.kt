package com.soundmesh.probe.sync

import com.soundmesh.probe.RunStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Throwing away a calibration's audio once the analysis has read it.
 *
 * The recording is the largest thing the app writes and the only one nothing on the handset ever
 * opens again - a few megabytes a case, held for as long as the phone lasts, because a case id
 * names a directory that later runs write over in place rather than a run that ends.
 *
 * What is under test is the reach of the delete, not the size it saves. Everything else in a case
 * directory is a measurement somebody may want years later, in files a hundredth the size, and a
 * deletion that took one of them would be the expensive kind of mistake: silent, and noticed only
 * by whoever went looking for the evidence.
 */
class CalibrationRecordingDiscardTest {
    private fun runStore(): RunStore = RunStore(Files.createTempDirectory("discard").toFile())

    @Test
    fun takesTheRecordingAndItsReference() {
        val store = runStore()
        val directory = store.prepareRun("C90")
        File(directory, "calibration.wav").writeText("recorded")
        File(directory, "chirp.wav").writeText("reference")

        CalibrationRunner(store, "C90").discardRecording()

        assertFalse("the recording survived", File(directory, "calibration.wav").exists())
        assertFalse("the reference survived", File(directory, "chirp.wav").exists())
    }

    /**
     * The JSON is the measurement. It is what the harness exports, what a later reader disbelieves
     * a number from, and three kilobytes against three megabytes - so the audio going does not
     * take it, and the directory itself stays where it is.
     */
    @Test
    fun leavesEverythingElseInTheCaseDirectory() {
        val store = runStore()
        val directory = store.prepareRun("C94")
        File(directory, "calibration.wav").writeText("recorded")
        File(directory, "chirp.wav").writeText("reference")
        File(directory, "peer-calibration-a1b2c3d4.json").writeText("{\"offset\":0.31}")
        File(directory, "sync.json").writeText("{}")

        CalibrationRunner(store, "C94").discardRecording()

        assertTrue("the case directory went with the audio", directory.isDirectory)
        assertEquals(
            listOf("peer-calibration-a1b2c3d4.json", "sync.json"),
            directory.listFiles()!!.map { it.name }.sorted()
        )
    }

    /** Only this case's. A run store holds every case ever run side by side under one parent. */
    @Test
    fun takesNothingFromAnotherCase() {
        val store = runStore()
        val mine = store.prepareRun("C90")
        val other = store.prepareRun("O16")
        File(mine, "calibration.wav").writeText("mine")
        File(other, "calibration.wav").writeText("somebody else's")

        CalibrationRunner(store, "C90").discardRecording()

        assertTrue("another case's recording went too", File(other, "calibration.wav").isFile)
    }

    /**
     * A run refused before it ever opened the microphone has no files to take, and the discard is
     * on the way out of every round including that one.
     */
    @Test
    fun saysNothingWhenThereWasNoRecording() {
        val store = runStore()

        CalibrationRunner(store, "C92").discardRecording()

        assertTrue(store.prepareRun("C92").isDirectory)
    }
}
