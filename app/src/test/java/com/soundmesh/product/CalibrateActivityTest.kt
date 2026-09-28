package com.soundmesh.product

import java.io.File
import org.junit.Assert.assertEquals
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
            source.contains("runCatching { measure(")
        )
    }

    /**
     * Both of the outputs this run plays on are drawn, and the start is refused while either is
     * silent.
     *
     * This measurement is one phone listening to itself twice - once on the ordinary path, once
     * on the path a capturing host is heard on - and it reads the difference between the two
     * arrivals. A handset silent on either of them measures its own noise floor instead, which
     * fails as a refusal on a good day and as a number on a bad one. Until 2026-09-19 the screen
     * said nothing about volume and carried no control for it, so the only way out of a run that
     * kept refusing was to guess.
     *
     * The rows are read back off the streams rather than echoed from the slider: `setStreamVolume`
     * has been seen on this project's own handsets to take a value, throw nothing, and move
     * nothing.
     */
    @Test
    fun bothOutputsAreDrawnAndTheStartIsRefusedWhileEitherIsSilent() {
        assertTrue(
            "the slider moves one output and leaves the other where it was",
            source.contains("handsetVolume.setBoth(percent)")
        )
        assertTrue(
            "only one of the two outputs is drawn",
            source.contains("listOf(false to R.string.calibrate_volume_media, true to R.string.calibrate_volume_alarm)")
        )
        assertTrue(
            "the rows are drawn from what was asked for rather than from what the streams read",
            source.contains("val reading = handsetVolume.read(capturing)")
        )
        assertTrue(
            "this screen keeps the level it set after somebody leaves it",
            source.contains("restoresOnLeaving(volumeChangedBefore, handsetVolume.changed())")
        )

        val screen = File("src/main/java/com/soundmesh/product/CalibrateScreen.kt").readText(Charsets.UTF_8)
        assertTrue(
            "the start button is live on a handset nothing can hear",
            screen.contains("enabled = tooQuiet.isEmpty(),")
        )
        // The label says 50% and is not drawn from the floor, so the two are pinned together here.
        assertEquals(50, QUIET_FLOOR_PERCENT)
        // The quieter of the two, because that is the one that decides whether the run works.
        assertTrue(
            "the slider is drawn from an output that may not be the one holding the run back",
            screen.contains("percent = state.volumes.minOf { it.percent },")
        )
    }
}
