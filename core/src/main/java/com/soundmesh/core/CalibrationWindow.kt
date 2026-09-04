package com.soundmesh.core

/**
 * Where in a recording a scheduled chirp pair should be looked for.
 *
 * This is the whole reason the measurement is cheap enough to run on a handset. Cross-correlation
 * costs a pass over the reference for every lag searched, so the width of the search is the width
 * of the bill: a 60 second chirp interval is 2.88 million lags and was measured at 22 seconds on a
 * PC. The PC had no choice - a recording pulled off a device never said when it opened, so the
 * only safe window was the whole interval.
 *
 * A device does know. The recorder and the chirp schedule live in the same process, so the instant
 * the recording opened and the instant a chirp was queued for are both in hand, and the search
 * collapses to the uncertainty between them.
 *
 * Nothing here enters a measurement. The window only says where to look; the arrival is still
 * pinned to the sample by [ChirpCorrelator]. What it needs is half a second of accuracy against an
 * alignment figure quoted in thousandths of a millisecond - five orders of magnitude of slack -
 * which is why an ordinary clock reading beside `startRecording()` is enough and no capture
 * timestamp API has to be trusted.
 */
object CalibrationWindow {
    /**
     * Half a second either side, in frames.
     *
     * It has to cover everything between the clock reading and the first sample actually captured:
     * the tail of `startRecording()`, thread scheduling, and whatever buffering the platform does
     * before the first read returns. Half a second is far wider than any of those and still costs
     * only a fraction of one interval, so the margin is taken rather than argued about.
     */
    const val DEFAULT_UNCERTAINTY_FRAMES = ChirpGenerator.SAMPLE_RATE / 2

    /**
     * Frames of [recordingStartedAtHostNanos]'s recording that could hold the chirp pair scheduled
     * at [fromHostNanos] and [toHostNanos].
     *
     * The two instants may arrive either way round - which of the pair is first depends on which
     * device is recording - so they are ordered here rather than at every call site. The window is
     * clamped at zero because there are no samples from before the recording opened; a chirp
     * scheduled earlier than that is a caller's mistake this cannot repair, and it shows up as an
     * arrival sitting on the search edge, which the analysis already refuses to trust.
     */
    fun searchWindow(
        recordingStartedAtHostNanos: Long,
        fromHostNanos: Long,
        toHostNanos: Long,
        uncertaintyFrames: Int = DEFAULT_UNCERTAINTY_FRAMES,
        sampleRate: Int = ChirpGenerator.SAMPLE_RATE
    ): IntRange {
        require(uncertaintyFrames >= 0) { "uncertaintyFrames cannot be negative" }
        val first = minOf(fromHostNanos, toHostNanos)
        val last = maxOf(fromHostNanos, toHostNanos)
        val from = frameAt(recordingStartedAtHostNanos, first, sampleRate) - uncertaintyFrames
        val to = frameAt(recordingStartedAtHostNanos, last, sampleRate) + uncertaintyFrames
        return maxOf(0, from)..maxOf(0, to)
    }

    /** The frame a host instant lands on, counted from the first sample of the recording. */
    private fun frameAt(recordingStartedAtHostNanos: Long, atHostNanos: Long, sampleRate: Int): Int =
        ((atHostNanos - recordingStartedAtHostNanos) * sampleRate / 1_000_000_000L).toInt()
}
