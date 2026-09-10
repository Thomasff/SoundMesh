package com.soundmesh.core

/**
 * Estimates the offset between two clocks from a sliding window of exchanges.
 *
 * Only the shortest round trips are kept: a short round trip spent the least time queued,
 * so its forward and return legs are the closest to equal, and asymmetry is the only error
 * the midpoint cannot cancel.
 */
class ClockOffsetEstimator(
    // Public so a run can record which configuration produced its estimates: without that, replaying
    // a stored run cannot know how to reproduce it, and the check that the replay still matches the
    // shipped estimator quietly stops working the first time a default moves.
    val windowSize: Int = DEFAULT_WINDOW,
    val bestCount: Int = DEFAULT_BEST
) {
    private val window = ArrayDeque<ClockExchange>()

    @Synchronized
    fun record(exchange: ClockExchange) {
        window.addLast(exchange)
        while (window.size > windowSize) window.removeFirst()
    }

    /**
     * The offset to use now, or null when the window cannot support one.
     *
     * [atLocalNanos] deliberately does not move the answer. The estimate describes the middle of
     * the window it was measured over, and asking for a later instant does not make it later - it
     * only used to make it noisier. See the anchoring note in the body.
     */
    @Synchronized
    fun estimate(atLocalNanos: Long): ClockEstimate? {
        if (window.size < MIN_SAMPLES) return null
        // Filter out exchanges with negative round trips (out-of-order timestamps)
        val validExchanges = window.filter { it.roundTripNanos >= 0 }
        if (validExchanges.size < MIN_SAMPLES) return null
        val best = validExchanges.sortedBy { it.roundTripNanos }.take(keepFor(window.size))
        val baseNanos = best.minOf { it.t1 }
        var sumX = 0.0; var sumY = 0.0; var sumXX = 0.0; var sumXY = 0.0
        for (exchange in best) {
            val x = (exchange.t1 - baseNanos) / 1e9
            val y = exchange.offsetMidpointNanos.toDouble()
            sumX += x; sumY += y; sumXX += x * x; sumXY += x * y
        }
        val count = best.size
        val denominator = count * sumXX - sumX * sumX
        // Every kept exchange landed at the same instant; a slope through one point is meaningless.
        val slope = if (denominator == 0.0) 0.0 else (count * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / count
        // Anchored at the centroid of the kept exchanges, not extrapolated to [atLocalNanos].
        //
        // A least squares line passes through the centroid of its points, so this is simply the
        // mean of the kept midpoints - and it is the one point on the line the slope's own error
        // cannot move. Extrapolating to the caller's instant multiplied that error by the lever
        // arm out to it: over 323 estimates of one measured run the slope scattered by 30 ppm and
        // the lever arm averaged 28 seconds, which put 0.85 ms of pure noise into every offset.
        // That is not a diagnostic number - the offset is what a playout instant is converted
        // through, so it landed whole in the emission timing of every chirp the sink played.
        //
        // What it costs is a lag: the estimate describes the middle of the window rather than now,
        // so it is stale by the real drift across half a window. At the 2.3 ppm measured between
        // these two handsets that is 65 microseconds, about three frames - a thirteenth of what
        // extrapolating was adding.
        val offset = sumY / count
        val driftPpm = slope / 1000.0

        // Guard against non-finite results from the linear fit
        if (!offset.isFinite() || !slope.isFinite() || !intercept.isFinite()) return null
        val offsetNanos = offset.toLong()
        // Detect saturation: if the result is an extreme value, the fit went wrong
        if (offsetNanos == Long.MAX_VALUE || offsetNanos == Long.MIN_VALUE) return null
        // Reject physically impossible drift: consumer oscillators cannot differ by more than ~100 ppm
        if (kotlin.math.abs(driftPpm) > MAX_DRIFT_PPM) return null

        return ClockEstimate(
            offsetNanos = offsetNanos,
            uncertaintyNanos = best.minOf { it.roundTripNanos } / 2,
            driftPpm = driftPpm,
            sampleCount = count
        )
    }

    /**
     * How many of the quietest to keep, given how many the window is holding right now.
     *
     * [bestCount] is a fraction of [windowSize] wearing a count's clothes: the rationale below is
     * "keep the quiet eighth", and eight of sixty-four is that fraction evaluated once and frozen.
     * While the window is still filling, a frozen count stops being a fraction - with eight
     * exchanges in hand, keeping eight of them keeps every queued one too, which is no selection at
     * all. Replayed over eighteen archived rounds, the offset the sink published in that stretch sat
     * 5.1 ms above where it settled, and the session starts rendering inside it: SinkSession waits
     * for the first estimate the guards accept and nothing more.
     *
     * Keeping the fraction instead costs nothing and waits for nothing. Across ten held-out runs -
     * four with the roles the other way round, one on a router - the error over the rendering
     * session's first ten seconds falls from 3.65 ms to 0.70 ms on the nine hotspot runs, and the
     * first estimate arrives 2.2 s sooner. Once the window is full this returns [bestCount] and the
     * rule is exactly the one every measurement above was taken under; the archived settled scatter
     * and the folded calibration bias are unchanged to three decimals.
     *
     * The router run is the one it does not rescue (18.8 to 13.6 ms, either way past the 19.4 ms a
     * listener called out). That link is bursty rather than slow, so its quiet population is not one
     * exchange in eight and there is nothing quiet to pick.
     */
    private fun keepFor(held: Int): Int =
        if (held >= windowSize) bestCount else maxOf(1, held * bestCount / windowSize)

    companion object {
        const val MIN_SAMPLES = 8
        // Wide enough to hold eight quiet exchanges, not just eight exchanges.
        //
        // Round trips between two handsets on one link are bimodal: a quiet cluster and a queued
        // one, with the quiet cluster about an eighth of the traffic on both runs measured. Keeping
        // the best eight of thirty-two therefore cuts right at the boundary of the quiet population
        // and routinely reaches past it, and a handful of asymmetric exchanges dominates a mean of
        // eight - which is why keeping a quarter of the window measured worse than keeping half of
        // it, and far worse than keeping an eighth. Replayed through two recorded runs, the offset
        // one phase disagrees with its neighbours by falls from 0.65 ms here to 0.14 ms, intervals
        // that do not overlap. The cost is lag: the fit describes the middle of a window twice as
        // long, about 64 seconds back, which at the 0.25 to 0.41 ppm measured between these two
        // crystals is 16 to 26 microseconds - under a frame, against a millisecond removed.
        const val DEFAULT_WINDOW = 64
        const val DEFAULT_BEST = 8
        private const val MAX_DRIFT_PPM = 500.0
    }
}
