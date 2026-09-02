package com.soundmesh.core

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChirpGeneratorTest {
    @Test
    fun producesTheAdvertisedLengthAndStaysInsidePcmRange() {
        val chirp = ChirpGenerator.generateMono()

        assertEquals(ChirpGenerator.FRAME_COUNT, chirp.size)
        assertEquals(5760, chirp.size)
        assertTrue(chirp.all { abs(it.toInt()) <= Short.MAX_VALUE.toInt() })
    }

    @Test
    fun isDeterministicSoTheReferenceMatchesWhatWasPlayed() {
        assertEquals(true, ChirpGenerator.generateMono().contentEquals(ChirpGenerator.generateMono()))
    }

    /** The whole calibration rests on this: a sharp peak and no rival sidelobe. */
    @Test
    fun correlatesWithItselfAsASingleSharpPeak() {
        val chirp = ChirpGenerator.generateMono().map { it.toDouble() }
        fun correlateAt(lag: Int): Double {
            var total = 0.0
            for (index in 0 until chirp.size - abs(lag)) {
                total += chirp[index + maxOf(lag, 0)] * chirp[index + maxOf(-lag, 0)]
            }
            return abs(total)
        }

        val peak = correlateAt(0)
        val worstSidelobe = (100..2000 step 25).maxOf { correlateAt(it) }

        assertTrue("sidelobe ${worstSidelobe / peak} of peak", worstSidelobe < peak * 0.35)
    }
}
