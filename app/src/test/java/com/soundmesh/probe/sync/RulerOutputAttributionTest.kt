package com.soundmesh.probe.sync

import com.soundmesh.probe.PlaybackUsage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the measuring path to the output attribution every archived run was taken on.
 *
 * Which usage an AudioTrack declares can change the output path the framework gives it, and with it
 * the latency the whole M1/M2 archive is measured against. A run may ask for another one - that is
 * the only way to find out what the product's capturing host costs, and a listener reporting that
 * one handset sounds slightly early is not a number. What must not happen is a run getting one
 * without asking.
 */
class RulerOutputAttributionTest {
    private fun source(path: String) = File("src/main/java/com/soundmesh/$path").readText(Charsets.UTF_8)

    @Test
    fun theRenderersDefaultAttributionIsTheOneTheArchiveWasMeasuredOn() {
        assertTrue(
            "SyncRenderer's playbackUsage default is no longer PlaybackUsage.MEDIA",
            source("probe/sync/SyncRenderer.kt")
                .contains("private val playbackUsage: PlaybackUsage = PlaybackUsage.MEDIA,")
        )
    }

    /** A run that asked for nothing gets the archive's attribution, not the last one somebody used. */
    @Test
    fun anUnaskedForAttributionIsTheArchivesOwn() {
        assertEquals(PlaybackUsage.MEDIA, PlaybackUsage.fromName(null))
    }

    @Test
    fun theHarnessTakesItsAttributionFromTheRunRatherThanTheBuild() {
        assertTrue(
            "SyncActivity no longer reads playback_usage, so a run cannot say which output it used",
            source("probe/sync/SyncActivity.kt")
                .contains("PlaybackUsage.fromName(intent.getStringExtra(\"playback_usage\"))")
        )
    }

    /** The product's sink stays on media: only the capturing host has a reason to be elsewhere. */
    @Test
    fun theProductsSinkNeverAsksForAnotherAttribution() {
        assertFalse(
            "SinkSession now sets playbackUsage",
            source("session/SinkSession.kt").contains("playbackUsage")
        )
    }
}
