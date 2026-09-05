package com.soundmesh.product

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two properties of the calibration screen that hardware taught rather than reasoning.
 *
 * Read out of the source rather than exercised, the way [com.soundmesh.probe.sync.RulerOutputAttributionTest]
 * reads the renderer's: an Activity's launch mode and its thread guards are framework behaviour,
 * and there is no Robolectric here to run them against. A text assertion is a weak test and is kept
 * only because both of these cost a run of a quiet room to find out about, and because each carries
 * the reason it exists.
 */
class CalibrateActivityTest {
    private val source = File("src/main/java/com/soundmesh/product/CalibrateActivity.kt").readText(Charsets.UTF_8)

    /**
     * The screen is singleTask, so starting it a second time delivers to onNewIntent and never
     * reaches onCreate. Without an override the screen holds the previous run's answer and measures
     * nothing - which is how the Magic6's second reading was lost: `am start` reported success and
     * three minutes of quiet room bought nothing at all.
     */
    @Test
    fun aSecondStartRunsTheCalibrationAgainRatherThanShowingTheLastAnswer() {
        assertTrue(
            "CalibrateActivity no longer answers a second start, so calibrating twice does nothing",
            source.contains("override fun onNewIntent(")
        )
    }

    /**
     * The measurement runs on its own thread, and an uncaught throw on any thread takes the whole
     * process with it. The first run of this ended that way - a case id the run store refused,
     * thrown on the recording thread, the app simply gone.
     */
    @Test
    fun aCalibrationThatFailsLeavesTheAppStandingToSaySo() {
        assertTrue(
            "the calibration thread's body is no longer guarded, so a failure kills the process",
            source.contains("runCatching { measure() }")
        )
    }
}
