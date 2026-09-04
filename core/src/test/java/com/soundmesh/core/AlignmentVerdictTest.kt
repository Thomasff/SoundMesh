package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentVerdictTest {
    private fun pair(errorMs: Double) = FacingPair(
        alignmentErrorMs = errorMs,
        separationMetres = 0.12,
        flightTimeMs = 0.35,
        rawHostMs = errorMs - 0.35,
        rawSinkMs = errorMs + 0.35
    )

    private fun judge(errors: List<Double?>) =
        AlignmentVerdict.judge(errors.map { it?.let(::pair) })

    /** The six values O38 actually measured, outlier and all. */
    private val measuredRun = listOf(0.240, 0.323, 0.146, 0.302, -1.063, 0.083)

    @Test
    fun acceptsARunThatLooksLikeTheOnesAlreadyMeasured() {
        val verdict = judge(measuredRun)

        assertTrue(verdict.failures.toString(), verdict.passed)
        assertEquals(5, verdict.clusterCount)
        assertEquals(listOf(-1.063), verdict.outliers)
    }

    /**
     * The whole point of reporting the cluster mean: the round mean of this run is +0.005 ms, which
     * reads as near perfect alignment and is really two large numbers cancelling.
     */
    @Test
    fun reportsTheClusterMeanRatherThanTheRoundMean() {
        val verdict = judge(measuredRun)

        assertEquals(0.219, verdict.clusterMeanMs!!, 0.001)
        assertEquals(1.063, verdict.maxAbsMs!!, 0.001)
    }

    @Test
    fun refusesARunWhereAPairCouldNotBeRead() {
        val verdict = judge(listOf(0.24, 0.32, null, 0.30, 0.08, 0.15))

        assertFalse(verdict.passed)
        assertTrue(verdict.failures.contains(VerdictFailure.UNREADABLE_PAIR))
    }

    @Test
    fun refusesARunWhoseClusterSitsOffCentre() {
        val verdict = judge(listOf(1.5, 1.55, 1.48, 1.52, 1.49, 1.53))

        assertFalse(verdict.passed)
        assertTrue(verdict.failures.contains(VerdictFailure.CLUSTER_MEAN_TOO_LARGE))
    }

    @Test
    fun refusesARunWithOnePairFarOutEvenWhenTheClusterIsCentred() {
        val verdict = judge(listOf(0.24, 0.32, 0.15, 0.30, 4.0, 0.08))

        assertFalse(verdict.passed)
        assertTrue(verdict.failures.contains(VerdictFailure.SINGLE_ERROR_TOO_LARGE))
        // The cluster itself is fine - the run fails on the single pair, which is the number a
        // listener would actually hear.
        assertTrue(verdict.clusterMeanMs!! < AlignmentVerdict.MAX_CLUSTER_MEAN_MS)
    }

    @Test
    fun refusesARunWhereTooManyPairsJump() {
        val verdict = judge(listOf(0.10, 0.11, 0.12, 0.13, 0.15, 0.18, 0.20, 2.5, 2.6, 2.7))

        assertFalse(verdict.passed)
        assertTrue(verdict.failures.contains(VerdictFailure.TOO_MANY_OUTLIERS))
    }

    /** Identical readings leave the deviation spread at zero, which must not divide by it. */
    @Test
    fun findsNoOutliersWhenEveryPairAgreesExactly() {
        val verdict = judge(listOf(0.2, 0.2, 0.2, 0.2, 0.2, 0.2))

        assertTrue(verdict.passed)
        assertEquals(emptyList<Double>(), verdict.outliers)
        assertEquals(0.2, verdict.clusterMeanMs!!, 1e-9)
    }

    @Test
    fun saysNothingAboutARunWithNoPairsAtAll() {
        val verdict = AlignmentVerdict.judge(emptyList())

        assertFalse(verdict.passed)
        assertTrue(verdict.failures.contains(VerdictFailure.NO_PAIRS))
    }
}
