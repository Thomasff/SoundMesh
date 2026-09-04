package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationWindowTest {
    private val second = 1_000_000_000L
    private val rate = ChirpGenerator.SAMPLE_RATE

    @Test
    fun placesTheWindowWhereTheChirpPairWasScheduled() {
        // Recording opens one second before the first chirp; its partner follows half a second later.
        val window = CalibrationWindow.searchWindow(
            recordingStartedAtHostNanos = 0,
            fromHostNanos = second,
            toHostNanos = second + second / 2,
            uncertaintyFrames = 4800
        )

        assertEquals(rate - 4800, window.first)
        assertEquals(rate + rate / 2 + 4800, window.last)
    }

    @Test
    fun coversBothChirpsOfThePairWithRoomEitherSide() {
        val window = CalibrationWindow.searchWindow(0, second, second + second / 2, 4800)

        assertTrue(rate in window)
        assertTrue(rate + rate / 2 in window)
    }

    /** There are no samples before the recording opened, however early the chirp was scheduled. */
    @Test
    fun neverSearchesBeforeTheRecordingBegan() {
        val window = CalibrationWindow.searchWindow(0, second / 100, second / 50, 4800)

        assertEquals(0, window.first)
    }

    @Test
    fun growsWithTheUncertaintyItIsGiven() {
        val tight = CalibrationWindow.searchWindow(0, second, second, 4800)
        val loose = CalibrationWindow.searchWindow(0, second, second, 48000)

        assertEquals(9600 + 1, tight.last - tight.first + 1)
        assertEquals(96000 + 1, loose.last - loose.first + 1)
    }

    /**
     * The reason this class exists. The PC never knew when a recording opened, so it searched a
     * whole chirp interval - 2.88 million lags for a 60 second schedule, measured at 22 seconds.
     */
    @Test
    fun staysFarSmallerThanTheIntervalItReplaces() {
        val interval = 60 * second
        val window = CalibrationWindow.searchWindow(
            recordingStartedAtHostNanos = 0,
            fromHostNanos = second,
            toHostNanos = second + second / 2,
            uncertaintyFrames = CalibrationWindow.DEFAULT_UNCERTAINTY_FRAMES
        )
        val lags = window.last - window.first + 1

        assertTrue("$lags lags", lags < interval / second * rate / 20)
    }

    @Test
    fun refusesAnUncertaintyThatWouldSwallowTheStagger() {
        val failure = runCatching { CalibrationWindow.searchWindow(0, second, second, -1) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun ordersTheTwoInstantsItIsGivenEvenIfTheyArrivedTheOtherWayRound() {
        val forwards = CalibrationWindow.searchWindow(0, second, second + second / 2, 4800)
        val backwards = CalibrationWindow.searchWindow(0, second + second / 2, second, 4800)

        assertEquals(forwards, backwards)
    }
}
