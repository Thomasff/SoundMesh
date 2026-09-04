package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationUpdateTest {
    private fun verdict(
        clusterMeanMs: Double?,
        clusterCount: Int = 6,
        outliers: List<Double> = emptyList(),
        passed: Boolean = true,
        failures: List<VerdictFailure> = emptyList()
    ) = RunVerdict(
        clusterMeanMs = clusterMeanMs,
        clusterSdMs = 0.25,
        clusterCount = clusterCount,
        outliers = outliers,
        maxAbsMs = 0.5,
        passed = passed,
        failures = failures
    )

    /** O40 measured +0.184 ms of residual on top of the -34.957 ms it was already correcting by. */
    @Test
    fun foldsTheResidualIntoTheCorrectionAlreadyApplied() {
        assertEquals(-34_773L, CalibrationUpdate.next(-34_957L, verdict(0.184)))
    }

    /**
     * The run the loop exists for. A pair that has never been calibrated is tens of milliseconds
     * out and fails every threshold in the verdict - and adopting only passing runs would leave it
     * failing forever, because nothing else can ever produce the first correction.
     */
    @Test
    fun adoptsAMeasurementThatIsFarOutOfSpec() {
        val uncalibrated = verdict(
            clusterMeanMs = -34.9,
            passed = false,
            failures = listOf(VerdictFailure.CLUSTER_MEAN_TOO_LARGE, VerdictFailure.SINGLE_ERROR_TOO_LARGE)
        )

        assertTrue(CalibrationUpdate.usable(uncalibrated))
        assertEquals(-34_900L, CalibrationUpdate.next(0L, uncalibrated))
    }

    /** Being wrong is not disqualifying. Not knowing how wrong is. */
    @Test
    fun refusesARunWithAPairItCouldNotRead() {
        val holed = verdict(0.2, failures = listOf(VerdictFailure.UNREADABLE_PAIR), passed = false)

        assertFalse(CalibrationUpdate.usable(holed))
        assertNull(CalibrationUpdate.next(-34_957L, holed))
    }

    @Test
    fun refusesARunWithMoreOutliersThanTheModelAllows() {
        val scattered = verdict(
            clusterMeanMs = 0.2,
            outliers = listOf(-2.1, -2.2, 1.9),
            failures = listOf(VerdictFailure.TOO_MANY_OUTLIERS),
            passed = false
        )

        assertNull(CalibrationUpdate.next(-34_957L, scattered))
    }

    /** One surviving pair has no spread, so nothing says its mean is a mean rather than a draw. */
    @Test
    fun refusesAClusterOfOne() {
        assertNull(CalibrationUpdate.next(-34_957L, verdict(0.2, clusterCount = 1)))
    }

    @Test
    fun refusesARunThatCouldNotBeCombinedAtAll() {
        assertNull(CalibrationUpdate.next(-34_957L, null))
        assertNull(CalibrationUpdate.next(-34_957L, verdict(null, clusterCount = 0)))
    }

    /**
     * Past a second of correction the sink's chirp leaves the window the host opens its recording
     * on, so the next run could not measure the thing that would undo this one.
     */
    @Test
    fun refusesACorrectionThatWouldRunOffTheSchedule() {
        assertNull(CalibrationUpdate.next(-950_000L, verdict(-100.0)))
        assertEquals(-999_000L, CalibrationUpdate.next(-950_000L, verdict(-49.0)))
    }
}
