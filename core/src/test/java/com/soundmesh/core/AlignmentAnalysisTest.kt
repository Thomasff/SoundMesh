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

    private fun reading(
        alignmentErrorMs: Double,
        propagationCorrectionMs: Double = 0.0,
        rawMsByShare: List<Double> = emptyList(),
        rawLoudestMs: Double? = null
    ) = AlignmentReading(
        firstIndex = 0,
        secondIndex = stagger,
        measuredStaggerFrames = stagger,
        alignmentErrorMs = alignmentErrorMs,
        propagationCorrectionMs = propagationCorrectionMs,
        separationMetres = 0.0,
        confidence = AlignmentConfidence.OK,
        ratios = listOf(1000.0, 1000.0),
        atSearchEdge = listOf(false, false),
        rawMsByShare = rawMsByShare,
        rawLoudestMs = rawLoudestMs
    )

    private fun readEdges(recorded: ShortArray, shares: List<Double>) = AlignmentAnalysis.read(
        recorded = recorded,
        reference = ChirpGenerator.generateMono(),
        staggerFrames = stagger,
        searchRadiusFrames = 4800,
        separationMetres = 0.0,
        edgeShares = shares
    )

    /** A direct sound and a louder bounce of it, for each of the pair. The bounce is later. */
    private fun withBounces() = recording(
        listOf(9000, 9600, 9000 + stagger, 9600 + stagger),
        listOf(0.4, 1.2, 0.4, 1.2)
    )

    @Test
    fun takesTheLoudestArrivalWhenNoShareIsAskedFor() {
        val result = read(withBounces())

        assertEquals(9600, result.firstIndex)
        assertEquals(9600 + stagger, result.secondIndex)
    }

    /**
     * The reason a distance is read from the edge: every reflection travels further than the
     * straight line, so the first arrival is the direct sound whether or not it is the loudest.
     */
    @Test
    fun takesTheFirstArrivalOfEachChirpWhenAShareIsAskedFor() {
        val result = readEdges(withBounces(), listOf(0.2))

        assertEquals(9000.0, result.firstIndex!!.toDouble(), 100.0)
        assertEquals((9000 + stagger).toDouble(), result.secondIndex!!.toDouble(), 100.0)
    }

    /**
     * The window the louder chirp was found in holds both of them, so its own first arrival
     * belongs to whichever came first - not to the chirp it found. Reading the winner again in a
     * window of its own is what keeps the second chirp from being handed the first one's edge.
     */
    @Test
    fun doesNotHandTheSecondChirpTheFirstChirpsEdge() {
        val result = readEdges(withBounces(), listOf(0.2))

        assertTrue("secondIndex was ${result.secondIndex}", result.secondIndex!! > stagger)
    }

    @Test
    fun reportsWhatEachShareWouldHaveSaid() {
        val result = readEdges(withBounces(), listOf(0.2, 0.1, 0.05))

        assertEquals(3, result.rawMsByShare.size)
        for (raw in result.rawMsByShare) assertEquals(0.0, raw, 0.5)
    }

    @Test
    fun saysNothingPerShareWhenNoShareWasAskedFor() {
        assertTrue(read(withBounces()).rawMsByShare.isEmpty())
    }

    /**
     * What a run can say about itself without a second opinion.
     *
     * The threshold is the one fitted number in the measurement, and 09-11 measured it fitted to
     * more than the room: with a clear line of sight the best share is 20%, and with a body in the
     * way it slides to 5-10%. Repeatability cannot catch that - the blocked runs were *more*
     * self-consistent than the clear ones and still wrong by 1.3 m. What a run can see is whether
     * its own answer depends on the fitted number. A clean onset lands every share on the same
     * lag; an absent one lands each share on a different reflection.
     */
    @Test
    fun sizesHowFarTheAnswerMovesWhenTheThresholdMoves() {
        val host = reading(0.0, rawMsByShare = listOf(0.0, 0.0, 0.0))
        val sink = reading(5.83, rawMsByShare = listOf(5.83, 5.83 + 2.92, 5.83 - 2.92))

        val pair = AlignmentAnalysis.combineFacing(host, sink)!!

        assertEquals(1.0, pair.separationMetres, 0.02)
        assertEquals(1.0, pair.separationSpreadMetres!!, 0.02)
    }

    @Test
    fun cannotSizeItWhenNeitherSideSweptAnything() {
        val pair = AlignmentAnalysis.combineFacing(reading(0.0), reading(0.0))!!

        assertNull(pair.separationSpreadMetres)
    }

    @Test
    fun cannotSizeItWhenTheTwoSidesSweptDifferentShares() {
        val host = reading(0.0, rawMsByShare = listOf(0.0, 0.0))
        val sink = reading(5.83, rawMsByShare = listOf(5.83))

        assertNull(AlignmentAnalysis.combineFacing(host, sink)!!.separationSpreadMetres)
    }

    /**
     * One chirp with a louder bounce after it and one with none, which is what makes the two
     * reading rules disagree: read at the loudest the pair looks 600 frames closer together than
     * it is, and read at the leading edge it looks exactly as staggered as it was told to be.
     */
    private fun bounceOnOneSideOnly() = recording(
        listOf(9000, 9600, 9000 + stagger),
        listOf(0.4, 1.2, 0.6)
    )

    /**
     * The whole of route B: one recording, read twice, because the alignment and the distance
     * want opposite rules and always have.
     *
     * Alignment keeps the loudest - short range alignment is already good, the M2 gate was passed
     * with it, and every archived correction was produced by it. The distance needs the earliest,
     * because every reflection travels further than the straight line it bounced off. Until this,
     * asking for the edge moved both, so a run could answer one question or the other.
     */
    @Test
    fun `reads the alignment at the loudest and the distance at the edge out of one recording`() {
        val result = readEdges(bounceOnOneSideOnly(), AlignmentAnalysis.DISTANCE_EDGE_SHARES)

        // 600 frames of bounce on one side only, at 48 frames per millisecond.
        assertEquals(-12.5, result.rawLoudestMs!!, 0.5)
        assertEquals(0.0, result.rawMsByShare[0], 2.0)
    }

    /**
     * The guard the whole change rests on: every arm sweeps now, so every archived alignment
     * number has to survive the extra pass unchanged.
     *
     * Asking for edges makes [AlignmentAnalysis.read] read the winner again in a window of its own,
     * which is a narrower window and so a different noise floor and a different trust ratio. The
     * lag it finds cannot change - a window centred on the winner cannot find a different winner -
     * but that is an argument, and the M2 gate and every stored correction depend on it.
     */
    @Test
    fun `asking for the distance leaves the alignment number exactly where it was`() {
        val recorded = bounceOnOneSideOnly()

        val plain = read(recorded)
        val swept = readEdges(recorded, AlignmentAnalysis.DISTANCE_EDGE_SHARES)

        assertEquals(plain.alignmentErrorMs!!, swept.rawLoudestMs!!, 1e-9)
        assertEquals(plain.confidence, swept.confidence)
    }

    @Test
    fun `a reading that swept no edges has no second answer to give`() {
        assertNull(read(bounceOnOneSideOnly()).rawLoudestMs)
    }

    /** The pair, not the side: the half sum is the alignment and it must be the loudest one. */
    @Test
    fun `the pair takes its alignment from the loudest and its distance from the edge`() {
        val host = reading(0.5, rawMsByShare = listOf(0.5), rawLoudestMs = 3.0)
        val sink = reading(1.5, rawMsByShare = listOf(1.5), rawLoudestMs = 5.0)

        val combined = AlignmentAnalysis.combineFacing(host, sink)!!

        assertEquals(4.0, combined.alignmentErrorMs, 1e-9)
        assertEquals(0.5, combined.flightTimeMs, 1e-9)
        assertEquals(0.5 / 1000 * AlignmentAnalysis.SPEED_OF_SOUND_M_S, combined.separationMetres, 1e-9)
    }

    /** Every arm that does not sweep reads exactly as it always has, which is most of the archive. */
    @Test
    fun `a pair that swept no edges combines exactly as it always did`() {
        val combined = AlignmentAnalysis.combineFacing(reading(0.5), reading(1.5))!!

        assertEquals(1.0, combined.alignmentErrorMs, 1e-9)
        assertEquals(0.5, combined.flightTimeMs, 1e-9)
    }
}
