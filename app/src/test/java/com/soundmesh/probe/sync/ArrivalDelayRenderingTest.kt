package com.soundmesh.probe.sync

import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the renderer holds its whole timeline back, and not only the moment a chunk is let go of.
 *
 * Read out of the source, the way [PeerCalibrationRunnerTest] reads its runner's: this class opens
 * an AudioTrack, and there is no Robolectric here to run one against. Weak as a test and kept for
 * one reason - the failure it guards is silent. Delaying at the release gate alone leaves the drift
 * controller measuring the delay as error and correcting it away a frame at a time, until the room
 * is back where it started with every counter on screen still reading healthy.
 */
class ArrivalDelayRenderingTest {
    private val source =
        File("src/main/java/com/soundmesh/probe/sync/SyncRenderer.kt").readText(Charsets.UTF_8)

    private val near = "a1b2c3d4e5f60718"
    private val far = "0918273645abcdef"

    private val room = SpatialField(
        SpatialMode.SPLIT,
        SpatialLayout(
            listOf(
                SpatialPosition(near, 0.0, 1.0),
                SpatialPosition(far, 0.0, 3.0)
            )
        ),
        metresPerUnit = 1.0
    )

    /**
     * Every clock read in the playing loop goes through the delayed one.
     *
     * Stated as "the raw clock is read in exactly three places" rather than as a list of the call
     * sites, because the failure is a call site being **added**: the loop reads the clock for the
     * release gate, the trim, the drift cadence and the end of the run, and any one of them taken
     * raw puts this handset's output back where it was by a different route.
     */
    @Test
    fun theRawClockIsNotReadInsideThePlayingLoop() {
        val reads = Regex("hostNanosNow\\(\\)").findAll(source).count()

        assertEquals(
            "hostNanosNow() is read $reads times: the loop must read playHostNanos() instead",
            3,
            reads
        )
        assertTrue(source.contains("private fun playHostNanos(): Long = hostNanosNow() - arrivalDelayNanos"))
        assertTrue(source.contains("val heardAtHostNanos = playHostNanos() + depthNanos"))
        assertTrue(
            source.contains(
                "playbackErrorFrames(playHostNanos(), pendingFrames, timelineNextHostNanos, SAMPLE_RATE)"
            )
        )
    }

    /** What the rule arriving does to this handset, which is the only thing it does to the clock. */
    @Test
    fun aRuleArrivingSetsWhatThisHandsetWaits() {
        val renderer = renderer(near)

        renderer.applySpatialField(room)

        assertEquals(2.0 / 343.0 * 1000, renderer.arrivalDelayNanos() / 1_000_000.0, 0.01)
    }

    /** The furthest handset waits for nobody, and a room with no scale makes everybody furthest. */
    @Test
    fun theFurthestHandsetAndAnUnmeasuredRoomBothWaitForNothing() {
        assertEquals(0L, renderer(far).also { it.applySpatialField(room) }.arrivalDelayNanos())
        assertEquals(
            0L,
            renderer(near).also { it.applySpatialField(room.copy(metresPerUnit = 0.0)) }
                .arrivalDelayNanos()
        )
    }

    /**
     * A rule withdrawn puts the clock back where it was, and so does a rule about a room this
     * handset is not in. Both are how a session ends up playing under no rule at all, and a delay
     * left standing under no rule is a handset quietly late for the rest of the run.
     */
    @Test
    fun aHandsetUnderNoRuleWaitsForNothing() {
        val renderer = renderer(near)
        renderer.applySpatialField(room)

        renderer.applySpatialField(null)
        assertEquals(0L, renderer.arrivalDelayNanos())

        renderer.applySpatialField(room)
        renderer.applySpatialField(
            room.copy(
                layout = SpatialLayout(listOf(SpatialPosition("ffffffffffffffff", 0.0, 1.0))),
                otherHalfIds = emptySet()
            )
        )
        assertEquals(0L, renderer.arrivalDelayNanos())
    }

    /**
     * A calibration run has no rule and so no delay, which is what keeps every alignment number
     * comparable with the archive: the chirp is the instrument, and an instrument that plays a few
     * milliseconds late measures those milliseconds as the thing it was pointed at.
     */
    @Test
    fun aRunWithNoRuleAtAllIsTheRunTheArchiveWasMeasuredOn() {
        assertEquals(0L, renderer(null).arrivalDelayNanos())
    }

    private fun renderer(peerId: String?) = SyncRenderer(
        PlaybackScheduler(SyncRenderer.FRAMES_PER_CHUNK, 8),
        DriftController(),
        spatialPeerId = peerId,
        hostNanosNow = { 0L }
    )
}
