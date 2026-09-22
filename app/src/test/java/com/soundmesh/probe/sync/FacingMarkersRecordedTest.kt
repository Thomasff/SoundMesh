package com.soundmesh.probe.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a marked run has to leave behind for its two recordings to be read as a facing pair.
 *
 * The product's pair arrangement cancels the geometry by construction - two handsets, each
 * recording the other, the half sum taking the flight time out - and until now it only ever
 * covered the chirp path. The pair constant is spent on the streamed path, which the two
 * differ on by about 0.92 ms, so the streamed sweeps have to appear in the same recordings.
 *
 * Asserted on the source text because the activity needs a device to run.
 */
class FacingMarkersRecordedTest {
    private val source =
        File("src/main/java/com/soundmesh/probe/sync/SyncActivity.kt").readText(Charsets.UTF_8)

    /**
     * Both grids are written down by both sides, from what each one saw rather than from a rule.
     *
     * A side could in principle derive its peer's instants: the two grids are half a stride
     * apart and both sides know the stride. It must not. The schedule is computed live on the
     * host, once per chunk, and its marker-to-marker gap has been measured at 4998.17-5001.99 ms
     * against a nominal 5000 - so a derived grid would be wrong by about a millisecond, which is
     * the whole quantity being measured, and nothing anywhere would say so.
     */
    @Test
    fun eachSideWritesDownBothGridsFromWhatItSaw() {
        assertTrue(
            "the host no longer writes down the sink's marker instants",
            source.contains("facing?.markerStartIndex(sequence)?.let { facingMarkerPlays.add(")
        )
        assertTrue(
            "the sink no longer writes down the host's marker instants",
            source.contains("hostSweeps?.markerStartIndex(chunk.sequence)?.let {")
        )
        assertEquals(
            "one report per role carries the facing grid, and both of them do",
            2,
            Regex("MarkerPlayCodec\\.FACING_FIELD").findAll(source).count()
        )
    }

    /**
     * A marked run's recording is open before the sweeps it is there to hear.
     *
     * The chirp path opens its window one second before the chirp, which is a second after the
     * streamed segment has ended - so every recording taken so far holds the chirp pair and not
     * one streamed sweep. Asserted as an ordering rather than as an instant because that is what
     * went wrong: the window was never too narrow, it was opened too late in the method.
     */
    @Test
    fun aMarkedRunOpensItsRecordingBeforeTheStreamedSegment() {
        val hostOpens = source.indexOf("val earlyRecordThread = marked?.let {")
        val hostStreams = source.indexOf("while (System.nanoTime() < until) {")
        assertTrue("the host no longer opens a recording of its own streamed segment", hostOpens > 0)
        assertTrue(
            "the host opens its recording after the segment it is meant to cover",
            hostOpens < hostStreams
        )

        val sinkOpens = source.indexOf("val earlyRecordThread = if (ownSweeps != null)")
        val sinkPlays = source.indexOf("val rendererThread = Thread { renderer.run() }", sinkOpens)
        assertTrue("the sink no longer opens a recording of its own streamed segment", sinkOpens > 0)
        assertTrue("the sink opens its recording after it starts playing", sinkOpens < sinkPlays)
    }

    /**
     * A run without markers records exactly the window it always did.
     *
     * The archive is the only reference these numbers have, and it was taken on a recording that
     * opens one second before the sink's chirp and holds nothing else. Widening that for every
     * run would make every comparison with it a comparison between two arrangements.
     */
    @Test
    fun aRunWithoutMarkersKeepsTheWindowTheArchiveWasTakenOn() {
        assertTrue(
            "the chirp path's own window is gone, so nothing archived is comparable any more",
            source.contains("awaitHostInstant(sinkChirpAt - RECORD_LEAD_NANOS, hostNanosNow)") &&
                source.contains("val fromHostNanos = chirpAt - RECORD_LEAD_NANOS")
        )
    }

    /**
     * The widened recording closes on the same instant the narrow one does.
     *
     * It is opened before the chirp schedule exists, so it cannot be told its end when it starts;
     * it is given a ceiling instead. If the ceiling were the thing that closed it, the recording
     * would end at an instant no side computed and the chirp pair could be cut in half.
     */
    @Test
    fun theWidenedRecordingIsStoppedRatherThanLeftToItsCeiling() {
        assertEquals(
            "both sides set the instant their recording stops on",
            2,
            Regex("recordUntilHostNanos\\.set\\(").findAll(source).count()
        )
        assertEquals(
            "both sides stop on that instant rather than on the ceiling",
            2,
            Regex("stopped = \\{ hostNanosNow\\(\\) >= recordUntilHostNanos\\.get\\(\\) \\}")
                .findAll(source).count()
        )
    }
}
