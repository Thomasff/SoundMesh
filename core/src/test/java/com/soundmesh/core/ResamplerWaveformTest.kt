package com.soundmesh.core

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact bytes this conversion has always produced, pinned so they cannot move quietly.
 *
 * Every archived alignment measurement was made against audio that came out of here, and the
 * numbers from 186 of them are compared with each other and with runs still to come. A change in
 * the waveform would not fail anything: the tests next door check that a tone keeps its pitch and
 * its level, which a slightly different filter would also do. It would simply make the new runs
 * incomparable with the old ones, months later, with nothing to point at.
 *
 * So this is a digest and not a property. It is here for the streaming rewrite in particular: the
 * conversion is about to be fed in pieces rather than whole, and "the pieces agree with each
 * other" is a much weaker claim than "the pieces agree with what was archived".
 *
 * If one of these fails, the question is not how to update the number. It is whether the waveform
 * was meant to change at all - and if it was, every comparison against the archive has to be
 * declared broken in the same commit.
 */
class ResamplerWaveformTest {
    @Test
    fun theWaveformComingOutOfARateChangeHasNotMoved() {
        assertEquals(
            "f38901c3c756e242d662ef779321925ab68056cbf35c05ff2d6e7d0e86b6df7c",
            digestOf(44_100, 2)
        )
    }

    /** Coming up from half rate, where the filter bank has the most work to do. */
    @Test
    fun theWaveformComingUpFromHalfRateHasNotMoved() {
        assertEquals(
            "f961c1fff93b55b0c159eb0a5ce393204c829cbe5f0e1aa624d641f7f8880db3",
            digestOf(22_050, 1)
        )
    }

    /** And coming down, where the cutoff follows the output instead of the input. */
    @Test
    fun theWaveformComingDownFromDoubleRateHasNotMoved() {
        assertEquals(
            "bff10088e9e69b74c617bc26a7d4dcec847e20260ac7079338b53ae609393112",
            digestOf(96_000, 2)
        )
    }

    /** The share path: no filter at all, one sample written into both channels. */
    @Test
    fun theWaveformOfASharedMonoSourceHasNotMoved() {
        assertEquals(
            "3d332d900e3bf3ad851821726315a60f26c3516768a7678caa98b365ea1eccb5",
            digestOf(48_000, 1)
        )
    }

    /** And the one that is supposed to be the input itself, byte for byte. */
    @Test
    fun audioThatNeededNothingDoneToItHasNotMoved() {
        assertEquals(
            "4f91c3af9750372a9ad256a7685c3a27c61923db9d714fb3061beeb2ec5a4eb3",
            digestOf(48_000, 2)
        )
    }

    private fun digestOf(rate: Int, channels: Int): String =
        sha(Resampler.toStereo(noise(rate, channels, FRAMES), rate, channels, 48_000))

    /**
     * The same pseudo-random samples every time, and full scale rather than a tone.
     *
     * A tone only ever lands on a few of the 512 filter phases, so a bank built wrongly at the
     * others would go unnoticed. Noise lands on all of them and clips nothing.
     */
    private fun noise(rate: Int, channels: Int, frames: Int): ByteArray {
        val pcm = ByteArray(frames * channels * 2)
        var state = 0x2545F491L
        for (i in 0 until frames * channels) {
            state = (state * 6364136223846793005L + 1442695040888963407L) ushr 1
            val value = (state and 0xFFFF).toInt() - 32768
            pcm[i * 2] = (value and 0xFF).toByte()
            pcm[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Long enough to cross many filter phases, short enough that a failure prints quickly. */
        const val FRAMES = 4321
    }
}
