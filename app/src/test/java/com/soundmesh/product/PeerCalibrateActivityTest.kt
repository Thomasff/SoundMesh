package com.soundmesh.product

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four properties of the pair calibration screen, read out of the source the way
 * [CalibrateActivityTest] reads its own. Each is something the framework or a quiet room would
 * otherwise have had to teach, and each carries the reason it is here.
 */
class PeerCalibrateActivityTest {
    private val source =
        File("src/main/java/com/soundmesh/product/PeerCalibrateActivity.kt").readText(Charsets.UTF_8)

    /**
     * The screen is singleTask, so a second start is delivered to onNewIntent and never reaches
     * onCreate. Without the override the screen sits on the previous run's answer and measures
     * nothing, which is how a Magic6 reading was lost once already.
     */
    @Test
    fun aSecondStartRunsTheCalibrationAgainRatherThanShowingTheLastAnswer() {
        assertTrue(source.contains("override fun onNewIntent("))
    }

    /**
     * An uncaught throw on any thread takes the whole process with it, which on hardware looks
     * like the app vanishing rather than like a calibration failing.
     */
    @Test
    fun aCalibrationThatFailsLeavesTheAppStandingToSaySo() {
        assertTrue(source.contains("runCatching"))
        assertTrue(source.contains("Thread("))
    }

    /**
     * A verification measures the residual left over after the stored constant is applied. Writing
     * a residual where the constant lives would quietly halve the correction on every run after
     * it, and nothing downstream could tell.
     */
    @Test
    fun aVerificationNeverStoresWhatItMeasured() {
        assertTrue(source.contains("if (verifying) return"))
    }

    /**
     * The host id in the plan is the file name the constant is stored under. A correction filed
     * against the wrong peer is applied silently on every later session with nothing to notice it
     * by, so a plan naming a host this handset never scanned has to end the run.
     */
    @Test
    fun aPlanFromSomebodyElseEndsTheRunRatherThanBeingStored() {
        assertTrue(source.contains("plan.hostId != "))
        assertFalse(
            "the constant is written under something other than the peer that was measured",
            source.contains("StoredCalibration(filesDir, StoredCalibration.ANONYMOUS_PEER)")
        )
    }

    /**
     * The role is handed in, never worked out from the pairing file. Both handsets in this room
     * hold a scanned pairing - they have each scanned the other at some point - so "has a scanned
     * pairing" makes both of them the sink and no run can start at all. Found by reading the two
     * phones before the first run rather than by watching one fail.
     */
    /**
     * Having a clock estimate is not the same as having a settled one. MIN_SAMPLES is only the
     * point the estimator will answer at - eight of a sixty-four wide window - and the offset it
     * answers with keeps moving as the window fills. C1, the first run on hardware, scheduled its
     * five chirps against five different offsets spanning 8.1 ms, and its five alignment errors
     * moved with them one for one; the constant it stored was 12.3 ms off the harness's.
     *
     * The wait is derived from the window rather than chosen, so it cannot drift away from the
     * estimator it is waiting on.
     */
    @Test
    fun theChirpsWaitForTheClockWindowToFillRatherThanForItsFirstAnswer() {
        assertTrue(
            "the run no longer waits a whole estimator window before scheduling anything",
            source.contains("ClockOffsetEstimator.DEFAULT_WINDOW * CLOCK_INTERVAL_MILLIS")
        )
        assertTrue(source.contains("CLOCK_FILL_NANOS"))
    }

    /**
     * The constant is only as good as the offset the chirps were scheduled against, so a run that
     * does not record its clock cannot be told apart afterwards from one whose room was noisy.
     * That is what C1 cost to find out by hand.
     */
    @Test
    fun aRunRecordsTheClockItWasScheduledAgainst() {
        assertTrue(source.contains("\\\"clock\\\":{"))
        assertTrue(source.contains("uncertaintyNanos"))
    }

    /**
     * A case id is a directory the run store creates and never clears, so a measurement filed
     * under an archived run's id overwrites whatever that run left behind. C1 did exactly that on
     * the first hardware run: both handsets lost the calibration.wav an earlier alignment run had
     * put in runs/C1. The archive numbers every letter in the ones and tens, so ninety and up is
     * the first range nothing can be standing in.
     */
    @Test
    fun theCasesSitPastEverySeriesTheHarnessHasArchived() {
        val cases = Regex("const val CASE_(?:MEASURE|VERIFY) = \"([A-Z])([0-9]+)\"")
            .findAll(source).map { it.groupValues[2].toInt() }.toList()
        assertEquals(2, cases.size)
        assertTrue("a case id could be an archived run's directory", cases.all { it >= 90 })
    }

    @Test
    fun theRoleIsToldToTheScreenRatherThanGuessedFromThePairingFile() {
        assertTrue(source.contains("intent.getStringExtra(\"role\")"))
        assertFalse(
            "the role is derived from the pairing file, which both handsets of a pair can hold",
            source.contains("if (PairedHost(filesDir).read() != null) CalibrationRole.SINK")
        )
    }
}
