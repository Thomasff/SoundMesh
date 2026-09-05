package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationScheduleTest {
    private val plan = CalibrationPlan(
        caseId = "C1",
        hostId = "6cd33f5d070b332e",
        firstChirpAtHostNanos = 100_000_000_000L,
        staggerNanos = 500_000_000L,
        repeats = 5,
        intervalNanos = 5_000_000_000L
    )

    /** One chirp is six chunks of 20ms, which is what ChirpGenerator produces. */
    private val chirpNanos = 120_000_000L

    private fun timing(role: CalibrationRole) = CalibrationSchedule.of(plan, role, chirpNanos)

    @Test
    fun theSinkChirpsFirstAndTheHostAStaggerLater() {
        assertEquals(
            plan.firstChirpAtHostNanos,
            timing(CalibrationRole.SINK).ownChirpAtHostNanos.first()
        )
        assertEquals(
            plan.firstChirpAtHostNanos + plan.staggerNanos,
            timing(CalibrationRole.HOST).ownChirpAtHostNanos.first()
        )
    }

    @Test
    fun everyRepeatIsOneIntervalAfterTheLast() {
        val chirps = timing(CalibrationRole.SINK).ownChirpAtHostNanos

        assertEquals(5, chirps.size)
        for (index in 1 until chirps.size) {
            assertEquals(plan.intervalNanos, chirps[index] - chirps[index - 1])
        }
    }

    /**
     * The measurement is made of both sides of every pair, and each handset hears the other's
     * chirp in its own recording. A sink that closed its recording at its own last chirp would
     * miss the host's partner of that pair by the stagger, and the pair would come back
     * unreadable - which reads as a quiet room rather than as a recording stopped too early.
     */
    @Test
    fun bothSidesRecordPastTheLaterChirpOfTheLastPair() {
        val lastHostChirp =
            plan.firstChirpAtHostNanos + (plan.repeats - 1) * plan.intervalNanos + plan.staggerNanos

        for (role in CalibrationRole.entries) {
            assertTrue(
                "$role stops recording before the host's last chirp has finished",
                timing(role).recordUntilHostNanos >= lastHostChirp + chirpNanos
            )
        }
        assertEquals(
            timing(CalibrationRole.HOST).recordUntilHostNanos,
            timing(CalibrationRole.SINK).recordUntilHostNanos
        )
    }

    /**
     * The warm-up is ordinary audio and must not be inside any correlation window. The gap is what
     * keeps it out, and the search runs half a second either side of where a chirp is expected.
     */
    @Test
    fun theWarmUpEndsWellBeforeTheFirstChirp() {
        val warmUpEnd = timing(CalibrationRole.SINK).warmUpUntilHostNanos

        assertEquals(plan.firstChirpAtHostNanos - CalibrationSchedule.GAP_NANOS, warmUpEnd)
        assertTrue(
            "the warm-up ends inside the first chirp's search window",
            plan.firstChirpAtHostNanos - warmUpEnd > 1_000_000_000L
        )
    }

    /**
     * The recording opens before the first sound rather than before the first chirp: a microphone
     * that will not open has to stop the run before it makes any noise in somebody's quiet room,
     * and having tried is the only way to know.
     */
    @Test
    fun theRecordingOpensBeforeAnythingIsPlayed() {
        val timing = timing(CalibrationRole.SINK)

        assertEquals(timing.warmUpFromHostNanos, timing.recordFromHostNanos)
        assertEquals(
            CalibrationSchedule.WARM_UP_NANOS,
            timing.warmUpUntilHostNanos - timing.warmUpFromHostNanos
        )
    }

    /** A run with nothing to correlate spends a minute of somebody's quiet room on nothing. */
    @Test
    fun refusesAPlanThatCouldNotBeMeasured() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationSchedule.of(plan.copy(repeats = 0), CalibrationRole.SINK, chirpNanos)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationSchedule.of(plan.copy(intervalNanos = 0L), CalibrationRole.SINK, chirpNanos)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationSchedule.of(plan.copy(staggerNanos = 0L), CalibrationRole.SINK, chirpNanos)
        }
    }
}
