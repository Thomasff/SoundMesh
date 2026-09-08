package com.soundmesh.core

/** What the clock exchanges say about the link a run is about to be spent on. */
data class LinkQuality(
    val medianRoundTripNanos: Long,
    val p90RoundTripNanos: Long,
    val samples: Int
) {
    val usable: Boolean get() = medianRoundTripNanos <= LinkSurvey.MAX_MEDIAN_ROUND_TRIP_NANOS
}

/**
 * Reads the link out of the exchanges the clock has already made, before a run spends a minute of
 * chirps on it.
 *
 * Two handsets that cannot exchange a thirty-byte packet quickly cannot be aligned, and no
 * estimator recovers it: the offset a two-way exchange gives is biased by half the difference
 * between the two one-way delays, which is systematic rather than noise. On 2026-09-08 that was
 * measured directly - three runs on one room's router sat at 56 to 70 ms median round trip and
 * failed at 2.5 to 3.2 ms residual, while a hotspot in the same room at 17.8 ms passed at 0.165,
 * and four archived runs on a different router at 7.0 to 8.0 ms had worked all along. Replaying
 * those recordings through minimum filters, wider windows and every best-of cut moved none of it.
 *
 * So this is not a diagnostic. It is the difference between telling somebody their network is not
 * good enough and letting them stand still for fifty seconds to be told a number they cannot read.
 */
object LinkSurvey {
    /**
     * Above this a run is refused rather than attempted.
     *
     * An engineering value, on the same terms as [AlignmentVerdict.MAX_CLUSTER_MEAN_MS]: it sits in
     * the gap between the slowest link that has ever produced a passing run (17.8 ms) and the
     * fastest that has produced a failing one (56.3 ms), and nothing has been measured in between.
     * Eight runs is not enough to place it exactly; it is enough to know that 8 ms works and 56 ms
     * does not. Widen it when a link between the two is measured, not before.
     */
    const val MAX_MEDIAN_ROUND_TRIP_NANOS = 40_000_000L

    /** Below this there is nothing to judge, matching [ClockOffsetEstimator.MIN_SAMPLES]. */
    const val MIN_SAMPLES = ClockOffsetEstimator.MIN_SAMPLES

    /**
     * The link, or null when too few exchanges have come back to say anything about it.
     *
     * Negative round trips are dropped exactly as [ClockOffsetEstimator.estimate] drops them: they
     * are a clock that moved under the measurement, and counted as ordinary samples they would rate
     * a broken link as the fastest one in the room.
     */
    fun of(exchanges: List<ClockExchange>): LinkQuality? {
        val trips = exchanges.map { it.roundTripNanos }.filter { it >= 0 }.sorted()
        if (trips.size < MIN_SAMPLES) return null
        return LinkQuality(
            medianRoundTripNanos = median(trips),
            p90RoundTripNanos = trips[(trips.size * 9 - 1) / 10],
            samples = trips.size
        )
    }

    /** The midpoint, averaging the middle two of an even count, as [AlignmentVerdict] does. */
    private fun median(sorted: List<Long>): Long {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
