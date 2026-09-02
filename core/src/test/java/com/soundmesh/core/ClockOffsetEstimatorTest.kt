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
}
