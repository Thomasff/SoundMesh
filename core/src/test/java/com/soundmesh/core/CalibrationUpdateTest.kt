package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
    fun readsTheOffsetThroughTheCorrectionAlreadyApplied() {
        assertEquals(-34_773L, CalibrationUpdate.measured(-34_957L, verdict(0.184)))
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
        assertEquals(-34_900L, CalibrationUpdate.measured(0L, uncalibrated))
    }

    /** Being wrong is not disqualifying. Not knowing how wrong is. */
    @Test
    fun refusesARunWithAPairItCouldNotRead() {
        val holed = verdict(0.2, failures = listOf(VerdictFailure.UNREADABLE_PAIR), passed = false)

        assertFalse(CalibrationUpdate.usable(holed))
        assertNull(CalibrationUpdate.measured(-34_957L, holed))
    }

    @Test
    fun refusesARunWithMoreOutliersThanTheModelAllows() {
        val scattered = verdict(
            clusterMeanMs = 0.2,
            outliers = listOf(-2.1, -2.2, 1.9),
            failures = listOf(VerdictFailure.TOO_MANY_OUTLIERS),
            passed = false
        )

        assertNull(CalibrationUpdate.measured(-34_957L, scattered))
    }

    /** One surviving pair has no spread, so nothing says its mean is a mean rather than a draw. */
    @Test
    fun refusesAClusterOfOne() {
        assertNull(CalibrationUpdate.measured(-34_957L, verdict(0.2, clusterCount = 1)))
    }

    @Test
    fun refusesARunThatCouldNotBeCombinedAtAll() {
        assertNull(CalibrationUpdate.measured(-34_957L, null))
        assertNull(CalibrationUpdate.measured(-34_957L, verdict(null, clusterCount = 0)))
    }

    /** With no history behind it, the first measurement is the whole estimate. */
    @Test
    fun takesTheFirstMeasurementWhole() {
        assertEquals(-35_135L, CalibrationUpdate.fold(0L, 0, -35_135L))
        assertEquals(-35_135L, CalibrationUpdate.fold(-12_000L, 0, -35_135L))
    }

    /**
     * The damping. A second measurement moves the estimate half way, a fourth a quarter of the way,
     * because the estimate is the mean of every measurement rather than the latest one.
     */
    @Test
    fun movesOneOverNTowardsEachNewMeasurement() {
        assertEquals(-35_580L, CalibrationUpdate.fold(-35_135L, 1, -36_026L))
        assertEquals(-35_235L, CalibrationUpdate.fold(-35_335L, 3, -34_935L))
    }

    /**
     * The defect this replaces, on the four measurements the handsets actually produced across
     * O41 to O44. Taking each measurement whole - `new = old + residual`, an integrator with a gain
     * of one - has no restoring force, and the correction wandered 1.205 ms; O44 then failed the
     * 1.0 ms gate as a direct result. The same four measurements, averaged, span less than half of
     * that and are still tightening.
     */
    @Test
    fun dampsTheWalkThatMadeARunFail() {
        val measured = listOf(-35_135L, -36_026L, -35_884L, -34_821L)

        val estimates = ArrayList<Long>()
        var estimate = 0L
        measured.forEachIndexed { seen, value ->
            estimate = CalibrationUpdate.fold(estimate, seen, value)!!
            estimates.add(estimate)
        }

        assertEquals(listOf(-35_135L, -35_580L, -35_681L, -35_466L), estimates)
        val walked = measured.max() - measured.min()
        val averaged = estimates.max() - estimates.min()
        assertEquals(1_205L, walked)
        assertEquals(546L, averaged)
        assertTrue("averaging must not travel further than the walk it replaces", averaged < walked)
    }

    /**
     * Past a second of correction the sink's chirp leaves the window the host opens its recording
     * on, so the next run could not measure the thing that would undo this one. Clamped on the
     * estimate rather than the measurement, because the estimate is what gets applied.
     */
    @Test
    fun refusesACorrectionThatWouldRunOffTheSchedule() {
        assertNull(CalibrationUpdate.fold(-950_000L, 0, -1_050_000L))
        assertEquals(-999_000L, CalibrationUpdate.fold(-950_000L, 0, -999_000L))
    }

    /** A single wild measurement can no longer carry the estimate off the schedule on its own. */
    @Test
    fun survivesAWildMeasurementItWouldOnceHaveAdopted() {
        val folded = CalibrationUpdate.fold(-35_000L, 9, -900_000L)!!

        assertTrue(abs(folded) < CalibrationUpdate.MAX_OFFSET_MICROS)
        assertEquals(-121_500L, folded)
    }
}
