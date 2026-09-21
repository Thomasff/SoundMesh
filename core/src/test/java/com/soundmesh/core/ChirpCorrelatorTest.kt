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
    private fun recording(
        offsets: List<Int>,
        frames: Int = ChirpGenerator.SAMPLE_RATE * 2,
        gains: List<Double> = offsets.map { 0.6 }
    ): ShortArray {
        val chirp = ChirpGenerator.generateMono()
        val out = ShortArray(frames)
        val noise = Random(7)
        for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
        for ((at, offset) in offsets.withIndex()) {
            for (index in chirp.indices) {
                out[offset + index] = (out[offset + index] + chirp[index] * gains[at]).toInt().toShort()
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

    /**
     * The shape that once put an answer three and a half metres late.
     *
     * A confidence ratio cannot tell these apart: both arrivals are the same real chirp, and the
     * louder one rates better. Only the order of the two carries the answer.
     */
    @Test
    fun findsTheFirstArrivalEvenWhenSomethingLaterIsLouder() {
        val direct = ChirpGenerator.SAMPLE_RATE / 4
        val reflection = direct + ChirpGenerator.SAMPLE_RATE / 100
        val recorded = recording(listOf(direct, reflection), gains = listOf(0.4, 0.9))
        val chirp = ChirpGenerator.generateMono()

        assertEquals(reflection, ChirpCorrelator.findArrival(recorded, chirp, 0, recorded.size)!!.index)
        assertEquals(direct, ChirpCorrelator.findFirstArrival(recorded, chirp, 0, recorded.size)!!.index)
    }

    /** With one arrival in the window the two have to agree, or every clean run changes answer. */
    @Test
    fun agreesWithTheLoudestLagWhenThereIsOnlyOneArrival() {
        val recorded = recording(listOf(12345))
        val chirp = ChirpGenerator.generateMono()

        assertEquals(
            ChirpCorrelator.findArrival(recorded, chirp, 0, 30000)!!.index,
            ChirpCorrelator.findFirstArrival(recorded, chirp, 0, 30000)!!.index
        )
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

    /**
     * The direct sound is the earliest path by definition - every reflection travels further -
     * but nothing makes it the loudest. A handset speaker and mic both point somewhere other than
     * at the peer, so a bounce can catch a stronger lobe than the straight line does. Measured
     * 09-11: with a clear line of sight at two metres the strongest arrival landed 12-21 ms after
     * the first one, which is why picking the loudest reads metres too far.
     */
    @Test
    fun picksTheFirstArrivalRatherThanTheLoudestWhenGivenAShare() {
        val recorded = recording(listOf(12345, 13345), gains = listOf(0.4, 1.2))

        val found = ChirpCorrelator.findArrival(
            recorded, ChirpGenerator.generateMono(), 0, 30000, edgeShares = listOf(0.2)
        )!!

        assertEquals(13345, found.index)
        assertEquals(12345.0, found.edgeIndices[0].toDouble(), 100.0)
    }

    /** The share is what counts as an arrival, so raising it past the weaker one skips it. */
    @Test
    fun aShareAboveTheWeakerArrivalPicksTheLoudOneAfterAll() {
        val recorded = recording(listOf(12345, 13345), gains = listOf(0.4, 1.2))

        val found = ChirpCorrelator.findArrival(
            recorded, ChirpGenerator.generateMono(), 0, 30000, edgeShares = listOf(0.5)
        )!!

        assertEquals(13345.0, found.edgeIndices[0].toDouble(), 100.0)
    }

    /** Every share is answered from the one pass over the recording, not one pass each. */
    @Test
    fun answersEveryShareFromASingleCorrelation() {
        val found = ChirpCorrelator.findArrival(
            recording(listOf(12345)), ChirpGenerator.generateMono(), 0, 30000,
            edgeShares = listOf(0.3, 0.2, 0.1)
        )!!

        assertEquals(3, found.edgeIndices.size)
    }

    @Test
    fun asksForNoEdgesByDefault() {
        val found = ChirpCorrelator.findArrival(recording(listOf(12345)), ChirpGenerator.generateMono(), 0, 30000)!!

        assertTrue(found.edgeIndices.isEmpty())
    }
}
