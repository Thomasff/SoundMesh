package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlignmentResultCodecTest {
    private val SINK = "a1b2c3d4e5f60718"

    private fun readable(errorMs: Double) = AlignmentReading(
        firstIndex = 48000,
        secondIndex = 72010,
        measuredStaggerFrames = 24010,
        alignmentErrorMs = errorMs,
        propagationCorrectionMs = 0.0,
        separationMetres = 0.0,
        confidence = AlignmentConfidence.OK,
        ratios = listOf(41.5, 38.25),
        atSearchEdge = listOf(false, false)
    )

    private val unreadable = AlignmentReading(
        firstIndex = null,
        secondIndex = null,
        measuredStaggerFrames = null,
        alignmentErrorMs = null,
        propagationCorrectionMs = 0.0,
        separationMetres = 0.0,
        confidence = AlignmentConfidence.UNRELIABLE,
        ratios = listOf(3.0, null),
        atSearchEdge = listOf(true, null)
    )

    @Test
    fun roundTripsAWholeRunUnchanged() {
        val readings = listOf(readable(-0.198), unreadable, readable(2.177))

        val message = AlignmentResultCodec.decode(AlignmentResultCodec.encode("O40", SINK, -34_957L, readings))

        assertEquals("O40", message.caseId)
        assertEquals(readings, message.readings)
    }

    /**
     * A run that recorded nothing still has to say so: the host waits on this line, and a sink
     * that simply never connected is a failure, not an empty result.
     */
    @Test
    fun roundTripsARunWithNoReadings() {
        val message = AlignmentResultCodec.decode(AlignmentResultCodec.encode("O40", SINK, -34_957L, emptyList()))

        assertEquals("O40", message.caseId)
        assertEquals(emptyList<AlignmentReading>(), message.readings)
    }

    /** A correlation floor of zero leaves the ratio infinite, and that is a real reading. */
    @Test
    fun roundTripsAnInfiniteRatio() {
        val reading = readable(0.0).copy(ratios = listOf(Double.POSITIVE_INFINITY, 12.0))

        val message = AlignmentResultCodec.decode(AlignmentResultCodec.encode("O40", SINK, 0L, listOf(reading)))

        assertEquals(listOf(reading), message.readings)
    }

    @Test
    fun rejectsAnUnknownVersion() {
        val text = AlignmentResultCodec.encode("O40", SINK, 0L, listOf(readable(0.1))).replace("alignment 3 ", "alignment 4 ")

        assertThrows(IllegalArgumentException::class.java) { AlignmentResultCodec.decode(text) }
    }

    /** A truncated stream must fail loudly rather than combine against the pairs that did arrive. */
    @Test
    fun rejectsFewerReadingsThanTheHeaderPromises() {
        val text = AlignmentResultCodec.encode("O40", SINK, 0L, listOf(readable(0.1), readable(0.2)))
            .lines().dropLast(1).joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) { AlignmentResultCodec.decode(text) }
    }

    /**
     * The host waits on one socket and any handset in the room still holding a plan can reach it,
     * so a delivery that does not say who sent it cannot be checked against who was served.
     */
    @Test
    fun carriesTheNameOfTheHandsetThatSentIt() {
        val message = AlignmentResultCodec.decode(
            AlignmentResultCodec.encode("O40", SINK, 0L, listOf(readable(0.1)))
        )

        assertEquals(SINK, message.sinkId)
    }

    @Test
    fun rejectsASinkIdThatWouldBreakTheHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            AlignmentResultCodec.encode("O40", "a1b2 c3d4", 0L, listOf(readable(0.1)))
        }
    }

    @Test
    fun rejectsACaseIdThatWouldBreakTheHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            AlignmentResultCodec.encode("O40 extra", SINK, 0L, listOf(readable(0.1)))
        }
    }

    /**
     * The correction the sender was already standing on, which only the sender knows. Without it
     * the receiver has to read its own copy of the same launch flag, and computes a silently wrong
     * new correction on any run where the two roles were started differently.
     */
    @Test
    fun carriesTheCorrectionTheSenderHadAlreadyApplied() {
        val message = AlignmentResultCodec.decode(AlignmentResultCodec.encode("O40", SINK, -34_957L, listOf(readable(0.1))))

        assertEquals(-34_957L, message.appliedOffsetMicros)
    }

    @Test
    fun roundTripsACalibrationReply() {
        val reply = CalibrationReply(measuredOffsetMicros = -34_773L, clusterMeanMicros = 184L, passed = true)

        assertEquals(reply, CalibrationReplyCodec.decode(CalibrationReplyCodec.encode(reply)))
    }

    /** A run that cannot say leaves the other side on the correction it already had. */
    @Test
    fun roundTripsAReplyWithNothingToAdopt() {
        val reply = CalibrationReply(measuredOffsetMicros = null, clusterMeanMicros = null, passed = null)

        assertEquals(reply, CalibrationReplyCodec.decode(CalibrationReplyCodec.encode(reply)))
    }

    @Test
    fun rejectsAReplyOfAnUnknownVersion() {
        val text = CalibrationReplyCodec.encode(CalibrationReply(1L, 2L, true)).replace("calibration 2 ", "calibration 3 ")

        assertThrows(IllegalArgumentException::class.java) { CalibrationReplyCodec.decode(text) }
    }
}
