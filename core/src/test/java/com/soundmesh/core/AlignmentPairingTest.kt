package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentPairingTest {
    private fun reading(errorMs: Double?) = AlignmentReading(
        firstIndex = if (errorMs == null) null else 48000,
        secondIndex = if (errorMs == null) null else 72000,
        measuredStaggerFrames = if (errorMs == null) null else 24000,
        alignmentErrorMs = errorMs,
        propagationCorrectionMs = 0.0,
        separationMetres = 0.0,
        confidence = if (errorMs == null) AlignmentConfidence.UNRELIABLE else AlignmentConfidence.OK,
        ratios = listOf(40.0, 40.0),
        atSearchEdge = listOf(false, false)
    )

    private fun message(caseId: String, errors: List<Double?>) =
        AlignmentResultMessage(caseId, errors.map(::reading))

    /**
     * The two readings of one pair straddle the flight time: the host hears its partner across the
     * room, the sink hears the mirror. -0.2 and +0.5 is an alignment error of +0.15 ms over a
     * flight time of 0.35 ms, which is 0.12 m - the separation these runs are actually taken at.
     */
    @Test
    fun combinesTheTwoSidesAndJudgesTheRun() {
        val paired = AlignmentPairing.combine(
            caseId = "O40",
            hostReadings = listOf(reading(-0.2), reading(-0.1)),
            delivered = message("O40", listOf(0.5, 0.6))
        )

        assertNull(paired.failure)
        assertEquals(0.15, paired.pairs[0]!!.alignmentErrorMs, 1e-9)
        assertEquals(0.12, paired.pairs[0]!!.separationMetres, 0.005)
        assertTrue(paired.verdict!!.passed)
        assertEquals(0.2, paired.verdict.clusterMeanMs!!, 1e-9)
    }

    /**
     * Two runs' numbers combine into something that looks exactly like a good measurement, so the
     * case id crossing the wire is checked rather than trusted.
     */
    @Test
    fun refusesReadingsFromAnotherRun() {
        val paired = AlignmentPairing.combine("O40", listOf(reading(-0.2)), message("O39", listOf(0.5)))

        assertEquals(PairingFailure.RESULT_CASE_MISMATCH, paired.failure)
        assertNull(paired.verdict)
    }

    /** The ordinary shape of a run the sink was not asked to record: it delivers, with nothing in it. */
    @Test
    fun namesARunOnlyOneHandsetRecorded() {
        val paired = AlignmentPairing.combine("O40", listOf(reading(-0.2)), message("O40", emptyList()))

        assertEquals(PairingFailure.ONE_SIDED_RUN, paired.failure)
        assertNull(paired.verdict)
    }

    @Test
    fun refusesToCombineRunsOfDifferentLengths() {
        val paired = AlignmentPairing.combine("O40", listOf(reading(-0.2), reading(-0.1)), message("O40", listOf(0.5)))

        assertEquals(PairingFailure.PAIR_COUNT_MISMATCH, paired.failure)
        assertNull(paired.verdict)
    }

    /** Half a pair says nothing, and the verdict has to report the hole rather than average around it. */
    @Test
    fun leavesAHoleWhereEitherSideCouldNotRead() {
        val paired = AlignmentPairing.combine(
            caseId = "O40",
            hostReadings = listOf(reading(-0.2), reading(null)),
            delivered = message("O40", listOf(0.5, 0.6))
        )

        assertNull(paired.failure)
        assertNull(paired.pairs[1])
        assertTrue(paired.verdict!!.failures.contains(VerdictFailure.UNREADABLE_PAIR))
    }
}
