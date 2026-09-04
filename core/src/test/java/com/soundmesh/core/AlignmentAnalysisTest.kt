package com.soundmesh.core

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentAnalysisTest {
    private val stagger = ChirpGenerator.SAMPLE_RATE / 2

    private fun recording(offsets: List<Int>, gains: List<Double> = offsets.map { 0.6 }): ShortArray {
        val chirp = ChirpGenerator.generateMono()
        val out = ShortArray(ChirpGenerator.SAMPLE_RATE * 2)
        val noise = Random(7)
        for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
        for ((at, offset) in offsets.withIndex()) {
            for (index in chirp.indices) {
                out[offset + index] = (out[offset + index] + chirp[index] * gains[at]).toInt().toShort()
            }
        }
        return out
    }

    private fun read(recorded: ShortArray, separationMetres: Double = 0.0) = AlignmentAnalysis.read(
        recorded = recorded,
        reference = ChirpGenerator.generateMono(),
        staggerFrames = stagger,
        searchRadiusFrames = 4800,
        separationMetres = separationMetres
    )

    /** Which chirp is louder depends on the room, so the strongest peak is not always the first. */
    @Test
    fun ordersTheTwoChirpsByTimeEvenWhenTheLaterOneCorrelatesMoreStrongly() {
        val result = read(recording(listOf(9000, 9000 + stagger), listOf(0.6, 1.4)))

        assertEquals(9000, result.firstIndex)
        assertEquals(9000 + stagger, result.secondIndex)
        assertEquals(0.0, result.alignmentErrorMs!!, 1e-9)
    }

    @Test
    fun measuresTheAlignmentErrorBetweenTwoStaggeredDevices() {
        // The second device fires 96 frames - 2 ms - later than it should have.
        val result = read(recording(listOf(10000, 10000 + stagger + 96)))

        assertEquals(stagger + 96, result.measuredStaggerFrames)
        assertEquals(2.0, result.alignmentErrorMs!!, 0.05)
    }

    @Test
    fun reportsAPerfectlyAlignedPairAsZeroError() {
        val result = read(recording(listOf(8000, 8000 + stagger)))

        assertEquals(AlignmentConfidence.OK, result.confidence)
        assertEquals(0.0, result.alignmentErrorMs!!, 1e-9)
    }

    /**
     * Added back, not subtracted: the sink's chirp arrives late through the air, which drags the
     * raw difference down, so a wider separation must push the reported error further positive.
     */
    @Test
    fun addsBackTheFlightTimeAcrossTheMeasuredSeparation() {
        val result = read(recording(listOf(8000, 8000 + stagger)), separationMetres = 1.0)

        assertEquals(1000.0 / AlignmentAnalysis.SPEED_OF_SOUND_M_S, result.propagationCorrectionMs, 1e-9)
        assertEquals(result.propagationCorrectionMs, result.alignmentErrorMs!!, 1e-9)
    }

    @Test
    fun reportsUnreliableWhenOnlyOneChirpIsThere() {
        val result = read(recording(listOf(8000)))

        assertEquals(AlignmentConfidence.UNRELIABLE, result.confidence)
        assertNull(result.alignmentErrorMs)
    }

    /**
     * Once the radius reaches the stagger, each chirp sits inside the other's search window and the
     * two can be told apart only by which happens to correlate louder - silently swapping first and
     * second, and negating the reported error.
     */
    @Test
    fun refusesASearchRadiusThatCouldConfuseTheTwoChirps() {
        val failure = runCatching {
            AlignmentAnalysis.read(
                recorded = recording(listOf(8000, 8000 + stagger)),
                reference = ChirpGenerator.generateMono(),
                staggerFrames = stagger,
                searchRadiusFrames = stagger,
                separationMetres = 0.0
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun refusesASeparationThatWasNeverMeasured() {
        val failure = runCatching { read(recording(listOf(8000, 8000 + stagger)), separationMetres = -1.0) }
            .exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun recoversBothTheAlignmentErrorAndTheSeparationFromTwoFacingReadings() {
        // A true error of +0.5 ms with the handsets 1.0 m apart: the air enters the two readings
        // with opposite signs, so neither side alone is either number.
        val flight = 1000.0 / AlignmentAnalysis.SPEED_OF_SOUND_M_S
        val combined = AlignmentAnalysis.combineFacing(reading(0.5 - flight), reading(0.5 + flight))

        assertNotNull(combined)
        assertEquals(0.5, combined!!.alignmentErrorMs, 1e-9)
        assertEquals(1.0, combined.separationMetres, 1e-9)
        assertEquals(flight, combined.flightTimeMs, 1e-9)
    }

    @Test
    fun saysNothingWhenOnlyOneSideOfThePairWasTrustworthy() {
        assertNull(AlignmentAnalysis.combineFacing(reading(0.5), null))
        assertNull(AlignmentAnalysis.combineFacing(null, reading(0.5)))
    }

    /** A side read with a distance still combines correctly, because the correction is taken back out. */
    @Test
    fun combinesSidesThatWereAlreadyCorrectedForADistance() {
        val flight = 1000.0 / AlignmentAnalysis.SPEED_OF_SOUND_M_S
        val host = reading(0.5 - flight + flight, propagationCorrectionMs = flight)
        val combined = AlignmentAnalysis.combineFacing(host, reading(0.5 + flight))

        assertEquals(0.5, combined!!.alignmentErrorMs, 1e-9)
    }

    private fun reading(alignmentErrorMs: Double, propagationCorrectionMs: Double = 0.0) = AlignmentReading(
        firstIndex = 0,
        secondIndex = stagger,
        measuredStaggerFrames = stagger,
        alignmentErrorMs = alignmentErrorMs,
        propagationCorrectionMs = propagationCorrectionMs,
        separationMetres = 0.0,
        confidence = AlignmentConfidence.OK,
        ratios = listOf(1000.0, 1000.0),
        atSearchEdge = listOf(false, false)
    )
}
