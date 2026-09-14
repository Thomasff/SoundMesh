package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * What a decorrelator has to be true of, and the first of these is the reason this is written here
 * rather than taken from a paper: an allpass filter's magnitude response is exactly one, so the
 * implementation can be checked against arithmetic instead of against an opinion.
 *
 * That distinction is the lesson of the separation axis that was built and removed on 2026-09-14.
 * Its offline figures were beautiful and meant nothing, because they asked the algorithm whether it
 * recognised its own definition. These do not: flatness, energy and tail length are properties of
 * the filter that hold for every input, and [theTailIsShortEnoughNotToSmearADrum] is one the
 * design can actually fail - it is what stops this being tuned into a reverb.
 *
 * None of them says it sounds enveloping. Nothing offline can. That is decided with the knob at
 * zero on one run and up on the next, in a room, by a person.
 */
class DecorrelatorTest {
    private val rate = 48_000

    private fun impulseResponse(peerId: String, length: Int): DoubleArray {
        val filter = Decorrelator(peerId, rate)
        return DoubleArray(length) { filter.left(if (it == 0) 1.0 else 0.0) }
    }

    /**
     * The exact criterion. An allpass section is (g + z^-M) / (1 + g z^-M), whose numerator and
     * denominator have equal magnitude at every frequency by construction, and a cascade of them
     * is the product of ones. So the tolerance here is floating point, not judgement: anything
     * looser would pass a filter that colours the sound, which is the one thing a decorrelator is
     * not allowed to do.
     */
    @Test
    fun theMagnitudeResponseIsFlatToFloatingPointPrecision() {
        val size = 1 shl 15
        val re = impulseResponse("da3fe1c00de55dc6", size)
        val im = DoubleArray(size)

        Fourier(size).forward(re, im)

        var worst = 0.0
        for (bin in 0 until size / 2) worst = maxOf(worst, abs(hypot(re[bin], im[bin]) - 1.0))
        assertTrue("magnitude wandered by $worst, so this filter colours the sound", worst < 1e-9)
    }

    /** The same statement in the time domain, and a check that the tail was not truncated away. */
    @Test
    fun itGivesBackExactlyTheEnergyItWasHanded() {
        val energy = impulseResponse("da3fe1c00de55dc6", 1 shl 15).sumOf { it * it }

        assertEquals(1.0, energy, 1e-9)
    }

    /**
     * The criterion that can fail, and the reason it is here: smearing an attack across the room is
     * exactly the fault that made the struck/held axis unpleasant enough to delete. Forty
     * milliseconds is under the echo threshold for percussive material, so a drum stays one drum.
     *
     * Swept over enough names to draw every filter this can build, not asked of one. The first
     * version of this test asked one name, got 40.7 ms, and hid the fact that the longest filter
     * in the same design took 50.8 - a quarter over the bound, on a handset nobody had named yet.
     */
    @Test
    fun noFilterThisCanDrawSmearsAnAttackPastFortyMilliseconds() {
        var worst = 0.0
        var worstName = ""
        for (at in 0 until 4000) {
            val name = "peer-$at"
            val millis = tailMillisOf(name)
            if (millis > worst) { worst = millis; worstName = name }
        }

        assertTrue("$worstName takes $worst ms to let 95% of an attack out, which is an echo", worst <= 40.0)
    }

    /**
     * The construction that buys the bound above's acoustic counterpart.
     *
     * Two filters agreeing about three stages and differing by one step in the fourth are barely
     * decorrelated - 0.51 measured, over the 0.5 asked for. The last stage is a check digit over
     * the other three precisely so that family cannot be drawn, and this is that claim: every two
     * different filters differ somewhere in at least two stages. The worst pair that still leaves
     * possible measures 0.40.
     */
    @Test
    fun everyTwoDifferentFiltersDifferInAtLeastTwoStages() {
        val drawn = LinkedHashSet<List<Int>>()
        for (at in 0 until 4000) drawn += Decorrelator("peer-$at", rate).delaySamples

        val all = drawn.toList()
        for (a in all.indices) for (b in a + 1 until all.size) {
            val apart = all[a].indices.count { all[a][it] != all[b][it] }
            assertTrue(
                "${all[a]} and ${all[b]} differ in only $apart stage",
                apart >= 2
            )
        }
    }

