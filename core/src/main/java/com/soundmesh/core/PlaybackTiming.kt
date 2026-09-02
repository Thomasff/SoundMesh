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
 */
fun pendingPlaybackFrames(
    writtenFrames: Long,
    framePosition: Long,
    timestampNanos: Long,
    nowNanos: Long,
    sampleRate: Int
): Long {
    val elapsedNanos = (nowNanos - timestampNanos).coerceAtLeast(0L)
    return writtenFrames - (framePosition + elapsedNanos * sampleRate / 1_000_000_000L)
}
