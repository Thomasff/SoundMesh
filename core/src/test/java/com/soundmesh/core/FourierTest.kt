package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The transform checked against its own definition, which is the whole reason it is written here.
 *
 * Everything else in this project is judged by ear or by a measurement with noise in it. This one
 * has an oracle: the sum below **is** the discrete Fourier transform, fifteen lines of it, and a
 * fast implementation that disagrees with it is wrong - there is no interpretation to argue about.
 * That is why a library was not taken for this and is taken for nothing else in the same pipeline.
 */
class FourierTest {
    /** The definition, written out. Quadratic and slow, which is exactly what makes it trustworthy. */
    private fun byDefinition(re: DoubleArray, im: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (bin in 0 until n) {
            var sumRe = 0.0
            var sumIm = 0.0
            for (at in 0 until n) {
                val angle = -2.0 * PI * bin * at / n
                val turnRe = cos(angle)
                val turnIm = sin(angle)
                sumRe += re[at] * turnRe - im[at] * turnIm
                sumIm += re[at] * turnIm + im[at] * turnRe
            }
            outRe[bin] = sumRe
            outIm[bin] = sumIm
        }
        return outRe to outIm
    }

    private fun noise(n: Int, seed: Int): DoubleArray {
        val random = Random(seed)
        return DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
    }

    @Test
    fun agreesWithTheDefinitionOfTheTransform() {
        val size = 64
        val re = noise(size, seed = 1)
        val im = noise(size, seed = 2)
        val (wantRe, wantIm) = byDefinition(re, im)

        Fourier(size).forward(re, im)

        for (bin in 0 until size) {
            assertEquals("real part of bin $bin", wantRe[bin], re[bin], 1e-9)
            assertEquals("imaginary part of bin $bin", wantIm[bin], im[bin], 1e-9)
        }
    }

    /** Real input is the only kind this pipeline ever has, and it is the easiest case to get subtly wrong. */
    @Test
    fun agreesWithTheDefinitionOnRealInput() {
        val size = 256
        val re = noise(size, seed = 3)
        val im = DoubleArray(size)
        val (wantRe, wantIm) = byDefinition(re, im)

        Fourier(size).forward(re, im)

        for (bin in 0 until size) {
            assertEquals("real part of bin $bin", wantRe[bin], re[bin], 1e-9)
            assertEquals("imaginary part of bin $bin", wantIm[bin], im[bin], 1e-9)
        }
    }

    @Test
    fun comesBackToWhatWentIn() {
        val size = 512
        val re = noise(size, seed = 4)
        val im = noise(size, seed = 5)
        val wasRe = re.copyOf()
        val wasIm = im.copyOf()
        val fourier = Fourier(size)

        fourier.forward(re, im)
        fourier.inverse(re, im)

        for (at in 0 until size) {
            assertEquals("sample $at", wasRe[at], re[at], 1e-12)
            assertEquals("sample $at", wasIm[at], im[at], 1e-12)
        }
    }

    /** A steady tone at a bin's own frequency belongs entirely to that bin and its mirror. */
    @Test
    fun aToneThatFitsTheWindowLandsInOneBin() {
        val size = 128
        val bin = 7
        val re = DoubleArray(size) { cos(2.0 * PI * bin * it / size) }
        val im = DoubleArray(size)

        Fourier(size).forward(re, im)

        for (at in 0 until size) {
            val magnitude = hypot(re[at], im[at])
            val want = if (at == bin || at == size - bin) size / 2.0 else 0.0
            assertEquals("bin $at", want, magnitude, 1e-9)
        }
    }

    /** The same instance, twice, has to answer the same thing: the tables are read, never written. */
    @Test
    fun theSecondTimeThroughIsTheSameAsTheFirst() {
        val size = 64
        val fourier = Fourier(size)
        val first = noise(size, seed = 6)
        val firstIm = DoubleArray(size)
        val second = first.copyOf()
        val secondIm = DoubleArray(size)

        fourier.forward(first, firstIm)
        fourier.forward(DoubleArray(size) { 1.0 }, DoubleArray(size))
        fourier.forward(second, secondIm)

        for (at in 0 until size) {
            assertEquals("bin $at", first[at], second[at], 1e-12)
            assertEquals("bin $at", firstIm[at], secondIm[at], 1e-12)
        }
    }

    @Test
    fun refusesASizeThatIsNotAPowerOfTwo() {
        assertThrows(IllegalArgumentException::class.java) { Fourier(100) }
        assertThrows(IllegalArgumentException::class.java) { Fourier(0) }
        assertThrows(IllegalArgumentException::class.java) { Fourier(-8) }
    }

    /** A window that is not the size the tables were built for is a bug, not a shorter transform. */
    @Test
    fun refusesAWindowOfTheWrongLength() {
        val fourier = Fourier(16)

        assertThrows(IllegalArgumentException::class.java) {
            fourier.forward(DoubleArray(8), DoubleArray(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fourier.forward(DoubleArray(16), DoubleArray(8))
        }
    }

    /** The smallest transform there is. Two points is one butterfly and nothing else. */
    @Test
    fun handlesTheSmallestWindowThereIs() {
        val re = doubleArrayOf(3.0, 1.0)
        val im = doubleArrayOf(0.0, 0.0)

        Fourier(2).forward(re, im)

        assertEquals(4.0, re[0], 1e-12)
        assertEquals(2.0, re[1], 1e-12)
        assertEquals(0.0, im[0], 1e-12)
        assertEquals(0.0, im[1], 1e-12)
    }
}
