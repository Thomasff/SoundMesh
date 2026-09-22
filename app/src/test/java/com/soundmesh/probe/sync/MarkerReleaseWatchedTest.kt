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
}
