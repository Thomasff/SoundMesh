package com.soundmesh.core

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading one window that holds a chirp from every handset in the room, rather than from two.
 *
 * The pair reading this generalises is not replaced, and the last test here is why: every
 * alignment number this project has ever produced came from that path, and a reading that answered
 * a slightly different question would end the comparability of all of them without saying so.
 */
class AlignmentSlotsTest {
    private val slot = ChirpGenerator.SAMPLE_RATE / 2
    private val radius = 4800

    /** Chirps at the given frames, the loudest standing for this handset's own. */
    private fun recording(offsets: List<Int>, gains: List<Double>): ShortArray {
        val chirp = ChirpGenerator.generateMono()
        val out = ShortArray(ChirpGenerator.SAMPLE_RATE * 4)
        val noise = Random(7)
        for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
        for ((at, offset) in offsets.withIndex()) {
            for (index in chirp.indices) {
                out[offset + index] = (out[offset + index] + chirp[index] * gains[at]).toInt().toShort()
            }
        }
        return out
    }

    private fun slotsOf(recorded: ShortArray, ownSlot: Int, slotCount: Int) =
        AlignmentAnalysis.readSlots(
            recorded = recorded,
            reference = ChirpGenerator.generateMono(),
            ownSlot = ownSlot,
            slotCount = slotCount,
            slotFrames = slot,
            searchRadiusFrames = radius
        )

    @Test
    fun findsEveryHandsetsChirpInOneWindow() {
        val at = listOf(9000, 9000 + slot, 9000 + 2 * slot)

        val slots = slotsOf(recording(at, listOf(1.4, 0.6, 0.6)), ownSlot = 0, slotCount = 3)

        assertEquals(at, slots.map { it?.index })
    }

    /**
     * The anchor is this handset's own chirp, and its slot is whichever one the schedule gave it.
     * Assuming the loudest is the first would put every handset but one a whole slot out.
     */
    @Test
    fun findsThemWhenThisHandsetIsNotTheFirstToChirp() {
        val at = listOf(9000, 9000 + slot, 9000 + 2 * slot)

        val slots = slotsOf(recording(at, listOf(0.6, 0.6, 1.4)), ownSlot = 2, slotCount = 3)

        assertEquals(at, slots.map { it?.index })
    }

    /**
     * Every pair in the room out of one window, which is the whole point: measured serially, two
     * pairs are two snapshots taken a round apart with the clocks drifting in between.
     */
    @Test
    fun measuresEveryPairFromTheOneRecording() {
        val late = 480 // 10 ms
        val slots = slotsOf(
            recording(listOf(9000, 9000 + slot + late, 9000 + 2 * slot), listOf(1.4, 0.6, 0.6)),
            ownSlot = 0,
            slotCount = 3
        )

        assertEquals(10.0, errorOf(slots, 0, 1), 1e-9)
        assertEquals(-10.0, errorOf(slots, 1, 2), 1e-9)
        // The two handsets that kept the schedule still read as keeping it, which is the property
        // that makes one handset's fault its own rather than the whole room's.
        assertEquals(0.0, errorOf(slots, 0, 2), 1e-9)
    }

    /** A handset nobody could hear reads as unreliable, and only the pairs holding it do. */
    @Test
    fun saysNothingAboutAPairWhoseChirpNeverArrived() {
        val slots = slotsOf(
            recording(listOf(9000, 9000 + 2 * slot), listOf(1.4, 0.6)).let { it },
            ownSlot = 0,
            slotCount = 3
        )

        assertEquals(AlignmentConfidence.UNRELIABLE, readingOf(slots, 0, 1).confidence)
        assertEquals(AlignmentConfidence.OK, readingOf(slots, 0, 2).confidence)
    }

    /**
     * The pin. For two handsets the new path has to answer exactly what the old one answers - the
     * same frames, the same milliseconds, the same verdict - because the old one produced every
     * alignment number in the archive and a quietly different answer ends their comparability.
     */
    @Test
    fun readsAPairExactlyAsThePairPathDoes() {
        val recorded = recording(listOf(9000, 9000 + slot + 240), listOf(0.6, 1.4))
        val reference = ChirpGenerator.generateMono()

        val pair = AlignmentAnalysis.read(
            recorded = recorded,
            reference = reference,
            staggerFrames = slot,
            searchRadiusFrames = radius,
            separationMetres = 1.5
        )
        // This handset chirped second, which is the host's side of the pair.
        val viaSlots = readingOf(slotsOf(recorded, ownSlot = 1, slotCount = 2), 0, 1, metres = 1.5)

        assertNotNull(pair.alignmentErrorMs)
        assertEquals(pair.firstIndex, viaSlots.firstIndex)
        assertEquals(pair.secondIndex, viaSlots.secondIndex)
        assertEquals(pair.measuredStaggerFrames, viaSlots.measuredStaggerFrames)
        assertEquals(pair.alignmentErrorMs!!, viaSlots.alignmentErrorMs!!, 1e-12)
        assertEquals(pair.propagationCorrectionMs, viaSlots.propagationCorrectionMs, 1e-12)
        assertEquals(pair.confidence, viaSlots.confidence)
        assertEquals(pair.ratios, viaSlots.ratios)
        assertEquals(pair.atSearchEdge, viaSlots.atSearchEdge)
    }

    /** And the same when this handset is the one that chirped first. */
    @Test
    fun readsAPairExactlyAsThePairPathDoesFromTheOtherSide() {
        val recorded = recording(listOf(9000, 9000 + slot + 240), listOf(1.4, 0.6))
        val pair = AlignmentAnalysis.read(
            recorded = recorded,
            reference = ChirpGenerator.generateMono(),
            staggerFrames = slot,
            searchRadiusFrames = radius,
            separationMetres = 0.0
        )

        val viaSlots = readingOf(slotsOf(recorded, ownSlot = 0, slotCount = 2), 0, 1)

        assertEquals(pair.alignmentErrorMs!!, viaSlots.alignmentErrorMs!!, 1e-12)
        assertEquals(pair.confidence, viaSlots.confidence)
    }

    /** Two slots closer together than the search is wide can be mistaken for each other. */
    @Test
    fun refusesASearchWiderThanTheGapBetweenSlots() {
        try {
            AlignmentAnalysis.readSlots(
                recorded = recording(listOf(9000, 9000 + slot), listOf(1.4, 0.6)),
                reference = ChirpGenerator.generateMono(),
                ownSlot = 0,
                slotCount = 2,
                slotFrames = slot,
                searchRadiusFrames = slot
            )
            throw AssertionError("expected to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("slotFrames"))
        }
    }

    private fun readingOf(
        slots: List<ChirpArrival?>,
        earlier: Int,
        later: Int,
        metres: Double = 0.0
    ) = AlignmentAnalysis.betweenSlots(slots, earlier, later, slot, metres)

    private fun errorOf(slots: List<ChirpArrival?>, earlier: Int, later: Int): Double =
        readingOf(slots, earlier, later).alignmentErrorMs!!
}
