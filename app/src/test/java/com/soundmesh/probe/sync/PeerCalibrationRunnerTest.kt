package com.soundmesh.probe.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four properties of the calibration run that a quiet room would otherwise have to teach.
 *
 * Read out of the source rather than exercised, the way [com.soundmesh.product.CalibrateActivityTest]
 * reads its screen's: this class opens an AudioTrack and an AudioRecord, and there is no
 * Robolectric here to run them against. A text assertion is a weak test and is kept only because
 * each of these costs a run of a quiet room to find out about, and because each carries the reason
 * it exists.
 */
class PeerCalibrationRunnerTest {
    private val source =
        File("src/main/java/com/soundmesh/probe/sync/PeerCalibrationRunner.kt").readText(Charsets.UTF_8)

    /**
     * A recording that would not open has to stop the run before it plays a chirp. Without the
     * guard the handset spends most of a minute making noise in somebody's quiet room, and nothing
     * will ever read a note of it.
     */
    @Test
    fun aRunWithNoRecordingStopsInsteadOfPlayingOutItsChirps() {
        assertTrue(
            "the chirps are submitted without checking that the recording opened",
            source.contains("if (recordingFailure != null) return")
        )
    }

    /**
     * The chirp goes through the same scheduler, output depth compensation and drift correction
     * the music does. A chirp played on a private AudioTrack would measure a different quantity
     * and store it under the same name - the mistake CalibrationRunner's own comment exists to
     * prevent.
     */
    @Test
    fun theChirpTravelsTheSamePathTheMusicDoes() {
        assertTrue(source.contains("SyncRenderer("))
        assertTrue(source.contains("scheduler.submit(AudioChunk("))
    }

    /**
     * Both handsets read their recordings against the SINK's chirp instant, because that is the
     * anchor OnDeviceAlignment and every archived analysis are written around. A host searching
     * from its own staggered instant would be half a second off centre on every pair, on a window
     * only half a second wide either way.
     */
    @Test
    fun bothSidesReadTheirRecordingAgainstTheSameAnchor() {
        assertTrue(
            "the recording is read against something other than the plan's shared anchor",
            source.contains("firstChirpAtHostNanos = plan.firstChirpAtHostNanos")
        )
    }

    /**
     * An uncaught throw on any thread takes the whole process with it, which on hardware looks
     * like the app vanishing rather than like a calibration failing. The recording runs on its own
     * thread, and the first run of the output-lead calibration ended exactly that way.
     */
    @Test
    fun aRecordingThatThrowsLeavesTheRunStandingToSaySo() {
        assertTrue(source.contains("runCatching"))
        assertTrue(source.contains("recordingFailure ="))
    }
}
