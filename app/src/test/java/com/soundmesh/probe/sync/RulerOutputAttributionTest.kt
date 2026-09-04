package com.soundmesh.probe.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the measuring path to the output attribution every archived run was taken on.
 *
 * The product's capturing host has to play through the accessibility usage, because capture only
 * works with the media volume at zero and a media-usage output is muted along with it. That is a
 * product decision, and it must not reach the ruler: which usage an AudioTrack declares can change
 * the output path the framework gives it, and with it the latency the whole M1/M2 archive is
 * measured against. The parameter therefore exists with a default, and this is what says the
 * default is still the archive's and that the harness still takes it.
 *
 * Read from source rather than exercised, for the reason [LauncherIntentDeliveryTest] is: the class
 * under it needs an AudioTrack, and what is being guarded here is a declaration.
 */
class RulerOutputAttributionTest {
    private fun source(path: String) = File("src/main/java/com/soundmesh/$path").readText(Charsets.UTF_8)

    @Test
    fun theRenderersDefaultAttributionIsTheOneTheArchiveWasMeasuredOn() {
        val renderer = source("probe/sync/SyncRenderer.kt")

        assertTrue(
            "SyncRenderer's playbackUsage default is no longer PlaybackUsage.MEDIA",
            renderer.contains("private val playbackUsage: PlaybackUsage = PlaybackUsage.MEDIA,")
        )
    }

    @Test
    fun theMeasuringPathNeverAsksForAnotherAttribution() {
        for (path in listOf("probe/sync/SyncActivity.kt", "session/SinkSession.kt")) {
            assertFalse(
                "$path now sets playbackUsage, so its runs are no longer comparable with the archive",
                source(path).contains("playbackUsage")
            )
        }
    }
}
