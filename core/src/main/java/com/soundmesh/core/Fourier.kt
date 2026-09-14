package com.soundmesh.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The discrete Fourier transform of one fixed-length window, done in place.
 *
 * Written here rather than taken from a library, and that is the exception in this pipeline rather
 * than the rule. The rule, set on 2026-09-14, is to look for a library first, because something
 * hand-rolled that is subtly wrong sounds like nothing worse than "a bit disappointing" and nobody
 * ever finds out. This is the one piece that has an oracle: the definition of the transform is
 * fifteen lines of arithmetic, and an implementation that disagrees with it past rounding is wrong
 * with no room left to argue - see FourierTest. The separation that will be built on top has no
 * such oracle, which is why that one is being copied from published work and this one is not.
 *
 * What it cost to not take a library: the only pure-JVM candidate was JTransforms, which would have
 * been the first production dependency [Crossover] and its neighbours have ever had, would have
 * brought a second jar with it, and is multithreaded - meaning its thread pool would have had to be
 * found and switched off before this ran on an audio thread.
 *
 * Complex in, complex out, both halves in arrays the caller owns. Real input means handing in an
 * imaginary array of zeros and doing twice the arithmetic a real-input transform would. At the
 * sizes this is for - a couple of thousand points, a hundred or so windows a second - that is a
 * rounding error against the rest of the render loop, and half an algorithm not written is half an
 * algorithm that cannot be wrong.
 *
 * One instance per window length, built once and then read only: the tables below are the whole
 * reason this is a class rather than a function, and building them per window would cost more than
 * the transform. Nothing is allocated per call, because a call happens on the audio thread.
 */
class Fourier(val size: Int) {
    init {
        // A power of two has exactly one bit set, so clearing the lowest set bit empties it.
        require(size > 0 && (size and (size - 1)) == 0) {
            "a radix-2 transform takes a power of two: $size"
        }
    }

    /**
     * Where each input sample has to move before the butterflies start.
     *
     * The algorithm below works outward from pairs to the whole window, and for that to line up,
     * the window has to be sitting in bit-reversed order first: sample 1 of 8 where sample 4 was,
     * 3 where 6 was. Precomputed because the answer depends on nothing but [size].
     */
    private val reversed = IntArray(size).also { table ->
        val bits = size.countTrailingZeroBits()
        for (at in 0 until size) {
            var from = at
            var to = 0
            repeat(bits) {
                to = (to shl 1) or (from and 1)
                from = from shr 1
            }
            table[at] = to
        }
    }

    /**
     * The roots of unity, cosine and sine kept apart so each read is one array access.
     *
     * Only half of them, because the second half is the first half negated and every stage picks
     * its own stride through this one table. Two transcendentals per entry, paid once.
     */
    private val turnRe = DoubleArray(size / 2)
    private val turnIm = DoubleArray(size / 2)

    init {
        for (at in 0 until size / 2) {
            val angle = -2.0 * PI * at / size
            turnRe[at] = cos(angle)
            turnIm[at] = sin(angle)
        }
    }

    /**
     * Replaces [re] and [im] with their transform.
     *
     * In place, and the caller's arrays: this runs on the audio thread, where an allocation is a
     * pause that arrives at whatever moment the collector chooses.
     */
    fun forward(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) {
            "this transform is built for $size points, not ${re.size}/${im.size}"
        }
        for (at in 0 until size) {
            // Only one direction of each swap, or every pair would be exchanged twice and the
            // window would come out exactly as it went in.
            val to = reversed[at]
            if (at < to) {
                var carried = re[at]; re[at] = re[to]; re[to] = carried
                carried = im[at]; im[at] = im[to]; im[to] = carried
            }
        }
        var span = 2
        while (span <= size) {
            val half = span shr 1
            // How far apart this stage's roots sit in the table: the widest stage reads every
            // entry, the narrowest reads only the first.
            val stride = size / span
            var start = 0
            while (start < size) {
                for (at in 0 until half) {
                    val near = start + at
                    val far = near + half
                    val root = at * stride
                    val rootRe = turnRe[root]
                    val rootIm = turnIm[root]
                    val turnedRe = re[far] * rootRe - im[far] * rootIm
                    val turnedIm = re[far] * rootIm + im[far] * rootRe
                    re[far] = re[near] - turnedRe
                    im[far] = im[near] - turnedIm
                    re[near] += turnedRe
                    im[near] += turnedIm
                }
                start += span
            }
            span = span shl 1
        }
    }

    /**
     * Replaces a transform with the window it came from.
     *
     * The same butterflies, run with the two halves swapped over. Swapping real for imaginary is a
     * conjugation and a quarter turn; doing it on the way in and again on the way out leaves the
     * quarter turns cancelled and the conjugation standing, which is the only difference between
     * this direction and the other one. So there is one transform in this file, not two, and the
     * test that says a window survives a round trip is testing the only code path there is.
     */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        forward(im, re)
        val share = 1.0 / size
        for (at in 0 until size) {
            re[at] *= share
            im[at] *= share
        }
    }
}
