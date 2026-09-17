package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * What a room has to be true of.
 *
 * None of these says it sounds like a room - nothing offline does, which is why the tunings are
 * borrowed rather than invented. What they do pin down is everything the borrowing does not cover:
 * that the tail lasts as long as it claims, that the top of it goes first, that two handsets get
 * two different rooms, and that none of it can make a sample louder than it was. The first two are
 * the two stand-ins this replaces, measured; the third is the property that lets a roomful of
 * handsets beat a pair of speakers; the fourth is the failure this project has actually shipped.
 */
class RoomReverbTest {
    private val rate = 48_000

    private fun tailOf(peerId: String, seconds: Double): DoubleArray {
        val room = RoomReverb(peerId, rate)
        return DoubleArray((rate * seconds).toInt()) { room.left(if (it == 0) 1.0 else 0.0) }
    }

    private fun rmsOf(values: DoubleArray, from: Int, until: Int): Double {
        var sum = 0.0
        for (at in from until until) sum += values[at] * values[at]
        return sqrt(sum / (until - from))
    }

    /**
     * The tail lasts as long as [RoomReverb.REVERB_SECONDS] says, which is the one number in this
     * that a listener would name if asked what room they were in.
     *
     * Measured as the level of the last twentieth of a reverberation time against the first, which
     * for a decay of sixty decibels over the whole should land near it. Loose, because eight combs
     * beating against each other is not a clean exponential and the point is the order of
     * magnitude: this catches a room that rings for four seconds or dies in fifty milliseconds,
     * and those are the two failures worth catching.
     */
    @Test
    fun theTailFallsSixtyDecibelsInAboutTheTimeItClaims() {
        val tail = tailOf("aa11", RoomReverb.REVERB_SECONDS)
        val window = rate / 20

        val opening = rmsOf(tail, 0, window)
        val closing = rmsOf(tail, tail.size - window, tail.size)

        val fallen = 20.0 * log10(opening / closing)
        assertTrue("fell $fallen dB over a reverberation time", fallen in 40.0..80.0)
    }

    /**
     * **The claim that retires the shelf.** A wall keeps the bottom and takes the top, once per
     * bounce, so the tail is duller at its end than at its beginning. That is the whole reason a
     * sound across a room is duller than the same sound close by, and doing it here rather than
     * across the output is the difference between a room and a tone control.
     *
     * The two bands are measured off adjacent samples rather than through a filter: a sum is what
     * two neighbours have in common and a difference is what they do not, which is a low half and a
     * high half with no coefficient to get wrong and nothing borrowed from the code under test.
     */
    @Test
    fun theTopOfTheTailGoesBeforeTheBottomOfIt() {
        val tail = tailOf("aa11", RoomReverb.REVERB_SECONDS)
        val early = rate / 20 until rate / 10
        val late = tail.size - rate / 20 until tail.size

        val earlyTilt = tiltOf(tail, early.first, early.last + 1)
        val lateTilt = tiltOf(tail, late.first, late.last + 1)

        val lost = 20.0 * log10(earlyTilt / lateTilt)
        assertTrue("the tail lost $lost dB of its top over its length", lost > 6.0)
    }

    /** High over low, from what neighbouring samples disagree and agree about. */
    private fun tiltOf(values: DoubleArray, from: Int, until: Int): Double {
        var high = 0.0
        var low = 0.0
        for (at in from until until) {
            val up = values[at] + values[at - 1]
            val down = values[at] - values[at - 1]
            low += up * up
            high += down * down
        }
        return sqrt(high / low)
    }

