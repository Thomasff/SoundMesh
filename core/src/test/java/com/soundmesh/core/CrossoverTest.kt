package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CrossoverTest {
    private val sampleRate = 48000

    /** Runs [count] samples of a steady level through the left channel and returns the last answer. */
    private fun settleOnDc(crossover: Crossover, level: Double, hz: Double, count: Int): Double {
        val coefficient = Crossover.coefficientFor(hz, sampleRate)
        var low = 0.0
        repeat(count) { low = crossover.lowLeft(level, coefficient) }
        return low
    }

    /** The fastest thing 16-bit audio can carry: full scale flipping sign every sample. */
    private fun settleOnNyquist(crossover: Crossover, level: Double, hz: Double, count: Int): Double {
        val coefficient = Crossover.coefficientFor(hz, sampleRate)
        var low = 0.0
        repeat(count) { low = crossover.lowLeft(if (it % 2 == 0) level else -level, coefficient) }
        return low
    }

    @Test
    fun aLevelThatNeverMovesEndsUpEntirelyInTheLowHalf() {
        val low = settleOnDc(Crossover(), 10_000.0, hz = 1000.0, count = 4800)

        assertTrue("a steady level is as low as sound gets: $low", abs(low - 10_000.0) < 1.0)
    }

    @Test
    fun theFastestWaveThereIsEndsUpEntirelyInTheHighHalf() {
        val low = settleOnNyquist(Crossover(), 10_000.0, hz = 1000.0, count = 4800)

        assertTrue("nothing that fast belongs in the low half: $low", abs(low) < 200.0)
    }

    @Test
    fun aCrossoverSetHigherLetsMoreOfTheSameWaveThrough() {
        val low = abs(settleOnNyquist(Crossover(), 10_000.0, hz = 1000.0, count = 4800))
        val higher = abs(settleOnNyquist(Crossover(), 10_000.0, hz = 10_000.0, count = 4800))

        assertTrue("$higher should let through more than $low", higher > low * 10.0)
    }

    /**
     * The reason this is a class and not a function.
     *
     * A filter answers with what it has already heard, so the same samples fed to a filter that
     * carried on from the previous chunk and to one starting cold get different answers. If that
     * ever stops being true the state has been lost somewhere, and the way that reaches a listener
     * is a click at every chunk edge - fifty times a second - rather than as a failing build.
     */
    @Test
    fun aFilterCarriedOnFromTheLastChunkDisagreesWithOneStartingCold() {
        val carried = Crossover()
        settleOnDc(carried, 10_000.0, hz = 1000.0, count = 4800)

        val next = settleOnDc(carried, 0.0, hz = 1000.0, count = 48)
        val cold = settleOnDc(Crossover(), 0.0, hz = 1000.0, count = 48)

        assertEquals(0.0, cold, 0.0)
        assertNotEquals(cold, next, 100.0)
    }

    @Test
    fun theTwoChannelsDoNotHearEachOther() {
        val crossover = Crossover()
        val coefficient = Crossover.coefficientFor(1000.0, sampleRate)
        repeat(4800) { crossover.lowLeft(10_000.0, coefficient) }

        assertEquals(0.0, crossover.lowRight(0.0, coefficient), 0.0)
    }

    @Test
    fun aCrossoverAboveWhatTheRateCanCarryIsRefused() {
        val coefficient = Crossover.coefficientFor(40_000.0, sampleRate)

        assertTrue("a coefficient stays a fraction: $coefficient", coefficient <= 1.0)
    }
}
