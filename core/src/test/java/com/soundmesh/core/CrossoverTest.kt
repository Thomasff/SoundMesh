package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

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
     *
     * Read at the first sample after the edge rather than the forty-eighth. The gap this looks for
     * is the click itself, which is a discontinuity at the edge; forty-eight samples in, what is
     * left of it is whatever the filter has not yet forgotten, and that is a property of the
     * skirt rather than of the state. The margin there had been 110 against a threshold of 100,
     * sized when the filter had two poles; steepening it to four made the same intact state read
     * as 66 and turned this red. At the edge the gap is the full carried level either way.
     */
    @Test
    fun aFilterCarriedOnFromTheLastChunkDisagreesWithOneStartingCold() {
        val carried = Crossover()
        settleOnDc(carried, 10_000.0, hz = 1000.0, count = 4800)

        val next = settleOnDc(carried, 0.0, hz = 1000.0, count = 1)
        val cold = settleOnDc(Crossover(), 0.0, hz = 1000.0, count = 1)

        assertEquals(0.0, cold, 0.0)
        assertNotEquals(cold, next, 5_000.0)
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

    /** What fraction of a steady tone at [toneHz] survives into the low half, crossover at [hz]. */
    private fun toneSurviving(toneHz: Double, hz: Double): Double {
        val crossover = Crossover()
        val coefficient = Crossover.coefficientFor(hz, sampleRate)
        var peak = 0.0
        repeat(sampleRate) { n ->
            val low = crossover.lowLeft(10_000.0 * sin(2.0 * Math.PI * toneHz * n / sampleRate), coefficient)
            // Second half only: the first is the filter settling, and its overshoot is not the answer.
            if (n > sampleRate / 2) peak = max(peak, abs(low))
        }
        return peak / 10_000.0
    }

    /**
     * Two octaves above the split, the low half has to be gone rather than merely quieter.
     *
     * Measured by ear on 2026-09-10, both handsets on the low half, one song, crossover dragged
     * from 100 to 5000: at every setting the listener heard the whole song, darker. At 100 the
     * melody was still followable - a kilohertz is more than three octaves up. The constant this
     * pins carried a comment claiming it was "steep enough to hear as two different sounds rather
     * than as one slightly dulled one"; the listener's own words were "the same sound, a bit
     * muffled". A frequency split nobody can hear as a split is the feature not working.
     *
     * Thirty decibels rather than more: what a listener wants back is the part their own speaker
     * could never carry anyway, and every pole costs phase. This threshold sits between what two
     * poles give (24.6 dB) and what four give (35.3 dB), so it moves only for the reason it names.
     */
    @Test
    fun twoOctavesAboveTheSplitIsGoneFromTheLowHalf() {
        val surviving = toneSurviving(toneHz = 3200.0, hz = 800.0)

        assertTrue("only $surviving down at two octaves - that is a dulled song, not a split", surviving < 0.0316)
    }

    /**
     * And the number on the slider goes on meaning what it meant.
     *
     * Cascading more poles at the same corner would have steepened the skirt by moving the split
     * itself down - dragging to 800 would sound like 515 used to. A listener spent an evening
     * calibrating their ear against these numbers, and half power at the labelled frequency is the
     * convention they were calibrating against.
     */
    @Test
    fun theLabelledFrequencyIsStillWhereHalfTheToneSurvives() {
        val surviving = toneSurviving(toneHz = 800.0, hz = 800.0)

        assertEquals(0.5, surviving, 0.02)
    }
}
