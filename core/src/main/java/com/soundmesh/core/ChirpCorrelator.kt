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
    val atSearchEdge: Boolean,
    /**
     * The first lag reaching each requested share of [peak], in the order the shares were asked
     * for, or empty when none were.
     *
     * Kept beside [index] rather than replacing it because the two answer different questions and
     * both are wanted: [peak] and [ratio] describe the window as a whole and are what the trust
     * gate reads, while an edge is where the arrival began. Answering several shares at once is
     * the point - the scores are computed once and picked from many times, so asking how much the
     * answer moves with the share costs one comparison per lag rather than another pass over the
     * recording.
     */
    val edgeIndices: List<Int> = emptyList()
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
     * What counts as an arrival having started, as a share of the loudest lag in the window.
     *
     * Measured, not guessed: on the profile [findFirstArrival] was built against, the direct path
     * reached 0.77 of the loudest lag while the noise before it sat at 0.002, so anything from
     * about 0.01 to 0.7 picks the same sample. A third is the middle of that range.
     */
    const val FIRST_ARRIVAL_SHARE = 0.3

    /** Three milliseconds at the chirp's own rate - see [findFirstArrival] for where it came from. */
    private const val FIRST_ARRIVAL_SPAN_SAMPLES = ChirpGenerator.SAMPLE_RATE * 3 / 1000

    /**
     * The best match for [reference] in `recorded[searchFrom..searchTo]`, or null for an empty range.
     *
     * The noise floor is the median of every score, not the best rival peak: a calibration
     * recording deliberately holds two equally strong chirps, so each would rate the other as its
     * rival and no real measurement would ever look trustworthy.
     */
    fun findArrival(
        recorded: ShortArray,
        reference: ShortArray,
        searchFrom: Int,
        searchTo: Int,
        edgeShares: List<Double> = emptyList()
    ): ChirpArrival? {
        val from = maxOf(0, searchFrom)
        val to = minOf(searchTo, recorded.size - reference.size)
        if (to < from) return null
        val scores = scoresOver(recorded, reference, from, to)
        return arrivalAt(scores, from, loudestOf(scores), edgeShares)
    }

    /**
     * The *first* arrival in the window rather than the loudest one.
     *
     * The two are the same sound only when the direct path is the strongest thing that reaches the
     * microphone, and across a room it often is not. Measured on one run: a handset's chirp at
     * 140 cm arrived at a laptop's microphone array with a dead noise floor before it, a clean
     * narrow peak, and then twelve milliseconds of reverberation in which one cluster came back
     * *louder than the direct path*. [findArrival] answered with that cluster, at a confidence
     * ratio of 369 against a threshold of 20, and the answer was 10.7 ms - three and a half metres
     * - late. A confidence ratio cannot see this: a reflection is a true sound off a true surface
     * and rates exactly as well as the direct path.
     *
     * So the loudest lag is used only to set the scale, and the answer is the peak belonging to
     * the first crossing of [share] of it. On a clean arrival the crossing sits a fraction of a
     * millisecond below its own peak and this returns what [findArrival] would have; the two
     * diverge only when something later is louder, which is exactly the case worth knowing about.
     *
     * [findArrival] is left alone rather than changed. Every archived run was measured with it,
     * and an analysis that quietly answers differently is one no old number can be compared with.
     */
    fun findFirstArrival(
        recorded: ShortArray,
        reference: ShortArray,
        searchFrom: Int,
        searchTo: Int,
        share: Double = FIRST_ARRIVAL_SHARE,
        edgeShares: List<Double> = emptyList()
    ): ChirpArrival? {
        val from = maxOf(0, searchFrom)
        val to = minOf(searchTo, recorded.size - reference.size)
        if (to < from) return null
        val scores = scoresOver(recorded, reference, from, to)
        val crossing = firstLagReaching(scores, share * scores[loudestOf(scores)])
        // From the crossing forward to that arrival's own peak. The crossing lands on the rising
        // flank, which on the profile this was built against was 1.3 ms below the peak; the span
        // is set from that measurement with margin, and short enough that it cannot reach a
        // surface more than half a metre of extra path away.
        var first = crossing
        val until = minOf(scores.size - 1, crossing + FIRST_ARRIVAL_SPAN_SAMPLES)
        for (index in crossing..until) if (scores[index] > scores[first]) first = index
        return arrivalAt(scores, from, first, edgeShares)
    }

    private fun scoresOver(recorded: ShortArray, reference: ShortArray, from: Int, to: Int): DoubleArray {
        val scores = DoubleArray(to - from + 1)
        for (offset in from..to) {
            var total = 0.0
            for (index in reference.indices) {
                total += recorded[offset + index].toDouble() * reference[index].toDouble()
            }
            scores[offset - from] = abs(total)
        }
        return scores
    }

    private fun loudestOf(scores: DoubleArray): Int {
        var bestAt = 0
        for (index in 1 until scores.size) if (scores[index] > scores[bestAt]) bestAt = index
        return bestAt
    }

    private fun arrivalAt(scores: DoubleArray, from: Int, at: Int, edgeShares: List<Double>): ChirpArrival {
        val floor = median(scores)
        return ChirpArrival(
            index = from + at,
            peak = scores[at],
            floor = floor,
            ratio = if (floor == 0.0) Double.POSITIVE_INFINITY else scores[at] / floor,
            atSearchEdge = at == 0 || at == scores.size - 1,
            edgeIndices = edgeShares.map { share -> from + firstLagReaching(scores, share * scores[at]) }
        )
    }

    /**
     * Where the arrival starts, given what counts as having started.
     *
     * Falls back to the loudest lag when nothing reaches [level], which can only happen for a
     * share above one; at or below one the peak itself always qualifies.
     */
    private fun firstLagReaching(scores: DoubleArray, level: Double): Int {
        for (index in scores.indices) if (scores[index] >= level) return index
        var bestAt = 0
        for (index in 1 until scores.size) if (scores[index] > scores[bestAt]) bestAt = index
        return bestAt
    }

    /** The upper median, sorting a copy so the caller's scores keep their order. */
    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf()
        sorted.sort()
        return sorted[sorted.size / 2]
    }
}
