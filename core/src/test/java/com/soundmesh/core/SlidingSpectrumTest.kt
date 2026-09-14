package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * The sliding transform judged by the one property that costs nothing to check: identity.
 *
 * A stream taken apart into windows and put back together, with nothing touched in between, has to
 * come back out as the sound that went in. That is not a weak test - getting the window, the hop
 * and the sum of the two windowings wrong all show up here as a signal that beats or steps, and
 * every one of those is a fault a listener would only be able to describe as "it sounds a bit off".
 */
class SlidingSpectrumTest {
    private fun noise(count: Int, seed: Int): DoubleArray {
        val random = Random(seed)
        return DoubleArray(count) { random.nextDouble(-1.0, 1.0) }
    }

    /** Runs [sound] through [spectrum] and returns what came back, sample for sample. */
    private fun through(spectrum: SlidingSpectrum, sound: DoubleArray): DoubleArray =
        DoubleArray(sound.size) { spectrum.pass(sound[it]) }

    @Test
    fun handsTheSoundBackAsItWasWhenNothingChangesIt() {
        val size = 256
        val spectrum = SlidingSpectrum(size)
        val sound = noise(4000, seed = 11)

        val back = through(spectrum, sound)

        for (at in spectrum.held until sound.size) {
            assertEquals("sample $at", sound[at - spectrum.held], back[at], 1e-9)
        }
    }

    /** The window has to be whole before anything in it can be transformed, and that is the wait. */
    @Test
    fun saysHowLongItIsHoldingOnTo() {
        assertEquals(255, SlidingSpectrum(256).held)
        assertEquals(2047, SlidingSpectrum(2048).held)
    }

    @Test
    fun saysNothingUntilItHasAWholeWindow() {
        val spectrum = SlidingSpectrum(64)

        val back = through(spectrum, noise(200, seed = 12))

        for (at in 0 until spectrum.held) {
            assertEquals("sample $at came out before there was a window to make it from", 0.0, back[at], 0.0)
        }
    }

    @Test
    fun emptyingTheSpectrumEmptiesTheSound() {
        val spectrum = SlidingSpectrum(64) { re, im -> re.fill(0.0); im.fill(0.0) }

        val back = through(spectrum, noise(500, seed = 13))

        for (at in back.indices) {
            assertEquals("sample $at", 0.0, back[at], 1e-12)
        }
    }

    /**
     * What the caller is handed is the transform of the windowed sound, not of the raw sound.
     *
     * Worth its own test because every frame is windowed twice - once going in and once coming
     * back - and a reader looking at these numbers has to know which of the two they are seeing.
     */
    @Test
    fun showsTheTransformOfTheWindowedSound() {
        val size = 64
        val sound = noise(size, seed = 14)
        var sawRe: DoubleArray? = null
        var sawIm: DoubleArray? = null
        // The fourth frame, not the first: the three before it reach back past the start of the
        // stream and are mostly the silence that was there before anything was handed in.
        var seen = 0
        val spectrum = SlidingSpectrum(size) { re, im ->
            if (++seen == 4) { sawRe = re.copyOf(); sawIm = im.copyOf() }
        }

        through(spectrum, sound)

        val wantRe = DoubleArray(size) { sound[it] * (0.5 - 0.5 * cos(2.0 * PI * it / size)) }
        val wantIm = DoubleArray(size)
        Fourier(size).forward(wantRe, wantIm)
        for (bin in 0 until size) {
            assertEquals("real part of bin $bin", wantRe[bin], sawRe!![bin], 1e-9)
            assertEquals("imaginary part of bin $bin", wantIm[bin], sawIm!![bin], 1e-9)
        }
    }

    /**
     * Only windows this arrangement actually adds back up over.
     *
     * A quarter-window hop is what makes four overlapping raised cosines, squared, sum to a
     * constant. At any other hop they sum to something that ripples, and the sound comes back with
     * a hum at the frame rate that nobody would trace to the window length.
     */
    @Test
    fun refusesAWindowItCannotAddBackUp() {
        assertThrows(IllegalArgumentException::class.java) { SlidingSpectrum(100) }
        assertThrows(IllegalArgumentException::class.java) { SlidingSpectrum(2) }
        assertThrows(IllegalArgumentException::class.java) { SlidingSpectrum(0) }
    }
}
