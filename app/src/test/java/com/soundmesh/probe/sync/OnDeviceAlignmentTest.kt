package com.soundmesh.probe.sync

import com.soundmesh.core.AlignmentConfidence
import com.soundmesh.core.ChirpGenerator
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceAlignmentTest {
    private val second = 1_000_000_000L
    private val rate = ChirpGenerator.SAMPLE_RATE
    private val staggerNanos = second / 2
    private val intervalNanos = 2 * second

    /**
     * A recording of a whole run: every pair where the schedule says it should be, plus the lead
     * the recorder opens with. [lateFrames] moves the second of each pair, which is the alignment
     * error the analysis is meant to recover.
     */
    private fun recordingOf(repeats: Int, leadNanos: Long, lateFrames: Int = 0): ShortArray {
        val chirp = ChirpGenerator.generateMono()
        val frames = ((leadNanos + repeats * intervalNanos + 2 * second) * rate / second).toInt()
        val out = ShortArray(frames)
        val noise = Random(11)
        for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
        val leadFrames = (leadNanos * rate / second).toInt()
        val staggerFrames = (staggerNanos * rate / second).toInt()
        val gapFrames = (intervalNanos * rate / second).toInt()
        for (pair in 0 until repeats) {
            val first = leadFrames + pair * gapFrames
            for (at in listOf(first, first + staggerFrames + lateFrames)) {
                for (index in chirp.indices) {
                    out[at + index] = (out[at + index] + chirp[index] * 0.6).toInt().toShort()
                }
            }
        }
        return out
    }

    private fun read(recorded: ShortArray, repeats: Int, leadNanos: Long) = OnDeviceAlignment.readRun(
        recorded = recorded,
        reference = ChirpGenerator.generateMono(),
        recordingStartedAtHostNanos = 0,
        firstChirpAtHostNanos = leadNanos,
        staggerNanos = staggerNanos,
        chirpRepeats = repeats,
        chirpIntervalNanos = intervalNanos,
        uncertaintyFrames = 4800
    )

    @Test
    fun readsEveryPairTheScheduleSaysIsThere() {
        val readings = read(recordingOf(3, second), 3, second)

        assertEquals(3, readings.size)
        assertTrue(readings.toString(), readings.all { it.confidence == AlignmentConfidence.OK })
    }

    @Test
    fun recoversTheErrorItWasGiven() {
        // 96 frames is 2 ms at 48 kHz.
        val readings = read(recordingOf(2, second, lateFrames = 96), 2, second)

        for (reading in readings) assertEquals(2.0, reading.alignmentErrorMs!!, 0.05)
    }

    /**
     * Readings destined for the two-sided combination must carry no distance correction: the
     * single-sided one assumes the host's geometry and would be applied with the wrong sign to the
     * sink's recording.
     */
    @Test
    fun leavesTheFlightTimeInSoTheTwoSidesCanCancelIt() {
        val readings = read(recordingOf(1, second), 1, second)

        assertEquals(0.0, readings[0].propagationCorrectionMs, 1e-9)
        assertEquals(0.0, readings[0].separationMetres, 1e-9)
    }

    /** A recording that opened late holds no first pair, and must say so rather than invent one. */
    @Test
    fun refusesToTrustAPairThatIsNotInTheRecording() {
        val readings = read(recordingOf(2, second), 3, second)

        assertEquals(3, readings.size)
        assertEquals(AlignmentConfidence.UNRELIABLE, readings[2].confidence)
    }

    @Test
    fun readsASingleUnrepeatedPair() {
        val readings = read(recordingOf(1, second), 1, second)

        assertEquals(1, readings.size)
        assertEquals(AlignmentConfidence.OK, readings[0].confidence)
    }
}
