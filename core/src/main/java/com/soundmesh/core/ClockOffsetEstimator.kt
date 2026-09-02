package com.soundmesh.core

/**
 * Estimates the offset between two clocks from a sliding window of exchanges.
 *
 * Only the shortest round trips are kept: a short round trip spent the least time queued,
 * so its forward and return legs are the closest to equal, and asymmetry is the only error
 * the midpoint cannot cancel.
 */
class ClockOffsetEstimator(
    private val windowSize: Int = DEFAULT_WINDOW,
    private val bestCount: Int = DEFAULT_BEST
) {
    private val window = ArrayDeque<ClockExchange>()

    @Synchronized
    fun record(exchange: ClockExchange) {
        window.addLast(exchange)
        while (window.size > windowSize) window.removeFirst()
    }

    @Synchronized
    fun estimate(atLocalNanos: Long): ClockEstimate? {
        if (window.size < MIN_SAMPLES) return null
        // Filter out exchanges with negative round trips (out-of-order timestamps)
        val validExchanges = window.filter { it.roundTripNanos >= 0 }
        if (validExchanges.size < MIN_SAMPLES) return null
        val best = validExchanges.sortedBy { it.roundTripNanos }.take(bestCount)
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
        val offset = intercept + slope * ((atLocalNanos - baseNanos) / 1e9)
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

    companion object {
        const val MIN_SAMPLES = 8
        const val DEFAULT_WINDOW = 32
        const val DEFAULT_BEST = 8
        private const val MAX_DRIFT_PPM = 500.0
    }
}
