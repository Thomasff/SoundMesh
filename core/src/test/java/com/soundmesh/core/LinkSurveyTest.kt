package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSurveyTest {
    /** One exchange with the given round trip and a symmetric path. */
    private fun exchange(t1: Long, roundTripNanos: Long): ClockExchange {
        val middle = t1 + roundTripNanos / 2
        return ClockExchange(t1, middle, middle, t1 + roundTripNanos)
    }

    private fun link(vararg roundTripMillis: Double) = LinkSurvey.of(
        roundTripMillis.mapIndexed { index, millis ->
            exchange(index * 250_000_000L, (millis * 1_000_000).toLong())
        }
    )

    @Test
    fun theMedianIsTheMiddleRoundTripRatherThanTheMean() {
        // One 200 ms straggler must not move a link whose eight others are 8 ms.
        val survey = link(8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 200.0)!!

        assertEquals(8_000_000L, survey.medianRoundTripNanos)
        assertTrue(survey.usable)
    }

    /**
     * The numbers are the ones measured on 2026-09-08, and they are the whole basis of the
     * threshold: four archived runs on somebody else's router sat at 7.0 to 8.0 ms and worked, one
     * hotspot run at 17.8 ms passed at 0.165 ms residual, and three runs on the router in that room
     * sat at 56 to 70 ms and failed at 2.5 to 3.2 ms. Nothing has been measured in between.
     */
    @Test
    fun aLinkLikeTheOnesThatWorkedIsUsableAndOneLikeTheRunsThatFailedIsNot() {
        assertTrue("the archive's router", link(*DoubleArray(9) { 7.7 })!!.usable)
        assertTrue("the hotspot that passed", link(*DoubleArray(9) { 17.8 })!!.usable)

        assertFalse("the router that failed", link(*DoubleArray(9) { 56.3 })!!.usable)
        assertFalse(link(*DoubleArray(9) { 69.6 })!!.usable)
    }

    /**
     * A negative round trip is a clock that moved under the measurement, and the estimator already
     * refuses to fit one. A survey that counted them would rate a broken link as a fast one.
     */
    @Test
    fun exchangesTheEstimatorWouldNotFitAreNotCountedAsFastOnes() {
        val mixed = listOf(
            // roundTripNanos = (t4 - t1) - (t3 - t2) = 500 - 800, which is the shape the
            // estimator drops: a clock that moved under the measurement.
            ClockExchange(0, 100, 900, 500),
            exchange(1_000_000_000L, 60_000_000L),
            exchange(2_000_000_000L, 60_000_000L),
            exchange(3_000_000_000L, 60_000_000L),
            exchange(4_000_000_000L, 60_000_000L),
            exchange(5_000_000_000L, 60_000_000L),
            exchange(6_000_000_000L, 60_000_000L),
            exchange(7_000_000_000L, 60_000_000L),
            exchange(8_000_000_000L, 60_000_000L)
        )

        val survey = LinkSurvey.of(mixed)!!

        assertEquals(8, survey.samples)
        assertFalse(survey.usable)
    }

    /** Too few exchanges is not a good link and not a bad one; it is no answer. */
    @Test
    fun aLinkNobodyHasMeasuredYetIsNotJudged() {
        assertNull(link(8.0, 8.0, 8.0))
    }
}