    /** How long 95% of an impulse takes to leave [peerId]'s filter, in milliseconds. */
    private fun tailMillisOf(peerId: String): Double {
        // A hundred milliseconds of room to be wrong in: a filter that needs more than this has
        // failed, and should say so rather than run off the end of the array reporting nothing.
        val length = rate / 10
        val response = impulseResponse(peerId, length)
        var carried = 0.0
        for (at in response.indices) {
            carried += response[at] * response[at]
            if (carried >= 0.95) return at * 1000.0 / rate
        }
        return Double.MAX_VALUE
    }

    /**
     * The point of the whole thing: two handsets handed the same music must stop playing the same
     * waveform, or the ear fuses them into one source at whichever is nearest.
     *
     * Measured over every lag rather than at zero, because two copies of one waveform a few
     * milliseconds apart are perfectly correlated - at a lag.
     */
    @Test
    fun twoHandsetsNoLongerPlayTheSameWaveform() {
        val names = listOf("da3fe1c00de55dc6", "9b17c4e20aa31f08", "40e6d9b7715c2a93")
        for (a in names.indices) for (b in a + 1 until names.size) {
            val worst = peakCorrelation(through(names[a]), through(names[b]))
            assertTrue(
                "${names[a]} and ${names[b]} still line up at $worst of full correlation",
                worst < 0.5
            )
        }
    }

    /** And the control: a handset against itself is one, so the measurement above can see agreement. */
    @Test
    fun aHandsetAgainstItselfIsFullyCorrelated() {
        assertEquals(1.0, peakCorrelation(through("da3fe1c00de55dc6"), through("da3fe1c00de55dc6")), 1e-9)
    }

    /** Nobody tells anybody which filter to use, so the name has to decide it the same way twice. */
    @Test
    fun theSameNameAlwaysGetsTheSameFilter() {
        val once = impulseResponse("da3fe1c00de55dc6", 4096)
        val again = impulseResponse("da3fe1c00de55dc6", 4096)

        assertTrue(once.contentEquals(again))
    }

    /** Two handsets in one room drawing the same filter would be two handsets with no effect at all. */
    @Test
    fun namesThatDifferByOneCharacterGetDifferentFilters() {
        val one = impulseResponse("da3fe1c00de55dc6", 4096)
        val other = impulseResponse("da3fe1c00de55dc7", 4096)

        assertNotEquals(
            "two handset names one character apart drew the same filter",
            one.toList(), other.toList()
        )
    }

    /** Two channels are two sounds; one leaking into the other would narrow the image, not widen it. */
    @Test
    fun theChannelsDoNotHearEachOther() {
        val filter = Decorrelator("da3fe1c00de55dc6", rate)

        var loudestRight = 0.0
        for (at in 0 until 4096) {
            filter.left(if (at == 0) 1.0 else 0.0)
            loudestRight = maxOf(loudestRight, abs(filter.right(0.0)))
        }

        assertEquals(0.0, loudestRight, 0.0)
    }

    private fun through(peerId: String): DoubleArray {
        val filter = Decorrelator(peerId, rate)
        val noise = Random(20260914L)
        return DoubleArray(16_384) { filter.left(noise.nextGaussian()) }
    }

    /** The largest normalised correlation between the two at any lag up to 60 ms. */
    private fun peakCorrelation(a: DoubleArray, b: DoubleArray): Double {
        val lags = 60 * rate / 1000
        val scale = sqrt(a.sumOf { it * it } * b.sumOf { it * it })
        var worst = 0.0
        for (lag in -lags..lags) {
            var sum = 0.0
            for (at in a.indices) {
                val other = at + lag
                if (other in b.indices) sum += a[at] * b[other]
            }
            worst = maxOf(worst, abs(sum) / scale)
        }
        return worst
    }
}
