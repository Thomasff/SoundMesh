package com.soundmesh.core

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every pair in a room of three, out of one window each handset recorded for itself.
 *
 * The arithmetic is [AlignmentAnalysis.combineFacing]'s and is already covered. What is new here is
 * the bookkeeping around it - which handset's reading goes on which side - and that is the one
 * mistake in this file that produces a plausible answer rather than an obviously wrong one: the
 * two sides swapped gives a negative separation and an alignment error of the opposite sign.
 */
class RoomAlignmentTest {
    private val slot = ChirpGenerator.SAMPLE_RATE / 2
    private val base = 9000
    private val metresPerFrame = AlignmentAnalysis.SPEED_OF_SOUND_M_S / ChirpGenerator.SAMPLE_RATE

    /** Flight times in frames between each pair, which stand for the room's geometry. */
    private val flight = mapOf(
        (0 to 1) to 140,
        (0 to 2) to 420,
        (1 to 2) to 280
    )

    private fun flightFrames(a: Int, b: Int) =
        if (a == b) 0 else flight[minOf(a, b) to maxOf(a, b)]!!

    /**
     * What handset [own] records: every handset's chirp, each late by its flight across the room,
     * with its own arriving first and loudest.
     */
    private fun recordingOf(own: Int, count: Int, lateFrames: Map<Int, Int> = emptyMap()): ShortArray {
        val chirp = ChirpGenerator.generateMono()
        val out = ShortArray(ChirpGenerator.SAMPLE_RATE * 4)
        val noise = Random(7)
        for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
        for (slotIndex in 0 until count) {
            val at = base + slotIndex * slot + flightFrames(own, slotIndex) + (lateFrames[slotIndex] ?: 0)
            val gain = if (slotIndex == own) 1.4 else 0.5
            for (index in chirp.indices) {
                out[at + index] = (out[at + index] + chirp[index] * gain).toInt().toShort()
            }
        }
        return out
    }

    private fun slotsFor(own: Int, count: Int, lateFrames: Map<Int, Int> = emptyMap()) =
        AlignmentAnalysis.readSlots(
            recorded = recordingOf(own, count, lateFrames),
            reference = ChirpGenerator.generateMono(),
            ownSlot = own,
            slotCount = count,
            slotFrames = slot,
            searchRadiusFrames = 4800
        )

    private fun room(count: Int, lateFrames: Map<Int, Int> = emptyMap()) =
        AlignmentAnalysis.facingPairs(
            (0 until count).associateWith { slotsFor(it, count, lateFrames) },
            slotFrames = slot
        )

    @Test
    fun answersAllThreePairsOfARoomOfThree() {
        val pairs = room(3)

        assertEquals(setOf(0 to 1, 0 to 2, 1 to 2), pairs.keys)
        assertTrue(pairs.values.all { it != null })
    }

    /**
     * The separations come back positive and the right size, which is what says the two sides were
     * not swapped. A swap is the failure worth a test of its own: it reads as a working room with
     * every distance negated, and nothing downstream looks at the sign.
     */
    @Test
    fun measuresEachPairsSeparationWithTheSidesTheRightWayRound() {
        val pairs = room(3)

        for (entry in pairs) {
            val key = entry.key
            val measured = entry.value
            val expected = flightFrames(key.first, key.second) * metresPerFrame
            assertEquals("pair $key", expected, measured!!.separationMetres, 0.02)
        }
    }

    /**
     * A handset that emits late is late in every pair it is in, and in neither of the others. The
     * room's own arithmetic has to keep one handset's fault its own.
     */
    @Test
    fun blamesOnlyThePairsHoldingTheHandsetThatWasLate() {
        val late = 480 // 10 ms

        val pairs = room(3, lateFrames = mapOf(1 to late))

        assertEquals(10.0, pairs[0 to 1]!!.alignmentErrorMs, 1e-6)
        assertEquals(-10.0, pairs[1 to 2]!!.alignmentErrorMs, 1e-6)
        assertEquals(0.0, pairs[0 to 2]!!.alignmentErrorMs, 1e-6)
    }

    /** Half a pair says nothing, and says it about that pair only. */
    @Test
    fun saysNothingAboutAPairWhoseOtherHalfNeverReported() {
        val pairs = AlignmentAnalysis.facingPairs(
            mapOf(0 to slotsFor(0, 3), 2 to slotsFor(2, 3)),
            slotFrames = slot
        )

        assertNull(pairs[0 to 1])
        assertNull(pairs[1 to 2])
        assertEquals(420 * metresPerFrame, pairs[0 to 2]!!.separationMetres, 0.02)
    }
}
