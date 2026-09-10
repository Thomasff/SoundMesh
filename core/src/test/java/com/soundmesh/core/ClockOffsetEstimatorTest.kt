package com.soundmesh.core

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockOffsetEstimatorTest {
    private val second = 1_000_000_000L

    /** Builds one exchange for a host that is [offsetNanos] ahead, with the given one way delays. */
    private fun exchange(t1: Long, offsetNanos: Long, forwardNanos: Long, backNanos: Long): ClockExchange {
        val t2 = t1 + offsetNanos + forwardNanos
        val t3 = t2
        val t4 = t3 - offsetNanos + backNanos
        return ClockExchange(t1, t2, t3, t4)
    }

    @Test
    fun recoversTheOffsetExactlyWhenThePathIsSymmetric() {
        val estimator = ClockOffsetEstimator()
        repeat(16) { index ->
            estimator.record(exchange(index * 2L * second, 5 * second, 2_000_000, 2_000_000))
        }

        val estimate = estimator.estimate(30L * second)

        assertNotNull(estimate)
        assertTrue("offset was ${estimate!!.offsetNanos}", abs(estimate.offsetNanos - 5 * second) < 100_000)
        assertEquals(0.0, estimate.driftPpm, 0.5)
    }

    @Test
    fun reportsUncertaintyAsHalfTheSmallestRoundTrip() {
        val estimator = ClockOffsetEstimator()
        repeat(16) { index ->
            estimator.record(exchange(index * 2L * second, 0, 3_000_000, 3_000_000))
        }

        val estimate = estimator.estimate(30L * second)!!

        assertEquals(3_000_000L, estimate.uncertaintyNanos)
    }

    @Test
    fun recoversRelativeDriftOfThirtyPartsPerMillion() {
        val estimator = ClockOffsetEstimator()
        // The sink clock runs 30 ppm slow, so the offset grows by 30 microseconds every second.
        repeat(32) { index ->
            val localNanos = index * 2L * second
            val offset = 5 * second + localNanos * 30 / 1_000_000
            estimator.record(exchange(localNanos, offset, 2_000_000, 2_000_000))
        }

        val estimate = estimator.estimate(64L * second)!!

        assertTrue("drift was ${estimate.driftPpm}", abs(estimate.driftPpm - 30.0) < 3.0)
    }

    @Test
    fun ignoresCongestedExchangesInFavourOfTheCleanestOnes() {
        val estimator = ClockOffsetEstimator()
        repeat(32) { index ->
            val localNanos = index * 2L * second
            // Every fourth exchange is clean; the rest sat in a queue on the way out.
            val forward = if (index % 4 == 0) 2_000_000L else 60_000_000L
            estimator.record(exchange(localNanos, 5 * second, forward, 2_000_000))
        }

        val estimate = estimator.estimate(64L * second)!!

        assertTrue("offset was ${estimate.offsetNanos}", abs(estimate.offsetNanos - 5 * second) < 1_000_000)
    }

    @Test
    fun leavesAKnownBiasWhenThePathIsSystematicallyAsymmetric() {
        val estimator = ClockOffsetEstimator()
        // Four milliseconds out, none back: the midpoint estimate is two milliseconds early.
        repeat(16) { index ->
            estimator.record(exchange(index * 2L * second, 0, 4_000_000, 0))
        }

        val estimate = estimator.estimate(30L * second)!!

        assertTrue("offset was ${estimate.offsetNanos}", abs(estimate.offsetNanos - 2_000_000) < 200_000)
        assertTrue("the bias must stay inside the reported uncertainty", estimate.uncertaintyNanos >= 2_000_000)
    }

    @Test
    fun doesNotLetTheFittedSlopeGrowTheOffsetWithHowFarAheadItIsAsked() {
        // The fit is anchored at the window it was measured over, not extrapolated to the caller's
        // instant. Extrapolating multiplied the slope's own error by the whole window span: on a
        // measured run the slope scattered by 30 ppm across a 28 second lever arm, putting 0.85 ms
        // of pure noise into every offset - and that offset is what a chirp's playout instant is
        // converted through. Anchoring costs a lag of the real drift over half a window, about
        // three frames, in exchange.
        val estimator = ClockOffsetEstimator()
        repeat(16) { index ->
            val localNanos = index * 2L * second
            val offset = 5 * second + localNanos * 30 / 1_000_000
            estimator.record(exchange(localNanos, offset, 2_000_000, 2_000_000))
        }

        val near = estimator.estimate(30L * second)!!
        val far = estimator.estimate(300L * second)!!

        assertEquals("asking further ahead must not move the offset", near.offsetNanos, far.offsetNanos)
        // The drift is still measured and reported; only the extrapolation goes.
        assertTrue("drift was ${far.driftPpm}", abs(far.driftPpm - 30.0) < 3.0)
    }

    @Test
    fun anchorsTheOffsetAtTheCentreOfTheExchangesItKept() {
        // A least squares line passes through the centroid of its points, so the anchored value is
        // the mean of the kept midpoints - with a symmetric path, the true offset at the middle of
        // the window. A window this fills, so the cut keeps all eight and there is a centroid to be
        // anchored at; a fraction of a filling window would keep one and the question would not arise.
        val estimator = ClockOffsetEstimator(windowSize = 8, bestCount = 8)
        repeat(8) { index ->
            val localNanos = index * 2L * second
            val offset = 5 * second + localNanos * 30 / 1_000_000
            estimator.record(exchange(localNanos, offset, 2_000_000, 2_000_000))
        }

        val estimate = estimator.estimate(1000L * second)!!

        // The window spans 0 to 14s, so its middle is 7s in, where the offset is 5s + 7s * 30ppm.
        assertTrue("offset was ${estimate.offsetNanos}", abs(estimate.offsetNanos - (5 * second + 210_000)) < 20_000)
    }

    @Test
    fun refusesToGuessBeforeEnoughExchangesArrive() {
        val estimator = ClockOffsetEstimator()
        repeat(ClockOffsetEstimator.MIN_SAMPLES - 1) { index ->
            estimator.record(exchange(index * 2L * second, 5 * second, 2_000_000, 2_000_000))
        }

        assertNull(estimator.estimate(30L * second))
    }

    @Test
    fun forgetsExchangesThatFellOutOfTheWindow() {
        val estimator = ClockOffsetEstimator(windowSize = 8, bestCount = 4)
        repeat(8) { index -> estimator.record(exchange(index * 2L * second, 9 * second, 2_000_000, 2_000_000)) }
        repeat(8) { index -> estimator.record(exchange((8 + index) * 2L * second, 5 * second, 2_000_000, 2_000_000)) }

        val estimate = estimator.estimate(32L * second)!!

        assertTrue("stale offset survived: ${estimate.offsetNanos}", abs(estimate.offsetNanos - 5 * second) < 1_000_000)
    }

    @Test
    fun neverReportsNegativeUncertaintyEvenWithOutOfOrderTimestamps() {
        val estimator = ClockOffsetEstimator()
        // Mix out-of-order exchanges (negative roundTripNanos) with valid ones
        repeat(8) { index ->
            // Valid exchanges
            estimator.record(exchange(index * 4L * second, 5 * second, 2_000_000, 2_000_000))
        }
        repeat(8) { index ->
            // Out-of-order: t4 < t1, producing negative roundTripNanos
            val t1 = (8 + index) * 4L * second
            val t2 = t1 + 5 * second + 1_000_000
            val t3 = t2
            val t4 = t3 - 5 * second - 100_000_000  // Makes roundTripNanos negative
            estimator.record(ClockExchange(t1, t2, t3, t4))
        }

        val estimate = estimator.estimate(32L * second)

        assertNotNull(estimate)
        assertTrue("uncertainty must never be negative: ${estimate!!.uncertaintyNanos}", estimate.uncertaintyNanos >= 0)
    }

    @Test
    fun refusesToEstimateWhenAllExchangesHaveNegativeRoundTrips() {
        val estimator = ClockOffsetEstimator()
        repeat(ClockOffsetEstimator.MIN_SAMPLES) { index ->
            val t1 = index * 2L * second
            val t2 = t1 + 1_000_000
            val t3 = t2
            val t4 = t1 - 50_000_000  // Makes roundTripNanos definitely negative
            estimator.record(ClockExchange(t1, t2, t3, t4))
        }

        val estimate = estimator.estimate(30L * second)

        assertNull("should refuse to estimate with only negative round trips", estimate)
    }

    @Test
    fun handlesIdenticalT1ValuesWithoutProducingNonFiniteResults() {
        val estimator = ClockOffsetEstimator()
        val baseT1 = 10L * second
        repeat(ClockOffsetEstimator.MIN_SAMPLES) { index ->
            // All exchanges have the same t1, causing denominator to be zero
            estimator.record(exchange(baseT1, 5 * second, 2_000_000L + index * 100, 2_000_000L + index * 100))
        }

        val estimate = estimator.estimate(baseT1)

        // Should either return a valid estimate or null, never saturated values or NaN drift
        if (estimate != null) {
            assertTrue("offset must not be saturated", estimate.offsetNanos != Long.MAX_VALUE && estimate.offsetNanos != Long.MIN_VALUE)
            assertTrue("uncertainty must be non-negative", estimate.uncertaintyNanos >= 0)
            assertTrue("drift must be finite", !estimate.driftPpm.isNaN() && !estimate.driftPpm.isInfinite())
        }
    }

    @Test
    fun refusesEstimatesImplyingPhysicallyImpossibleDrift() {
        // A window these fill: the guard grades a slope, and a slope needs more than the one exchange
        // a filling window would keep.
        val estimator = ClockOffsetEstimator(windowSize = ClockOffsetEstimator.MIN_SAMPLES, bestCount = ClockOffsetEstimator.MIN_SAMPLES)
        val baseT1 = 10L * second
        repeat(ClockOffsetEstimator.MIN_SAMPLES) { index ->
            // t1 values spread over 1ms; offsets spread over 500 seconds.
            // This implies drift of 500 ppm, which exceeds the 500 ppm threshold.
            val t1 = baseT1 + index * 125_000  // 125 microseconds apart
            val offsetNanos = index * 63_000_000_000L  // 63 billion nanos apart (63 seconds)
            estimator.record(exchange(t1, offsetNanos, 2_000_000, 2_000_000))
        }

        val estimate = estimator.estimate(baseT1 + 5 * second)

        assertNull("should reject estimates implying >500 ppm drift", estimate)
    }

    /**
     * A window has to be wide enough to hold eight quiet exchanges, or the best-of cut is forced to
     * take queued ones to reach its count.
     *
     * Measured on two recorded runs: round trips here are bimodal, a quiet cluster around 6 ms and a
     * queued one around 18 ms, and the quiet cluster is about an eighth of all exchanges. A cut that
     * keeps a quarter of a 32 wide window therefore sits right on the boundary and routinely dips
     * into the queued population - and a handful of asymmetric exchanges dominates a mean of eight.
     * Replaying those runs through both, the offset a phase disagrees with its neighbours by falls
     * from 0.65 ms to 0.14 ms on the wider window, with no overlap between the intervals.
     */
    @Test
    fun keepsTheFitClearOfQueuedExchangesWhenOnlyAnEighthOfThemAreQuiet() {
        val estimator = ClockOffsetEstimator()
        val trueOffset = 3_000_000_000L
        repeat(64) { index ->
            val t1 = index * 2_000_000_000L
            // Every eighth exchange is quiet and symmetric; the rest queue on the way out, which
            // pushes their midpoint above the true offset by half the asymmetry.
            val quiet = index % 8 == 0
            val forward = if (quiet) 2_000_000L else 40_000_000L
            val back = 2_000_000L
            val t2 = t1 + forward + trueOffset
            val t3 = t2 + 100_000L
            estimator.record(ClockExchange(t1, t2, t3, t3 - trueOffset + back))
        }

        val estimate = estimator.estimate(128_000_000_000L)!!

        // The queued exchanges sit 19 ms above the truth; a fit that admitted any of them shows it.
        assertEquals(trueOffset.toDouble(), estimate.offsetNanos.toDouble(), 1_000_000.0)
    }

    @Test
    fun keepsOnlyTheQuietFractionWhileTheWindowIsStillFilling() {
        val estimator = ClockOffsetEstimator()
        val trueOffset = 3_000_000_000L
        // Eight exchanges in hand out of a window of sixty-four, one of them quiet. Keeping eight
        // of eight is no selection at all: seven queued midpoints sit 19 ms above the truth and
        // drag the mean up with them.
        repeat(8) { index ->
            val t1 = index * 250_000_000L
            val forward = if (index == 3) 2_000_000L else 40_000_000L
            val t2 = t1 + forward + trueOffset
            val t3 = t2 + 100_000L
            estimator.record(ClockExchange(t1, t2, t3, t3 - trueOffset + 2_000_000L))
        }

        val estimate = estimator.estimate(2L * second)!!

        assertEquals(trueOffset.toDouble(), estimate.offsetNanos.toDouble(), 1_000_000.0)
    }

    @Test
    fun keepsTheSameFractionOfTheWindowAsItFills() {
        val estimator = ClockOffsetEstimator()
        val kept = mutableListOf<Int>()
        repeat(64) { index ->
            estimator.record(exchange(index * 250_000_000L, 5 * second, 2_000_000, 2_000_000))
            if (index + 1 in listOf(8, 16, 32, 64)) kept += estimator.estimate(2L * second)!!.sampleCount
        }

        // An eighth of what the window holds, and exactly bestCount once it is full - which is
        // where every measurement the shipped constant was justified by was taken.
        assertEquals(listOf(1, 2, 4, 8), kept)
    }
}
