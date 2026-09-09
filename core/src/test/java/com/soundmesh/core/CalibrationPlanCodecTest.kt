package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalibrationPlanCodecTest {
    private val plan = CalibrationPlan(
        caseId = "C1",
        hostId = "6cd33f5d070b332e",
        firstChirpAtHostNanos = 156_074_184_412_285L,
        staggerNanos = 500_000_000L,
        repeats = 5,
        intervalNanos = 5_000_000_000L
    )

    @Test
    fun aPlanSurvivesTheRoundTrip() {
        assertEquals(plan, CalibrationPlanCodec.decode(CalibrationPlanCodec.encode(plan)))
    }

    @Test
    fun refusesTextThatIsNotAPlan() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationPlanCodec.decode("soundmesh-alignment 2 C1 0 5")
        }
    }

    @Test
    fun refusesAVersionItCannotRead() {
        val future = CalibrationPlanCodec.encode(plan)
            .replaceFirst(" ${CalibrationPlanCodec.VERSION} ", " 99 ")

        assertThrows(IllegalArgumentException::class.java) { CalibrationPlanCodec.decode(future) }
    }

    @Test
    fun refusesANumberItCannotRead() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationPlanCodec.decode("${CalibrationPlanCodec.MAGIC} 1 C1 abc soon 500000000 5 5000000000")
        }
    }

    /**
     * The fields are split on spaces, so an id carrying one would shift every field after it and
     * decode into a plan that is wrong rather than into a plan that is refused. The host id
     * reaches this from a scanned screen and the case id from an intent extra, so neither is this
     * class's to trust.
     */
    @Test
    fun refusesAnIdWithWhitespaceRatherThanSplittingOnIt() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationPlanCodec.encode(plan.copy(caseId = "C 1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationPlanCodec.encode(plan.copy(hostId = ""))
        }
    }

    @Test
    fun roundTripsTheAskUnchanged() {
        val request = CalibrationRequest(caseId = "MEASURE", sinkId = "a1b2c3d4e5f60718")

        assertEquals(request, CalibrationPlanCodec.decodeRequest(CalibrationPlanCodec.encodeRequest(request)))
    }

    /**
     * A sink from before the ask carried a name sends a bare case id. Filling one in - "unknown",
     * or the only sink this host has ever served - files the run against a peer it was not
     * measured with, and nothing in any later result could notice.
     */
    @Test
    fun refusesAnAskThatDoesNotSayWhoIsAsking() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationPlanCodec.decodeRequest("MEASURE")
        }
    }

    @Test
    fun refusesAnAskWhoseFieldsWouldShift() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationPlanCodec.encodeRequest(CalibrationRequest("MEA SURE", "a1b2c3d4e5f60718"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationPlanCodec.encodeRequest(CalibrationRequest("MEASURE", ""))
        }
    }
}
