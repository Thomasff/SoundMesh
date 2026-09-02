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
