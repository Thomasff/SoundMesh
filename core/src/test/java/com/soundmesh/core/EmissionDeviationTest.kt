package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The archived run these are taken from is 2026-09-08 18:29, X10 hosting and Magic6 following on
 * a hotspot. Every number below is read straight out of both handsets' recordings of that run.
 */
class EmissionDeviationTest {
    private val interval = 5 * 48_000

    /** The X10's own chirps, as the X10's own recording heard them. It plays second. */
    private val x10FromItself = listOf(287_434, 527_435, 767_379, 1_007_436, 1_247_369)

    /** The same five chirps as the Magic6 heard them, a room away. */
    private val x10FromTheMagic6 = listOf(288_480, 528_481, 768_425, 1_008_477, 1_248_410)

    /** The Magic6's own chirps, as the Magic6 heard them. It plays first. */
    private val magic6FromItself = listOf(264_449, 504_440, 744_446, 984_448, 1_224_444)

    @Test
    fun readsHowFarEachChirpLandedFromTheGridItWasScheduledOn() {
        val deviations = EmissionDeviation.of(x10FromItself, interval)

        // Frames 0, +1, -55, +2, -65 against a median-centred grid, at 48 kHz.
        assertEquals(listOf(0.0, 0.0208, -1.1458, 0.0417, -1.3542), deviations.map { round(it) })
    }

    /**
     * The check the analysis was missing for a day. Both handsets record both chirps, so every
     * emission is read twice from two independent recordings - and a real emission event is seen
     * by both microphones while a correlator taking the wrong peak is not.
     */
    @Test
    fun bothRecordingsAgreeOnWhichChirpStepped() {
        val here = EmissionDeviation.of(x10FromItself, interval)
        val there = EmissionDeviation.of(x10FromTheMagic6, interval)

        for (repeat in here.indices) {
            assertEquals(
                "the two recordings disagree about chirp $repeat",
                here[repeat]!!, there[repeat]!!, 0.07
            )
        }
    }

    /**
     * The whole point of separating the two. In this run the combined error scattered by 0.711 ms
     * and the report could only say so; the X10 stepped twice by more than a millisecond and the
     * Magic6 held to a tenth of one. One number cannot say that, and two can.
     */
    @Test
    fun tellsAJumpyHandsetFromASteadyOne() {
        val x10 = EmissionDeviation.spreadMs(EmissionDeviation.of(x10FromItself, interval))!!
        val magic6 = EmissionDeviation.spreadMs(EmissionDeviation.of(magic6FromItself, interval))!!

        assertEquals(0.700, x10, 0.001)
        assertEquals(0.075, magic6, 0.001)
    }

    /**
     * A chirp that could not be read takes no part in the centre and comes back as a hole, on the
     * same terms as everything else in this module: a run is not judged around a missing pair.
     */
    @Test
    fun aChirpThatCouldNotBeReadIsAHoleRatherThanAZero() {
        val deviations = EmissionDeviation.of(listOf(287_434, null, 767_379), interval)

        assertEquals(3, deviations.size)
        assertNull(deviations[1])
        // The centre is the median of the two that were read, so both sit either side of it.
        assertEquals(0.5729, round(deviations[0]), 0.0001)
        assertEquals(-0.5729, round(deviations[2]), 0.0001)
    }

    @Test
    fun answersNothingAboutARunNothingWasReadIn() {
        assertEquals(listOf(null, null), EmissionDeviation.of(listOf(null, null), interval))
        assertNull(EmissionDeviation.spreadMs(listOf(null, 1.0)))
    }

    private fun round(value: Double?): Double =
        Math.round((value ?: 0.0) * 10_000) / 10_000.0
}
