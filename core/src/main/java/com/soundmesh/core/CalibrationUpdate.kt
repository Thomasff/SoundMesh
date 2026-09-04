package com.soundmesh.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns a finished run's verdict into the standing correction the next run should apply.
 *
 * The correction is cumulative. A run is measured while already correcting by some amount, so what
 * it reports is the residual left over, and the new correction is the old one plus that residual.
 * Reading the residual as the whole correction would undo the calibration on every run.
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
     * The correction the next run should apply, or null if this run cannot say.
     *
     * Null covers three different situations on purpose - no verdict at all, an untrustworthy one,
     * and a result that has run off the schedule - because the caller does the same thing in all
     * three: it keeps the correction it already had. What went wrong is named where it happened.
     */
    fun next(appliedOffsetMicros: Long, verdict: RunVerdict?): Long? {
        if (verdict == null || !usable(verdict)) return null
        val updated = appliedOffsetMicros + (verdict.clusterMeanMs!! * 1000).roundToLong()
        return if (abs(updated) >= MAX_OFFSET_MICROS) null else updated
    }
}
