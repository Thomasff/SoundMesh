package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimingTest {
    @Test
    fun perfectlySyncedPlaybackHasNoError() {
        val now = 0L
        val pendingFrames = 480L
        val sampleRate = 48000
        val target = now + pendingFrames * 1_000_000_000L / sampleRate

        assertEquals(0, playbackErrorFrames(now, pendingFrames, target, sampleRate))
    }

    @Test
    fun playbackAheadOfTheTimelineIsPositiveAndMakesDriftControllerInsert() {
        // Nothing queued, but the target is a full second in the future: this chunk would be
        // heard now, a full second early, which is running well ahead of schedule.
        val errorFrames = playbackErrorFrames(
            nowHostNanos = 0L, pendingFrames = 0L, targetHostNanos = 1_000_000_000L, sampleRate = 48000
        )

        assertEquals(48000, errorFrames)
        assertEquals(1, DriftController().observe(errorFrames).adjustFrames)
    }

    @Test
    fun playbackBehindTheTimelineIsNegativeAndMakesDriftControllerDrop() {
        // Nothing queued, but the target is a full second in the past: this chunk would still
        // only be heard now, a full second late, which is running well behind schedule.
        val errorFrames = playbackErrorFrames(
            nowHostNanos = 0L, pendingFrames = 0L, targetHostNanos = -1_000_000_000L, sampleRate = 48000
        )

        assertEquals(-48000, errorFrames)
        assertEquals(-1, DriftController().observe(errorFrames).adjustFrames)
    }

    @Test
    fun aRealisticOutputBufferDepthWithACorrectlyScheduledChunkStaysAtZero() {
        // ~210ms of pending frames, the depth SyncRenderer's own output buffer actually carries.
        // The subtraction bug this guards against is invisible at pendingFrames = 0 (both signs
        // of the depth term collapse to the same value there), so this is the case that must
        // fail against the old inline `hostNanosNow() - depthNanos` arithmetic.
        val now = 0L
        val pendingFrames = 10_080L
        val sampleRate = 48000
        val target = now + pendingFrames * 1_000_000_000L / sampleRate

        assertEquals(0, playbackErrorFrames(now, pendingFrames, target, sampleRate))
    }

    @Test
    fun aStaleTimestampIsAdvancedToNowRatherThanReadAsThePositionRightNow() {
        // The HAL last refreshed the reading a whole chunk period ago, so 960 more frames have
        // been heard since. Reading framePosition as the position now would report 960 frames
        // too many pending - twenty times DriftController's deadband, and the exact term the
        // ~50Hz ACQUIRING cadence can no longer average away.
        val pending = pendingPlaybackFrames(
            writtenFrames = 20_000L,
            framePosition = 10_000L,
            timestampNanos = 0L,
            nowNanos = 20_000_000L,
            sampleRate = 48000
        )

        assertEquals(9_040L, pending)
    }

    @Test
    fun aFreshTimestampIsLeftWhereItIs() {
        val pending = pendingPlaybackFrames(
            writtenFrames = 20_000L,
            framePosition = 10_000L,
            timestampNanos = 5_000_000_000L,
            nowNanos = 5_000_000_000L,
            sampleRate = 48000
        )

        assertEquals(10_000L, pending)
    }

    @Test
    fun aTimestampFromTheFutureCannotWindThePositionBackwards() {
        // A clock quirk that reports the position as true after now must not be extrapolated
        // negatively: that would inflate the pending count instead of leaving it alone.
        val pending = pendingPlaybackFrames(
            writtenFrames = 20_000L,
            framePosition = 10_000L,
            timestampNanos = 1_000_000_000L,
            nowNanos = 0L,
            sampleRate = 48000
        )

        assertEquals(10_000L, pending)
    }
}
