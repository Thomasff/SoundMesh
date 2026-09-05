package com.soundmesh.probe.sync

import com.soundmesh.probe.PlaybackUsage
import com.soundmesh.probe.ProbeCase
import com.soundmesh.probe.RunStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule a calibration lays out before it plays anything.
 *
 * Only the plan is exercised here: everything after it is an AudioTrack and a microphone, and the
 * one part of it that is a decision rather than a measurement is which output goes first.
 */
class OutputLeadRunnerTest {
    private val directory: File = Files.createTempDirectory("output-lead").toFile()

    private fun runner(repeats: Int) = OutputLeadRunner(
        runStore = RunStore(directory),
        caseId = "test",
        subject = PlaybackUsage.ACCESSIBILITY,
        repeats = repeats
    )

    @Test
    fun everyRepeatPlaysBothOutputsOnce() {
        val passes = runner(3).plan(0L)

        assertEquals(6, passes.size)
        for (pair in passes.chunked(2)) {
            assertEquals(setOf(PlaybackUsage.MEDIA, PlaybackUsage.ACCESSIBILITY), pair.map { it.usage }.toSet())
        }
    }

    /**
     * Anything that drifts over the seconds between a pair - the recorder's sample clock against
     * `nanoTime`, the hardware warming under load - enters a pair with a sign that depends on which
     * output went first. Held in one order it would survive the median untouched.
     */
    @Test
    fun theOutputThatGoesFirstAlternatesBetweenRepeats() {
        val firsts = runner(4).plan(0L).chunked(2).map { it.first().usage }

        assertEquals(
            listOf(PlaybackUsage.MEDIA, PlaybackUsage.ACCESSIBILITY, PlaybackUsage.MEDIA, PlaybackUsage.ACCESSIBILITY),
            firsts
        )
    }

    /** A chirp lands after the warm-up and the silence, never inside the tone it has to be heard over. */
    @Test
    fun eachChirpSitsAfterItsOwnPassHasWarmedUpAndFallenSilent() {
        for (pass in runner(2).plan(0L)) {
            assertEquals(
                pass.startHostNanos + OutputLeadRunner.WARMUP_NANOS + OutputLeadRunner.CALIBRATION_GAP_NANOS,
                pass.chirpAtHostNanos
            )
        }
    }

    @Test
    fun passesNeverOverlapSoOnlyOneOutputIsOpenAtATime() {
        val passes = runner(3).plan(0L)

        for ((earlier, later) in passes.zipWithNext()) {
            assertTrue(
                "a pass has to be over before the next one starts",
                later.startHostNanos > earlier.chirpAtHostNanos + OutputLeadRunner.CHIRP_DRAIN_NANOS
            )
        }
    }

    /**
     * A calibration can be re-run with its own answer already applied, and then a right answer
     * reads back as nothing left over - the whole of O65 -> O66 on one handset instead of two.
     *
     * The correction belongs to the subject's renderer alone, exactly as it does in HostSession:
     * moving both would move the pair together and measure the same difference all over again,
     * which would pass whatever the constant was.
     */
    @Test
    fun aCorrectionUnderVerificationMovesOnlyTheOutputItWasMeasuredFor() {
        val passes = OutputLeadRunner(
            runStore = RunStore(directory),
            caseId = "test",
            subject = PlaybackUsage.ACCESSIBILITY,
            repeats = 2,
            appliedLeadNanos = 124_791_000L
        ).plan(0L)

        for (pass in passes) {
            assertEquals(
                if (pass.usage == PlaybackUsage.ACCESSIBILITY) 124_791_000L else 0L,
                pass.leadNanos
            )
        }
    }

    /** Nothing is applied unless a verification asked for it: the measurement is of the raw paths. */
    @Test
    fun anOrdinaryCalibrationCorrectsNothing() {
        assertTrue(runner(2).plan(0L).all { it.leadNanos == 0L })
    }

    /**
     * The recording is written through the probe's own run store, and that store takes only the
     * harness's case ids - `[A-Z][0-9]+`. A default it rejects throws on the recording thread,
     * which is a whole process rather than a whole run.
     */
    @Test
    fun theDefaultCaseIsOneTheRunStoreWillAccept() {
        assertTrue(ProbeCase.isSafeCaseId(OutputLeadRunner.DEFAULT_CASE_ID))
    }

    /** Media is the path everything else is measured against, so it cannot also be the subject. */
    @Test(expected = IllegalArgumentException::class)
    fun measuringMediaAgainstItselfIsRefused() {
        OutputLeadRunner(RunStore(directory), "test", PlaybackUsage.MEDIA).run()
    }
}
