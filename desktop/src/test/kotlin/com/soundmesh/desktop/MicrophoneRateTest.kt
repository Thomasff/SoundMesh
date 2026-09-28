package com.soundmesh.desktop

import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.Resampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A microphone at 44.1 kHz, recorded as it is and put at the chirp's rate after the round.
 *
 * Taken on 2026-09-28 on an offline replay rather than on a microphone, since no machine here offers
 * 44.1: 954 archived chirp repeats sent through 48000 -> 44100 -> 48000 kept the gap between their two
 * arrivals to the frame in 949 of them, and moved none on average (docs/cross-platform.md). Only
 * 44.1 was replayed, so only 44.1 is taken.
 */
class MicrophoneRateTest {
    @Test
    fun aRoundRecordsAtTheChirpsRateAndAt44k1AndAtNothingElse() {
        assertTrue(MicrophoneCheck.recordsAt(48_000))
        assertTrue(MicrophoneCheck.recordsAt(44_100))
        // A headset's 16 kHz cannot carry the top of the 1-8 kHz sweep; 96 kHz was never replayed.
        assertFalse(MicrophoneCheck.recordsAt(16_000))
        assertFalse(MicrophoneCheck.recordsAt(96_000))
    }

    /** Every recording made so far was at 48 kHz, and none of them may change by this. */
    @Test
    fun aRecordingAlreadyAtTheChirpsRateIsLeftAsItIs() {
        val recording = ShortArray(4_800) { (it % 97).toShort() }
        assertSame(recording, atChirpRate(recording, ChirpGenerator.SAMPLE_RATE))
    }

    /**
     * A second at 44.1 kHz comes back as a second at 48 kHz with the chirp on the sample it was
     * played at: the analysis counts from the first sample, so a conversion that shifted or
     * stretched the recording would move every arrival with it.
     */
    @Test
    fun aRecordingAt44k1ComesBackAtTheChirpsRateWithTheChirpWhereItWas() {
        val chirp = ChirpGenerator.generateMono()
        val played = ShortArray(ChirpGenerator.SAMPLE_RATE)
        chirp.copyInto(played, AT)
        // What a 44.1 kHz microphone would have handed over for the same second.
        val heard = mono(Resampler.toStereo(bytes(played), ChirpGenerator.SAMPLE_RATE, 1, 44_100))

        val converted = atChirpRate(heard, 44_100)

        assertEquals(ChirpGenerator.SAMPLE_RATE, converted.size)
        assertEquals(AT, ChirpCorrelator.findArrival(converted, chirp, AT - 1_200, AT + 1_200)!!.index)
    }

    private fun bytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
        }
        return out
    }

    private fun mono(stereo: ByteArray): ShortArray =
        ShortArray(stereo.size / 4) { ((stereo[it * 4].toInt() and 0xFF) or (stereo[it * 4 + 1].toInt() shl 8)).toShort() }

    private companion object {
        const val AT = 20_000
    }
}
