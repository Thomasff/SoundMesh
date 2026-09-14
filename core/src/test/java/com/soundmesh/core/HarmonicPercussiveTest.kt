package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The separation judged on sound that was built to be separable, not by ear.
 *
 * A steady tone is what harmonic means and a click is what percussive means, so a mix of the two
 * is a case where the right answer is known in advance and can be counted. Ears come later and
 * answer a different question: an ear says whether the effect is there, never how much of it.
 */
class HarmonicPercussiveTest {
    private val size = 256
    private val rate = 48_000

    /** Long enough that the window, and then the whole run of frames the median looks back over, have filled. */
    private val settled = size * 8

    private fun tone(count: Int, hz: Double) =
        DoubleArray(count) { sin(2.0 * PI * hz * it / rate) }

    /** A click every [every] samples: one sample of full scale, silence in between. */
    private fun clicks(count: Int, every: Int) =
        DoubleArray(count) { if (it % every == 0) 1.0 else 0.0 }

    private fun mixed(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] + b[it] }

    private fun through(sound: DoubleArray, harmonic: Double, percussive: Double): DoubleArray {
        val halves = HarmonicPercussive(size)
        halves.keep(harmonic, percussive)
        val spectrum = SlidingSpectrum(size, halves::change)
        return DoubleArray(sound.size) { spectrum.pass(sound[it]) }
    }

    private fun energy(sound: DoubleArray) =
        (settled until sound.size).sumOf { sound[it] * sound[it] }

    /**
     * Both halves kept is the mix itself, exactly - the same property [Crossover] was built around.
     *
     * It matters for the same reason: it is what makes the knob wind back to the sound the room was
     * already playing. A separation whose two halves do not add up leaves a listener with no
     * setting that means "leave it alone".
     */
    @Test
    fun keepingBothHalvesHandsBackTheMix() {
        val sound = mixed(tone(12_000, hz = 440.0), clicks(12_000, every = 2400))

        val whole = through(sound, harmonic = 1.0, percussive = 1.0)

        for (at in settled until sound.size) {
            assertEquals("sample $at", sound[at - size + 1], whole[at], 1e-9)
        }
    }

    @Test
    fun theTwoHalvesAddBackUpToTheMix() {
        val sound = mixed(tone(12_000, hz = 440.0), clicks(12_000, every = 2400))

        val steady = through(sound, harmonic = 1.0, percussive = 0.0)
        val sudden = through(sound, harmonic = 0.0, percussive = 1.0)

        for (at in settled until sound.size) {
            assertEquals("sample $at", sound[at - size + 1], steady[at] + sudden[at], 1e-9)
        }
    }

    @Test
    fun aSteadyToneGoesToTheHarmonicHalf() {
        val sound = tone(12_000, hz = 440.0)

        val steady = energy(through(sound, harmonic = 1.0, percussive = 0.0))
        val sudden = energy(through(sound, harmonic = 0.0, percussive = 1.0))

        assertTrue("a tone left $sudden in the percussive half against $steady in the harmonic one",
            steady > sudden * 1000.0)
    }

    @Test
    fun aClickGoesToThePercussiveHalf() {
        val sound = clicks(12_000, every = 2400)

        val steady = energy(through(sound, harmonic = 1.0, percussive = 0.0))
        val sudden = energy(through(sound, harmonic = 0.0, percussive = 1.0))

        assertTrue("clicks left $steady in the harmonic half against $sudden in the percussive one",
            sudden > steady * 1000.0)
    }

    /** Nothing divided by nothing is the shape every masking rule has, and silence is where it happens. */
    @Test
    fun silenceStaysSilentRatherThanBecomingNotANumber() {
        val quiet = DoubleArray(4000)

        val steady = through(quiet, harmonic = 1.0, percussive = 0.0)
        val sudden = through(quiet, harmonic = 0.0, percussive = 1.0)

        for (at in quiet.indices) {
            assertEquals("harmonic sample $at", 0.0, steady[at], 0.0)
            assertEquals("percussive sample $at", 0.0, sudden[at], 0.0)
        }
    }
}
