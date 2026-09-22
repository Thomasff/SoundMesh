package com.soundmesh.probe.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerReleaseWatchedTest {
    /**
     * The releases recorded are the releases of this side's own sweeps, and of nothing else.
     *
     * The sink sees three kinds of chunk go past: its own marker chunks, the host's marker chunks
     * (which it replaces with silence), and ordinary audio. Only the first kind has a schedule
     * this side wrote down, so only the first kind has anything to compare a release against. A
     * watch on any of the others would not fail - it would add releases with no schedule beside
     * them, and the residual they produce is a number like any other.
     *
     * Asserted on the source text because the activity needs a device to run.
     */
    @Test
    fun theSinkWatchesTheReleaseOfEverySweepItStampsAndOfNoOtherChunk() {
        val source = File("src/main/java/com/soundmesh/probe/sync/SyncActivity.kt").readText(Charsets.UTF_8)
        val stamped = Regex("""ownSweeps\?\.markerStartIndex\(chunk\.sequence\)\?\.let \{([\s\S]*?)\}""")
            .find(source)?.groupValues?.get(1)
            ?: throw AssertionError("the sink no longer records its own marker schedule")

        assertTrue(
            "a marker whose schedule is written down but whose release is not cannot be read: $stamped",
            stamped.contains("watchMarkerRelease(chunk.sequence)")
        )
        assertEquals(
            "the only chunks worth watching are the ones this side stamped itself",
            1,
            Regex("watchMarkerRelease\\(").findAll(source).count()
        )
    }

    /**
     * The instant a marker is recorded at is the instant its depth was read, not a later one.
     *
     * AudioTrack.write blocks while the output is full. Reading the clock after it returns puts
     * that wait inside the answer, while the depth it is added to was measured before the wait -
     * and the two then double-count it. Measured: on two of four rounds this made alternate
     * markers read exactly four chunks out, an 80 ms step in a quantity whose whole range of
     * interest is one millisecond, and it did so without a single trim or dropped chunk to say
     * anything was wrong.
     */
    @Test
    fun aMarkerReleaseIsStampedWithTheClockItsDepthWasReadAgainst() {
        val source = File("src/main/java/com/soundmesh/probe/sync/SyncRenderer.kt").readText(Charsets.UTF_8)
        val record = Regex("""markerReleases\.computeIfAbsent\([\s\S]*?\n {16}\}""")
            .find(source)?.value
            ?: throw AssertionError("the renderer no longer records marker releases")

        assertTrue(
            "the clock here has already waited for the output to drain: $record",
            !record.contains("System.nanoTime()")
        )
    }
}
