package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun anOrdinaryReadingIsNotRejected() {
        // ~210ms of real output depth with a timestamp refreshed 5ms ago: the everyday case the
        // rejection must leave exactly as it was, pinned to a value it cannot quietly absorb.
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
    fun anExtrapolationPastEverythingWrittenIsRejectedRatherThanFlooredAtZero() {
        // An underrun, or a getTimestamp pair the HAL never refreshed, carries the extrapolated
        // position far past every frame ever written. A physical run reported -17798 frames
        // (-371ms) this way. Zero is not the answer: it asserts a completely empty output buffer,
        // which is a specific and wrong measurement the drift controller would act on.
        val pending = pendingPlaybackFrames(
            writtenFrames = 20_000L,
            framePosition = 19_000L,
            timestampNanos = 0L,
            nowNanos = 1_000_000_000L,
            sampleRate = 48000
        )

        assertNull(pending)
    }

    @Test
    fun aPositionAlreadyPastTheWrittenCountIsRejected() {
        // The same rejection with no extrapolation at all: the reported position alone is already
        // ahead of the write stream.
        val pending = pendingPlaybackFrames(
            writtenFrames = 20_000L,
            framePosition = 25_000L,
            timestampNanos = 0L,
            nowNanos = 0L,
            sampleRate = 48000
        )

        assertNull(pending)
    }

    @Test
    fun aPositionExactlyAtTheWrittenCountIsAGenuineZeroNotARejection() {
        // The boundary: the extrapolated position lands exactly on writtenFrames. Everything
        // handed over has been heard and nothing more - a drained output is an ordinary reading,
        // not an impossible one, so it must come back as 0 rather than as a rejected sample.
        // 20ms of extrapolation at 48kHz is exactly one 960-frame chunk.
        val pending = pendingPlaybackFrames(
            writtenFrames = 20_000L,
            framePosition = 19_040L,
            timestampNanos = 0L,
            nowNanos = 20_000_000L,
            sampleRate = 48000
        )

        assertEquals(0L, pending)
    }

    @Test
    fun theExtrapolatedPositionIsExposedSoCallersCanSeeAReadingRejected() {
        // What the renderer counts pendingRejected on: the unbounded position, so it can tell a
        // rejected reading from a genuinely empty output without repeating the extrapolation.
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
