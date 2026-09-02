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
    fun anOrdinaryReadingIsNotTouchedByTheLowerBound() {
        // ~210ms of real output depth with a timestamp refreshed 5ms ago: the everyday case the
        // bound must leave exactly as it was, pinned to a value the clamp cannot quietly absorb.
        val pending = pendingPlaybackFrames(
            writtenFrames = 100_000L,
            framePosition = 89_680L,
            timestampNanos = 0L,
            nowNanos = 5_000_000L,
            sampleRate = 48000
        )

        assertEquals(10_080L, pending)
    }

    @Test
    fun anExtrapolationPastEverythingWrittenReportsNothingPendingRatherThanANegativeCount() {
        // An underrun, or a getTimestamp pair the HAL never refreshed, carries the extrapolated
        // position far past every frame ever written. A physical run reported -17798 frames
        // (-371ms) this way, and playback cannot have consumed frames that were never handed over.
        val pending = pendingPlaybackFrames(
            writtenFrames = 20_000L,
            framePosition = 19_000L,
            timestampNanos = 0L,
            nowNanos = 1_000_000_000L,
            sampleRate = 48000
        )

        assertEquals(0L, pending)
    }

    @Test
    fun aPositionAlreadyPastTheWrittenCountReportsNothingPending() {
        // The same bound with no extrapolation at all: the reported position alone is already
        // ahead of the write stream.
        val pending = pendingPlaybackFrames(
            writtenFrames = 20_000L,
            framePosition = 25_000L,
            timestampNanos = 0L,
            nowNanos = 0L,
            sampleRate = 48000
        )

        assertEquals(0L, pending)
    }

    @Test
    fun theExtrapolatedPositionIsExposedSoCallersCanSeeTheBoundFire() {
        // What the renderer counts pendingClamped on: the unbounded position, so it can tell a
        // clamped reading from a genuinely empty output without repeating the extrapolation.
        val position = extrapolatedPlaybackFrames(
            framePosition = 19_000L,
            timestampNanos = 0L,
            nowNanos = 1_000_000_000L,
            sampleRate = 48000
        )

        assertEquals(67_000L, position)
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
