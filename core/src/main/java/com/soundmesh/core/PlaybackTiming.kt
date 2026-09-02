package com.soundmesh.core

/**
 * How far ahead of (positive) or behind (negative) the shared timeline a chunk about to be
 * written will actually land, in frames - the raw signal DriftController corrects.
 *
 * Bytes written now are not heard now: they queue up behind whatever the output already holds,
 * and are only heard once that backlog drains. At the nominal sample rate that takes
 * `pendingFrames / sampleRate` seconds, so the actual heard instant is `nowHostNanos +
 * pendingFrames * 1e9 / sampleRate` - never minus, which would place it in the past by twice the
 * output buffer's depth instead of accounting for it.
 *
 * The result is target minus heard, not heard minus target: a chunk that lands early (heard
 * before its target) is running ahead of schedule, and correcting "ahead" means delaying it,
 * which is exactly what DriftController's positive-means-insert convention does - inserting a
 * frame makes everything after it land later. A chunk landing late (heard after its target,
 * behind schedule) must come out negative, so DriftController drops a frame instead and
 * everything after it lands sooner.
 */
fun playbackErrorFrames(nowHostNanos: Long, pendingFrames: Long, targetHostNanos: Long, sampleRate: Int): Int {
    val heardNanos = nowHostNanos + pendingFrames * 1_000_000_000L / sampleRate
    return ((targetHostNanos - heardNanos) * sampleRate / 1_000_000_000L).toInt()
}

/**
 * Frames written to the output but not yet heard, with the playback position extrapolated from
 * the instant it was true up to [nowNanos].
 *
 * AudioTrack.getTimestamp reports a pair - [framePosition] and the monotonic instant
 * [timestampNanos] that position was true - and the HAL refreshes it far less often than the
 * ACQUIRING cadence samples it. Reading framePosition as the position right now is therefore late
 * by however long ago that refresh happened - the always-late-never-early stale-reading behaviour
 * design section 9.1 records as measured. That staleness does not cancel out: the error signal is a
 * difference between two readings taken one chunk apart, so a timestamp frozen across a 20ms
 * iteration enters as a whole chunk of phantom error, twenty times DriftController's deadband and
 * of the wrong sign. Advancing the position at the nominal rate over the elapsed duration removes
 * it.
 *
 * [timestampNanos] and [nowNanos] must share one time base (TIMEBASE_MONOTONIC, i.e.
 * System.nanoTime()) - the elapsed value is a duration, so this holds on the sink too, where host
 * time has a different origin. A negative elapsed is clamped to zero so a clock quirk cannot
 * inflate the pending count.
 *
 * Null when the extrapolated position runs past [writtenFrames]: playback cannot have consumed
 * frames that were never handed over, so such a reading is unusable and is rejected - exactly like
 * getTimestamp returning false. The extrapolation itself has no upper bound, so an underrun - or a
 * stale pair the HAL never refreshed - carries the position past the whole write stream and
 * produced -17798 frames (-371ms) of "pending" on a measured two handset run.
 *
 * Flooring that at zero instead was tried and was wrong. Zero is not a neutral value: it asserts
 * that the output buffer is completely empty, a specific claim the caller has no reason to believe,
 * and it reaches [playbackErrorFrames] and hence DriftController as if it were a measurement. On a
 * measured sink about 5% of readings arrived that way, which pushed the median-filtered error back
 * and forth across the deadband edge and stopped the phase machine ever completing its run of
 * consecutive in-deadband samples - acquisition never converged. An unusable reading has to be
 * discarded, not substituted.
 */
fun pendingPlaybackFrames(
    writtenFrames: Long,
    framePosition: Long,
    timestampNanos: Long,
    nowNanos: Long,
    sampleRate: Int
): Long? {
    val playbackFrames = extrapolatedPlaybackFrames(framePosition, timestampNanos, nowNanos, sampleRate)
    if (playbackFrames > writtenFrames) return null
    return writtenFrames - playbackFrames
}

/**
 * Where playback has reached at [nowNanos]: the position [framePosition] that was true at
 * [timestampNanos], advanced at the nominal rate over the elapsed duration.
 *
 * Exposed on its own only so a caller can tell whether [pendingPlaybackFrames] rejected a reading
 * - the position running past the write stream - without repeating the extrapolation and without
 * this function having to count anything itself.
 */
fun extrapolatedPlaybackFrames(framePosition: Long, timestampNanos: Long, nowNanos: Long, sampleRate: Int): Long {
    val elapsedNanos = (nowNanos - timestampNanos).coerceAtLeast(0L)
    return framePosition + elapsedNanos * sampleRate / 1_000_000_000L
}

/**
 * How many frames to drop from the front of a chunk that was released late, so that what is
 * actually written lands on the instant it was scheduled for.
 *
 * This is the fix for the release-phase error. PlaybackScheduler.poll releases a chunk once
 * `now + outputDepth` has reached its instant, and the render loop only polls once per chunk write
 * - the write blocks until the AudioTrack has room, which paces the loop at a whole chunk period.
 * So the release lands anywhere in [playAt, playAt + one chunk), and writing the chunk whole put
 * every frame in it that far late. The error was left for the drift controller to work off one
 * frame at a time, which takes about nineteen seconds for a full chunk - fine for a long stream,
 * useless for the calibration chirp, which is six chunks long and follows a two second gap that
 * re-draws the phase. Two clusters of role-swapped measurements 19.88ms apart against a 20.00ms
 * chunk are what that looks like from the microphone.
 *
 * [heardAtHostNanos] is the instant the next frame written will be heard - `now + outputDepth`,
 * the same value poll was asked with. Dropping the returned count makes frame N of the chunk, which
 * was always meant to be heard N frames into it, land exactly where it is actually heard; the
 * chunk still ends on its own boundary, so the timeline needs no adjustment. In contiguous
 * playback the previous chunk ended precisely here and this returns zero, so the trim only ever
 * bites at a start or after a gap.
 *
 * Never trims a chunk released early: that would move content earlier rather than align it.
 * Clamped to [chunkFrames] because an unclamped count would index past the payload; poll already
 * drops anything that late, so the clamp is a guard rather than a path taken.
 */
fun releaseTrimFrames(playAtHostNanos: Long, heardAtHostNanos: Long, sampleRate: Int, chunkFrames: Int): Int {
    val lateNanos = heardAtHostNanos - playAtHostNanos
    if (lateNanos <= 0L) return 0
    // Truncating division: a fraction of a frame late still leaves that frame in the future.
    val lateFrames = lateNanos * sampleRate / 1_000_000_000L
    return lateFrames.coerceAtMost(chunkFrames.toLong()).toInt()
}