    /**
     * Two handsets are two points in the room, and the reverberation at two points in a room is
     * uncorrelated - that is what makes a room enveloping instead of a mono echo. So this is not a
     * tolerance being met but the feature itself, and it is the one thing several handsets can do
     * that a pair of speakers cannot.
     */
    @Test
    fun twoHandsetsGetTwoDifferentRooms() {
        val mine = tailOf("aa11", 0.3)
        val yours = tailOf("bf90", 0.3)

        assertNotEquals(
            RoomReverb("aa11", rate).combSamples,
            RoomReverb("bf90", rate).combSamples
        )
        var together = 0.0
        for (at in mine.indices) together += mine[at] * yours[at]
        val correlation = abs(together) / sqrt(
            mine.sumOf { it * it } * yours.sumOf { it * it }
        )
        assertTrue("two handsets' tails correlate at $correlation", correlation < 0.2)
    }

    /** Drawn from the name and from nothing else, so a handset that reconnects is the same room. */
    @Test
    fun theSameNameAlwaysGetsTheSameRoom() {
        assertEquals(
            RoomReverb("aa11", rate).combSamples,
            RoomReverb("aa11", rate).combSamples
        )
    }

    /**
     * The failure this project has shipped, ruled out by arithmetic rather than by a clamp: eight
     * combs in parallel are fed at one over the sum of their own worst-case gains, so what leaves
     * cannot be larger than what went in. Checked on an impulse, which is the input that puts every
     * comb in phase at once and so is the worst case this bound was written against.
     */
    @Test
    fun theRoomNeverHandsBackMoreThanItWasGiven() {
        val loudest = tailOf("aa11", 1.0).maxOf { abs(it) }

        assertTrue("an impulse came back at $loudest", loudest <= 1.0)
    }

    /**
     * Noise rather than an impulse, because that is what music looks like to a filter and because
     * the worst case above says nothing about the ordinary one. A tail far below the source would
     * leave [RoomReverb.MOST_WET] unable to reach an audible room; one above it would mean the
     * crossfade was carrying the headroom alone.
     */
    @Test
    fun anOrdinarySignalComesBackAsATailYouCouldHear() {
        val room = RoomReverb("aa11", rate)
        val noise = Random(4)
        val fed = DoubleArray(rate)
        val wet = DoubleArray(rate)
        for (at in fed.indices) {
            fed[at] = noise.nextGaussian()
            wet[at] = room.left(fed[at])
        }
        // The second half only: the first is the room filling up, which is not its steady level.
        val quieter = 20.0 * log10(
            rmsOf(wet, rate / 2, rate) / rmsOf(fed, rate / 2, rate)
        )

        assertTrue("the tail sits $quieter dB under the source", quieter in -16.0..-8.0)
    }

    /** Two channels are two sounds, and a room that shared one bank would collapse them into one. */
    @Test
    fun theTwoChannelsRememberSeparately() {
        val room = RoomReverb("aa11", rate)

        room.left(1.0)
        for (at in 1 until rate / 10) room.left(0.0)
        val rightAfterLeftWasFed = (0 until rate / 10).map { room.right(0.0) }

        assertTrue(
            "the right channel played the left channel's tail",
            rightAfterLeftWasFed.all { it == 0.0 }
        )
    }

    /**
     * A comb of a different length needs a different feedback to die at the same time, and the
     * longer one needs the smaller: it only gets to go round a few times before the reverberation
     * time is up, so each of those times has to account for more of the sixty decibels. Sharing one
     * feedback between combs of different lengths - which is what Freeverb does - leaves the short
     * ones gone while the long ones are still going, and that difference is most of its ring.
     */
    @Test
    fun aLongerCombLosesMorePerBounceBecauseItsBounceTakesLonger() {
        val short = RoomReverb.feedbackFor(1116, rate)
        val long = RoomReverb.feedbackFor(1617, rate)

        assertTrue("$short against $long", long < short)
        // Under one, or the room never stops; above nothing, or it never starts.
        assertTrue("$long is not a decay", long > 0.0 && short < 1.0)
    }

    /** Off is off: the knob at nothing asks for no wet at all, not for very nearly none. */
    @Test
    fun aRoomNobodyAskedForIsExactlyNone() {
        assertEquals(0.0, RoomReverb.wetFor(0.0), 0.0)
        assertEquals(RoomReverb.MOST_WET, RoomReverb.wetFor(1.0), 0.0)
    }
}
