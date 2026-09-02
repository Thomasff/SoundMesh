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

    /** The chunks are what the scheduler is handed, so they must be exactly chunk shaped. */
    @Test
    fun cutsTheChirpIntoWholeStereoChunksPaddedWithSilence() {
        val chunks = ChirpGenerator.generateStereoChunks(960)

        assertEquals(6, chunks.size)
        assertTrue(chunks.all { it.size == 960 * 2 * 2 })

        // 5760 frames divide evenly by 960, so ask for a size that does not and check the pad.
        val uneven = ChirpGenerator.generateStereoChunks(1000)
        assertEquals(6, uneven.size)
        val tail = uneven.last()
        // Frames 5760..5999 of the last chunk are past the end of the sweep.
        assertTrue(tail.copyOfRange(760 * 2 * 2, tail.size).all { it.toInt() == 0 })
    }

    /** Both channels must carry the same sweep, or the reference chirp.wav stops matching. */
    @Test
    fun duplicatesTheMonoSweepIntoBothChannelsInOrder() {
        val mono = ChirpGenerator.generateMono()
        val chunks = ChirpGenerator.generateStereoChunks(960)

        for (frame in intArrayOf(0, 1, 959, 960, 5759)) {
            val pcm = chunks[frame / 960]
            val at = (frame % 960) * 4
            val left = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort()
            val right = ((pcm[at + 3].toInt() shl 8) or (pcm[at + 2].toInt() and 0xFF)).toShort()
            assertEquals("frame $frame left", mono[frame], left)
            assertEquals("frame $frame right", mono[frame], right)
        }
    }
}
