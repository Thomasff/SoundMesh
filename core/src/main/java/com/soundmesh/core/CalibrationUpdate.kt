package com.soundmesh.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns finished runs into the standing correction the next run should apply.
 *
 * Two steps, because two handsets own them. [measured] belongs to the handset that combines the
 * pair: it reads one run as a single observation of the offset between the two devices. [fold]
 * belongs to the handset that carries the correction: it folds that observation into the estimate
 * it already had.
 *
 * The read is cumulative. A run is measured while already correcting by some amount, so what it
 * reports is the residual left over, and the offset it observed is the applied correction plus that
 * residual. Reading the residual as the whole offset would undo the calibration on every run.
 *
 * This is the loop that used to be a person copying a number out of one run's output and typing it
 * into the next run's command line.
 */
object CalibrationUpdate {
    /**
     * The furthest the standing correction may travel.
     *
     * Derived rather than chosen: the host opens its recording one second before the sink's chirp
     * instant, so a correction beyond that moves the chirp out of the window the next run would
     * have to measure it in. A loop that has walked this far has diverged, and the run that would
     * prove it cannot be taken any more.
     */
    const val MAX_OFFSET_MICROS = 1_000_000L

    /**
     * Whether a run's cluster mean can be folded into the correction.
     *
     * Deliberately not [RunVerdict.passed]. A pair that has never been calibrated sits tens of
     * milliseconds out and fails every threshold in the verdict - and it is precisely the run whose
     * measurement the loop has to adopt, or no first correction can ever be made and the pair fails
     * forever. What disqualifies a run is not being wrong. It is not knowing how wrong it is: a
     * hole where a pair should be, or a scatter too wide for the model the cluster mean assumes.
     *
     * The cluster's own spread is reported rather than gated on. Every threshold in [AlignmentVerdict]
     * that is not derived was extrapolated from six measured runs, and inventing a seventh from
     * nothing is the error those runs exist to prevent.
     */
    fun usable(verdict: RunVerdict): Boolean =
        verdict.clusterMeanMs != null &&
            verdict.clusterCount >= 2 &&
            VerdictFailure.NO_PAIRS !in verdict.failures &&
            VerdictFailure.UNREADABLE_PAIR !in verdict.failures &&
            VerdictFailure.TOO_MANY_OUTLIERS !in verdict.failures

    /**
     * What one run observed the pair's offset to be, or null if the run cannot say.
     *
     * Null covers two situations on purpose - no verdict at all, and an untrustworthy one - because
     * the caller does the same thing in both: it keeps the estimate it already had, unchanged and
     * with its observation count unchanged. A run that cannot be read is not a measurement of zero.
     */
    fun measured(appliedOffsetMicros: Long, verdict: RunVerdict?): Long? {
        if (verdict == null || !usable(verdict)) return null
        return appliedOffsetMicros + (verdict.clusterMeanMs!! * 1000).roundToLong()
    }

    /**
     * The estimate after one more observation, or null if it would run off the schedule.
     *
     * A running mean, which is to say a gain of `1 / (observations + 1)`. The obvious update -
     * stand on whatever the latest run measured - is an integrator with a gain of one, and an
     * integrator with a gain of one applied to a noisy measurement has no restoring force: it
     * random walks. That is not a theory here. Across O41 to O44 the correction wandered 1.205 ms
     * and O44 failed the 1.0 ms gate because of where the walk had left it.
     *
     * The gain carries no chosen constant. What is being estimated is a constant - the fixed offset
     * between one pair of handsets - and the mean of every observation is what estimates a constant
     * from noisy readings; `1 / (n + 1)` is simply what a running mean's gain is. Its error falls
     * as `1 / sqrt(n)` instead of growing.
     *
     * The assumption it rests on, stated because it is the thing that would break it: the offset
     * does not drift. A mean over all history is slow to follow an offset that moves, and if that
     * turns out to happen the fix is a floor on the gain - which would be a chosen constant, and so
     * wants evidence first.
     */
    fun fold(estimateMicros: Long, observations: Int, measuredMicros: Long): Long? {
        require(observations >= 0) { "negative observation count: $observations" }
        val folded = estimateMicros +
            ((measuredMicros - estimateMicros).toDouble() / (observations + 1)).roundToLong()
        return if (abs(folded) >= MAX_OFFSET_MICROS) null else folded
    }
}
