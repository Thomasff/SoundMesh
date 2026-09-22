package com.soundmesh.core

import kotlin.math.abs

enum class VerdictFailure {
    NO_PAIRS,
    UNREADABLE_PAIR,
    CLUSTER_MEAN_TOO_LARGE,
    SINGLE_ERROR_TOO_LARGE,
    TOO_MANY_OUTLIERS
}

/**
 * What one calibration run says about a pair of handsets.
 *
 * Two numbers describe a run, not one. [clusterMeanMs] is the systematic offset - a calibration
 * constant that is wrong shows up here - and [maxAbsMs] is the worst single moment, which is what
 * a listener would actually hear. Reporting only the round mean hides both: the run this criterion
 * was built from averages to +0.005 ms, which reads as near perfect alignment and is really a
 * +0.219 ms cluster cancelling against a -1.063 ms jump.
 */
data class RunVerdict(
    val clusterMeanMs: Double?,
    val clusterSdMs: Double?,
    val clusterCount: Int,
    val outliers: List<Double>,
    val maxAbsMs: Double?,
    val passed: Boolean,
    val failures: List<VerdictFailure>
)

/**
 * Judges a calibration run the way the measured runs say it should be judged.
 *
 * The structure being separated here is real rather than an artefact of discarding the most extreme
 * of six points: against six independent normal draws the same rule flags a round 15.3% of the
 * time, and it flagged five of six measured rounds, which is a probability of 4.4e-4. The evidence
 * is that flagging rate, not the drop in spread - the drop on its own sits inside the null.
 *
 * The outliers are not measurement failures to be discarded. Each handset records the same chirp
 * pair, and combining the two recordings yields the alignment error and the flight time across the
 * room as independent quantities. A correlator that picked the wrong peak on one side would throw
 * the flight time off with it; in four of the five flagged rounds the geometry came back entirely
 * normal. The jumps are the system genuinely moving, and they are the term that matters to a
 * listener - so they are reported, not swept out.
 *
 * Measured on device rather than reasoned about.
 */
object AlignmentVerdict {
    /** Iglewicz-Hoaglin. Any threshold from 2.5 to 3.5 gives the same answer on the measured runs. */
    const val MODIFIED_Z_THRESHOLD = 3.5

    /**
     * A systematic offset this wide means the alignment constant is wrong, not that the room moved.
     *
     * This and [MAX_SINGLE_MS] are engineering values extrapolated from measurement - the worst
     * single pair yet seen is 1.906 ms and the widest cluster mean 0.317 ms - and not derived from
     * the data. They should be looked at again on an unfamiliar handset.
     */
    const val MAX_CLUSTER_MEAN_MS = 1.0

    /** Leaves 40% of the 5 ms gate as headroom for handsets nobody has measured yet. */
    const val MAX_SINGLE_MS = 3.0

    /** The measured runs hold at most one. Two is already unlike anything seen. */
    const val MAX_OUTLIERS = 2

    /**
     * Judges one run. A null entry is a chirp pair that could not be read at all - either side
     * being untrustworthy leaves no combined reading - and a run is not judged around a hole.
     */
    fun judge(pairs: List<FacingPair?>): RunVerdict {
        val failures = mutableListOf<VerdictFailure>()
        if (pairs.isEmpty()) failures.add(VerdictFailure.NO_PAIRS)
        if (pairs.any { it == null }) failures.add(VerdictFailure.UNREADABLE_PAIR)
        val errors = pairs.filterNotNull().map { it.alignmentErrorMs }
        if (errors.isEmpty()) {
            return RunVerdict(null, null, 0, emptyList(), null, false, failures.distinct())
        }

        val centre = median(errors)
        val spread = median(errors.map { abs(it - centre) })
        // Identical readings leave the spread at zero, and dividing by it would call every pair an
        // outlier or none depending on which way the NaN fell. Nothing is an outlier of itself.
        val outlying = errors.map { spread > 0 && abs(0.6745 * (it - centre) / spread) > MODIFIED_Z_THRESHOLD }
        val cluster = errors.filterIndexed { index, _ -> !outlying[index] }
        val outliers = errors.filterIndexed { index, _ -> outlying[index] }

        // A centre of the cluster, and deliberately not the floor of it.
        //
        // O17 argued for the floor: the emission steps it measured sit on a ladder 52 frames apart,
        // the ladder is one-sided, so level 0 is the placement the schedule asked for and the
        // lowest readings estimate it. That argument needs playback to sit on level 0, and O19
        // measured where it sits - on neither level. Against the handset's own schedule, its chirps
        // land 30 +- 11 frames (0.63 ms) later than the streamed audio the constant is spent on,
        // in all six rounds, and where the streamed audio falls between the two levels moves from
        // round to round.
        //
        // The floor is also the less stable of the two there: over six rounds it scattered 36
        // frames against the mean's 14, because whether a round happens to draw one of the rare low
        // emissions is itself a coin toss - one round drew none at all and its floor was its mean.
        //
        // What O19 cannot settle is whether any of this survives into a pair, because a pair
        // constant is a difference between two handsets and every term common to both cancels in
        // it. That needs two handsets measured the same way; until then this stays a centre.
        val clusterMean = if (cluster.isEmpty()) null else cluster.average()
        if (clusterMean != null && abs(clusterMean) >= MAX_CLUSTER_MEAN_MS) {
            failures.add(VerdictFailure.CLUSTER_MEAN_TOO_LARGE)
        }
        val maxAbs = errors.maxOf { abs(it) }
        if (maxAbs >= MAX_SINGLE_MS) failures.add(VerdictFailure.SINGLE_ERROR_TOO_LARGE)
        if (outliers.size > MAX_OUTLIERS) failures.add(VerdictFailure.TOO_MANY_OUTLIERS)

        return RunVerdict(
            clusterMeanMs = clusterMean,
            clusterSdMs = if (cluster.size < 2) null else standardDeviation(cluster),
            clusterCount = cluster.size,
            outliers = outliers,
            maxAbsMs = maxAbs,
            passed = failures.isEmpty(),
            failures = failures.distinct()
        )
    }

    /**
     * The midpoint, averaging the middle two of an even count.
     *
     * Deliberately not the upper median [ChirpCorrelator] takes: that one exists to reproduce the
     * PC analysis exactly, so that every alignment number ever recorded stays comparable. This one
     * answers a question the PC never asked and is free to be the ordinary definition.
     */
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun standardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }
}
