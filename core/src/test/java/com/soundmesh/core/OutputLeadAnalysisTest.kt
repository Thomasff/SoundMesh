package com.soundmesh.core

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputLeadAnalysisTest {
    private val rate = ChirpGenerator.SAMPLE_RATE
    private val startedAt = 5_000_000_000L
    private val referenceChirpAt = startedAt + 1_000_000_000L
    private val subjectChirpAt = startedAt + 3_000_000_000L

    /** Narrower than the half second a device uses, so a test correlates in a moment. */
    private val uncertainty = 1500

    /**
     * Four seconds of quiet room with a chirp planted at each offset. Noise rather than silence:
     * against pure silence every correlation score is zero, and a zero floor reads as an infinite
     * confidence ratio, so a recording with no chirp in it would look like a good measurement.
     */
    private fun recording(offsets: List<Int>, gains: List<Double> = offsets.map { 0.6 }): ShortArray {
        val chirp = ChirpGenerator.generateMono()
        val out = ShortArray(rate * 4)
        val noise = Random(11)
        for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
        for ((at, offset) in offsets.withIndex()) {
            for (index in chirp.indices) {
                out[offset + index] = (out[offset + index] + chirp[index] * gains[at]).toInt().toShort()
            }
        }
        return out
    }

    private fun read(
        recorded: ShortArray,
        recordingStartedAtHostNanos: Long = startedAt,
        uncertaintyFrames: Int = uncertainty
    ) = OutputLeadAnalysis.read(
        recorded = recorded,
        reference = ChirpGenerator.generateMono(),
        recordingStartedAtHostNanos = recordingStartedAtHostNanos,
        referenceChirpAtHostNanos = referenceChirpAt,
        subjectChirpAtHostNanos = subjectChirpAt,
        uncertaintyFrames = uncertaintyFrames
    )

    @Test
    fun twoOutputsThatFireWhenTheyWereToldToLeadEachOtherByNothing() {
        val result = read(recording(listOf(rate, rate * 3)))

        assertEquals(AlignmentConfidence.OK, result.confidence)
        assertEquals(0L, result.leadMicros)
    }

    /** The measurement this exists for: the subject output emitting ahead of the reference one. */
    @Test
    fun anOutputThatFiresEarlyLeadsByHowEarlyItWas() {
        val early = 936 // 19.5 ms at 48 kHz
        val result = read(recording(listOf(rate, rate * 3 - early)))

        assertEquals(AlignmentConfidence.OK, result.confidence)
        assertEquals(19_500L, result.leadMicros)
    }

    @Test
    fun anOutputThatFiresLateLeadsByANegativeAmount() {
        val result = read(recording(listOf(rate, rate * 3 + 480)))

        assertEquals(-10_000L, result.leadMicros)
    }

    /**
     * The instant the recording opened is a search hint and nothing more. It is read beside
     * `startRecording()` and can be a long way out, but it enters both arrivals with the same sign,
     * so a difference cannot carry it.
     *
     * The window has to be wide enough to still hold both chirps, which is the one thing the hint
     * really does decide - and the reason a device searches half a second either side rather than
     * the tenth of a second this shifts by.
     */
    @Test
    fun theInstantTheRecordingOpenedCancelsOutOfTheDifference() {
        val shift = rate / 10 // the recording really opened 100 ms before it was noticed
        val honest = read(recording(listOf(rate, rate * 3 - 936)))
        val mistaken = read(
            recording(listOf(rate + shift, rate * 3 - 936 + shift)),
            recordingStartedAtHostNanos = startedAt,
            uncertaintyFrames = shift + uncertainty
        )

        assertEquals(19_500L, honest.leadMicros)
        assertEquals(honest.leadMicros, mistaken.leadMicros)
    }

    @Test
    fun aRoomWithNoChirpInItIsNotAMeasurement() {
        val result = read(recording(emptyList()))

        assertEquals(AlignmentConfidence.UNRELIABLE, result.confidence)
        assertNull(result.leadMicros)
    }

    /** Half a measurement is not a measurement: one output heard and the other missed says nothing. */
    @Test
    fun oneChirpHeardAndTheOtherMissedIsNotAMeasurement() {
        val chirp = ChirpGenerator.generateMono()
        val out = ShortArray(rate * 4)
        val noise = Random(13)
        for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
        for (index in chirp.indices) out[rate + index] = (out[rate + index] + chirp[index] * 0.6).toInt().toShort()

        val result = read(out)

        assertEquals(AlignmentConfidence.UNRELIABLE, result.confidence)
        assertNull(result.leadMicros)
        assertNotNull(result.referenceIndex)
    }

    @Test
    fun theQuieterOutputStillMeasuresAsLongAsItStandsOutOfTheRoom() {
        val result = read(recording(listOf(rate, rate * 3 - 936), listOf(1.0, 0.15)))

        assertEquals(AlignmentConfidence.OK, result.confidence)
        assertEquals(19_500L, result.leadMicros)
        assertTrue("the quiet chirp still has to clear the ratio", result.ratios[1]!! >= ChirpCorrelator.MIN_TRUSTWORTHY_RATIO)
    }

    private fun reading(leadMicros: Long?) = OutputLeadReading(
        referenceIndex = if (leadMicros == null) null else 0,
        subjectIndex = if (leadMicros == null) null else 0,
        leadMicros = leadMicros,
        confidence = if (leadMicros == null) AlignmentConfidence.UNRELIABLE else AlignmentConfidence.OK,
        ratios = emptyList(),
        atSearchEdge = emptyList()
    )

    @Test
    fun theAnswerIsTheMiddleRepeatRatherThanTheAverageOfThem() {
        val result = OutputLeadAnalysis.combine(
            listOf(19_100L, 19_500L, 20_400L).map(::reading),
            minimumReadings = 3,
            maximumSpreadMicros = 3_000
        )

        assertEquals(19_500L, result.leadMicros)
        assertEquals(1_300L, result.spreadMicros)
        assertNull(result.refusal)
    }

    @Test
    fun repeatsThatWereNotHeardAreLeftOutRatherThanCountedAsZero() {
        val result = OutputLeadAnalysis.combine(
            listOf(reading(19_400L), reading(null), reading(19_500L), reading(19_600L)),
            minimumReadings = 3,
            maximumSpreadMicros = 3_000
        )

        assertEquals(19_500L, result.leadMicros)
        assertEquals(3, result.usedReadings)
    }

    /** A constant nobody looks at again is worse wrong than absent, so disagreement refuses. */
    @Test
    fun repeatsThatDisagreeAreRefusedRatherThanAveraged() {
        val result = OutputLeadAnalysis.combine(
            listOf(19_400L, 19_500L, 44_000L).map(::reading),
            minimumReadings = 3,
            maximumSpreadMicros = 3_000
        )

        assertNull(result.leadMicros)
        assertNotNull(result.refusal)
    }

    @Test
    fun tooFewRepeatsHeardIsARefusalAndSaysSo() {
        val result = OutputLeadAnalysis.combine(
            listOf(reading(19_500L), reading(null), reading(null)),
            minimumReadings = 3,
            maximumSpreadMicros = 3_000
        )

        assertNull(result.leadMicros)
        assertEquals(1, result.usedReadings)
        assertTrue(result.refusal!!.contains("1 of 3"))
    }
}
