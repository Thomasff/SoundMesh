package com.soundmesh.core

import kotlin.math.abs

/**
 * Where a chirp was found in a recording, and how much the find can be trusted.
 *
 * [ratio] is the peak against the noise floor. That floor is a structural property of pure noise
 * rather than a function of how loud the room is: with scores behaving like half-normal draws
 * across tens of thousands of lags, the peak sits near 4.2 sigma and the median near 0.67 sigma,
 * giving a ratio near 6.3 whatever the amplitude. A real chirp lands orders of magnitude higher -
 * tens of thousands in measurement - so [MIN_TRUSTWORTHY_RATIO] can sit far below a real chirp on
 * purpose. Rejecting a valid measurement costs a whole device session, which is the more expensive
 * failure than accepting a slightly weak true peak.
 */
data class ChirpArrival(
    val index: Int,
    val peak: Double,
    val floor: Double,
    val ratio: Double,
    /**
     * A winner sitting on the boundary is the dangerous case, not a miss. A chirp just past the
     * edge still overlaps the last lag searched, and that partial overlap can clear the confidence
     * ratio on its own - reporting an arrival that is really the window's edge, with every sign of
     * a good measurement. Anything on the boundary has to be treated as a chirp that may lie
     * outside the window entirely.
     */
    val atSearchEdge: Boolean
)

/**
 * Slides the reference across a recording and reports where it matched best.
 *
 * This is the measurement the alignment number is built on, and it lived on the PC until now: a
 * run recorded the room, the PC pulled the file and correlated it offline. Moving it here is what
 * lets two handsets answer "are we aligned" between themselves, with no cable and no third
 * machine. The PC implementation in tools/src/calibration-analysis.mjs stays as it is - the
 * recorded runs are analysed with it, and it is the reference this port is checked against.
 *
 * Cost is the reason the search window matters. Every lag costs a pass over the whole reference,
 * so a 60-second window measured 22 seconds on a PC against 190 milliseconds for a window of
 * 24000 lags. The PC has to search wide because a pulled recording never said when it started;
 * a device knows, because the recorder and the chirp schedule live in the same process.
 */
object ChirpCorrelator {
    /** Below this the arrival is noise dressed up as a measurement. Matches the PC analysis. */
    const val MIN_TRUSTWORTHY_RATIO = 20.0

    /**
     * The best match for [reference] in `recorded[searchFrom..searchTo]`, or null for an empty range.
     *
     * The noise floor is the median of every score, not the best rival peak: a calibration
     * recording deliberately holds two equally strong chirps, so each would rate the other as its
     * rival and no real measurement would ever look trustworthy.
     */
    fun findArrival(recorded: ShortArray, reference: ShortArray, searchFrom: Int, searchTo: Int): ChirpArrival? {
        val from = maxOf(0, searchFrom)
        val to = minOf(searchTo, recorded.size - reference.size)
        if (to < from) return null
        val scores = DoubleArray(to - from + 1)
        for (offset in from..to) {
            var total = 0.0
            for (index in reference.indices) {
                total += recorded[offset + index].toDouble() * reference[index].toDouble()
            }
            scores[offset - from] = abs(total)
        }
        var bestAt = 0
        for (index in 1 until scores.size) if (scores[index] > scores[bestAt]) bestAt = index
        val floor = median(scores)
        return ChirpArrival(
            index = from + bestAt,
            peak = scores[bestAt],
            floor = floor,
            ratio = if (floor == 0.0) Double.POSITIVE_INFINITY else scores[bestAt] / floor,
            atSearchEdge = bestAt == 0 || bestAt == scores.size - 1
        )
    }

    /** The upper median, sorting a copy so the caller's scores keep their order. */
    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf()
        sorted.sort()
        return sorted[sorted.size / 2]
    }
}
