package com.soundmesh.core

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device side of the measurement that used to run on the PC.
 *
 * These plant the very chirp the devices play at offsets this test picks, so a found index can be
 * checked against a known truth rather than against whatever the PC happened to report. The
 * agreement with the PC implementation is a separate question, and it is checked against a real
 * recording rather than synthetic noise - a fixture both sides generate is a fixture both sides
 * can be wrong about in the same way.
 */
class ChirpCorrelatorTest {
    /** Room noise stands in as a fixed seed, so a failure is always the code and never the draw. */
    private fun recording(offsets: List<Int>, frames: Int = ChirpGenerator.SAMPLE_RATE * 2): ShortArray {
        val chirp = ChirpGenerator.generateMono()
        val out = ShortArray(frames)
        val noise = Random(7)
        for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
        for (offset in offsets) {
            for (index in chirp.indices) {
                out[offset + index] = (out[offset + index] + chirp[index] * 0.6).toInt().toShort()
            }
        }
        return out
    }

    @Test
    fun findsAChirpPlantedAtAKnownOffset() {
        val found = ChirpCorrelator.findArrival(recording(listOf(12345)), ChirpGenerator.generateMono(), 0, 30000)

        assertEquals(12345, found!!.index)
        assertTrue("ratio was ${found.ratio}", found.ratio > 20)
    }

    @Test
    fun findsTheChirpWhateverTheSearchWindowStartsAt() {
        val recorded = recording(listOf(12345))

        val found = ChirpCorrelator.findArrival(recorded, ChirpGenerator.generateMono(), 10000, 30000)

        assertEquals(12345, found!!.index)
    }

    @Test
    fun returnsNothingRatherThanABogusIndexWhenTheSearchRangeIsEmpty() {
        assertNull(ChirpCorrelator.findArrival(recording(listOf(1000)), ChirpGenerator.generateMono(), 5000, 4000))
    }

    @Test
    fun clipsASearchThatRunsPastTheEndOfTheRecording() {
        val recorded = recording(listOf(12345))

        val found = ChirpCorrelator.findArrival(recorded, ChirpGenerator.generateMono(), 0, Int.MAX_VALUE)

        assertEquals(12345, found!!.index)
    }

    /**
     * A chirp just outside the window still overlaps the last lag searched, and that partial
     * overlap can clear the confidence ratio on its own - reporting an arrival that is really the
     * window's edge, with every sign of a good measurement.
     */
    @Test
    fun flagsAWinnerSittingOnTheSearchBoundary() {
        val found = ChirpCorrelator.findArrival(recording(listOf(12345)), ChirpGenerator.generateMono(), 12345, 20000)

        assertEquals(12345, found!!.index)
        assertTrue(found.atSearchEdge)
    }

    @Test
    fun ratesARecordingWithNoChirpAsUntrustworthy() {
        val found = ChirpCorrelator.findArrival(recording(emptyList()), ChirpGenerator.generateMono(), 0, 30000)

        assertTrue("noise alone rated ${found!!.ratio}", found.ratio < 20)
    }
}
