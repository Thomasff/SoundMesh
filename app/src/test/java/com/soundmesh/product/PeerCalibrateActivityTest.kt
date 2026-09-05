package com.soundmesh.product

import java.io.File
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
    @Test
    fun theRoleIsToldToTheScreenRatherThanGuessedFromThePairingFile() {
        assertTrue(source.contains("intent.getStringExtra(\"role\")"))
        assertFalse(
            "the role is derived from the pairing file, which both handsets of a pair can hold",
            source.contains("if (PairedHost(filesDir).read() != null) CalibrationRole.SINK")
        )
    }
}
