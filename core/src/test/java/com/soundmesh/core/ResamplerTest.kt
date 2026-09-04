package com.soundmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ResamplerTest {
    /**
     * The one case that must cost nothing and change nothing. Every archived alignment run fed
     * this path 48 kHz stereo, and a converted copy of it - however good the converter - would
     * end the comparability of all of them.
     */
    @Test
    fun audioThatAlreadyFitsIsHandedBackUntouched() {
        val pcm = tone(48000, 1000.0, 0.1, 12000.0, 2)
        assertSame(pcm, Resampler.toStereo(pcm, 48000, 2, 48000))
    }

    /** One channel at the right rate is a copy, not a filter run: the samples must survive whole. */
    @Test
    fun oneChannelAtTheRightRateIsSharedNotFiltered() {
        val mono = tone(48000, 1000.0, 0.01, 12000.0, 1)
        val stereo = Resampler.toStereo(mono, 48000, 1, 48000)
        assertEquals(mono.size * 2, stereo.size)
        for (frame in 0 until mono.size / 2) {
            val sample = mono.copyOfRange(frame * 2, frame * 2 + 2)
            assertArrayEquals("frame $frame left", sample, stereo.copyOfRange(frame * 4, frame * 4 + 2))
            assertArrayEquals("frame $frame right", sample, stereo.copyOfRange(frame * 4 + 2, frame * 4 + 4))
        }
    }

    @Test
    fun aSecondOfAudioStaysASecond() {
        val pcm = tone(44100, 1000.0, 1.0, 12000.0, 2)
        val converted = Resampler.toStereo(pcm, 44100, 2, 48000)
        assertEquals(48000, converted.size / 4)
    }

    /**
     * The failure this catches is the loud one: audio handed to the renderer at the wrong rate
     * plays at the wrong speed, and every timing number in the run stays sane while it does.
     */
    @Test
    fun aToneKeepsItsPitch() {
        val converted = Resampler.toStereo(tone(44100, 1000.0, 0.5, 12000.0, 2), 44100, 2, 48000)
        assertEquals(500.0, upwardCrossings(left(converted, 2)).toDouble(), 2.0)
    }

    @Test
    fun aToneKeepsItsLevel() {
        val converted = Resampler.toStereo(tone(44100, 1000.0, 0.5, 12000.0, 2), 44100, 2, 48000)
        assertEquals(12000.0, amplitudeAt(left(converted, 2), 48000, 1000.0), 240.0)
    }

    /** Music has content up here. A converter that quietly dulls it is worse than one that refuses. */
    @Test
    fun theTopOfWhatMusicCarriesSurvives() {
        val converted = Resampler.toStereo(tone(44100, 15000.0, 0.5, 12000.0, 2), 44100, 2, 48000)
        val level = amplitudeAt(left(converted, 2), 48000, 15000.0)
        assertTrue("15 kHz came back at $level of 12000", level > 12000.0 * 0.89)
    }

    /** Coming down from 96 kHz, what will not fit must be removed rather than folded back in. */
    @Test
    fun aToneTooHighToFitDoesNotComeBackAsALowerOne() {
        val converted = Resampler.toStereo(tone(96000, 30000.0, 0.5, 12000.0, 2), 96000, 2, 48000)
        val alias = amplitudeAt(left(converted, 2), 48000, 18000.0)
        assertTrue("30 kHz folded back to 18 kHz at $alias", alias < 12000.0 * 0.02)
    }

    /**
     * A windowed sinc overshoots at a step, and a full scale source leaves no headroom for it.
     * Truncating the overshoot to 16 bits instead of clamping it turns a peak into its opposite -
     * the loudest possible artefact, from the loudest passages only.
     */
    @Test
    fun fullScaleAudioDoesNotWrapAround() {
        val converted = Resampler.toStereo(square(44100, 100.0, 0.2), 44100, 2, 48000)
        val samples = left(converted, 2)
        var inversions = 0
        for (index in 1 until samples.size) {
            if (samples[index - 1] > 30000 && samples[index] < -30000) inversions++
            if (samples[index - 1] < -30000 && samples[index] > 30000) inversions++
        }
        assertEquals(0, inversions)
    }

    @Test
    fun silenceIsStillSilence() {
        val converted = Resampler.toStereo(ByteArray(44100 * 4), 44100, 2, 48000)
        assertEquals(48000, converted.size / 4)
        assertTrue(converted.all { it.toInt() == 0 })
    }

    private fun tone(rate: Int, hz: Double, seconds: Double, amplitude: Double, channels: Int): ByteArray {
        val frames = (rate * seconds).toInt()
        val pcm = ByteArray(frames * channels * 2)
        for (frame in 0 until frames) {
            val value = (sin(2.0 * PI * hz * frame / rate) * amplitude).toInt()
            for (channel in 0 until channels) write(pcm, (frame * channels + channel) * 2, value)
        }
        return pcm
    }

    private fun square(rate: Int, hz: Double, seconds: Double): ByteArray {
        val frames = (rate * seconds).toInt()
        val pcm = ByteArray(frames * 4)
        val half = (rate / hz / 2.0).toInt()
        for (frame in 0 until frames) {
            val value = if ((frame / half) % 2 == 0) 32767 else -32767
            write(pcm, frame * 4, value)
            write(pcm, frame * 4 + 2, value)
        }
        return pcm
    }

    private fun write(pcm: ByteArray, at: Int, value: Int) {
        pcm[at] = (value and 0xFF).toByte()
        pcm[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun left(pcm: ByteArray, channels: Int): DoubleArray {
        val frames = pcm.size / (channels * 2)
        return DoubleArray(frames) { frame ->
            val at = frame * channels * 2
            (((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort()).toDouble()
        }
    }

    private fun upwardCrossings(samples: DoubleArray): Int {
        var crossings = 0
        for (index in 1 until samples.size) {
            if (samples[index - 1] <= 0.0 && samples[index] > 0.0) crossings++
        }
        return crossings
    }

    /**
     * One bin of a Hann windowed DFT. The window costs resolution and buys two things this needs:
     * no leakage from a frequency that is not a whole number of cycles, and no weight on the
     * first and last taps, where the converter is reading past the end of its input.
     */
    private fun amplitudeAt(samples: DoubleArray, rate: Int, hz: Double): Double {
        var real = 0.0
        var imaginary = 0.0
        for (index in samples.indices) {
            val window = 0.5 * (1.0 - cos(2.0 * PI * index / (samples.size - 1)))
            val angle = 2.0 * PI * hz * index / rate
            real += samples[index] * window * cos(angle)
            imaginary += samples[index] * window * sin(angle)
        }
        return 4.0 * hypot(real, imaginary) / samples.size
    }
}
