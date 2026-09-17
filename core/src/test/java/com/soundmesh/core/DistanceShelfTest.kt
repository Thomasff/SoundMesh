package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

class DistanceShelfTest {
    private val rate = 48_000

    /**
     * The ordinary case, and it has to be exact rather than close. Every chunk of every song with
     * the source where it stands runs through this filter, so "very nearly what it was handed" is
     * a room quietly playing something other than what was sent to it, for ever, with nothing to
     * hear and nothing to measure against.
     */
    @Test
    fun aSourceThatHasNotMovedGetsItsSamplesBackUntouched() {
        val shelf = DistanceShelf()
        val coefficient = DistanceShelf.coefficientFor(rate)
        val depth = DistanceShelf.depthFor(0.0)

        for (frame in 0 until 500) {
            val sample = 9000.0 * (if (frame % 2 == 0) 1 else -1) + frame
            assertEquals(sample, shelf.left(sample, coefficient, depth), 0.0)
            assertEquals(sample, shelf.right(sample, coefficient, depth), 0.0)
        }
    }

    /**
     * A shelf, not a volume knob. What a room takes away is the top; the bottom arrives at a
     * distance as loudly as the rule already decided it should - which is [SpatialField.retreat]'s
     * job and not this one's. If this dropped the bottom too, the two would be turning the room
     * down together and the drag would be worth twice what the readout claims.
     */
    @Test
    fun theBottomOfTheSoundArrivesWhole() {
        val shelf = DistanceShelf()
        val coefficient = DistanceShelf.coefficientFor(rate)
        val depth = DistanceShelf.depthFor(1.0)

        // Long enough for a one-pole at two kilohertz to have settled at forty-eight thousand.
        var out = 0.0
        repeat(2_000) { out = shelf.left(8000.0, coefficient, depth) }

        assertEquals(8000.0, out, 1e-6)
    }

    /**
     * And the top, at the far end of the drag.
     *
     * The expected side is the filter's arithmetic written out here rather than asked of the class
     * under test: a one-pole handed alternating samples settles at a/(2-a) of them, and the shelf
     * crossfades the sample toward that. The two constants are read rather than repeated, so
     * retuning either by ear leaves this green and breaking the filter does not.
     */
    @Test
    fun theTopIsTakenOffAtTheFurthestTheRuleHolds() {
        val shelf = DistanceShelf()
        val coefficient = DistanceShelf.coefficientFor(rate)
        val depth = DistanceShelf.depthFor(1.0)

        var out = 0.0
        repeat(2_000) { frame -> out = shelf.left(if (frame % 2 == 0) 8000.0 else -8000.0, coefficient, depth) }

        val pole = 1.0 - exp(-2.0 * PI * DistanceShelf.CORNER_HZ / rate)
        val settled = pole / (2.0 - pole)
        val cut = 1.0 - 10.0.pow(-DistanceShelf.FULL_SHELF_DECIBELS / 20.0)
        assertEquals(-8000.0 * (1.0 - cut * (1.0 - settled)), out, 1e-6)
        // And it is a cut, which is the half of it that must hold however the numbers are retuned.
        assertTrue("the top came back louder: $out", abs(out) < 8000.0)
    }

    /** How far off the source is, and how much of the top has gone, move together. */
    @Test
    fun theFurtherOffTheSourceTheMoreOfTheTopIsGone() {
        val depths = (0..10).map { DistanceShelf.depthFor(it / 10.0) }

        assertEquals(0.0, depths.first(), 0.0)
        for (step in 1 until depths.size) {
            assertTrue("step $step went the wrong way", depths[step] > depths[step - 1])
        }
        // Half the drag is half the decibels, which is what makes the travel even to an ear.
        assertEquals(
            1.0 - 10.0.pow(-DistanceShelf.FULL_SHELF_DECIBELS / 2.0 / 20.0),
            DistanceShelf.depthFor(0.5),
            1e-12
        )
    }

    /**
     * Two channels are two sounds. A shelf sharing one pole between them would mix the left into
     * the right at every frequency the pole is doing anything at, which on a wide mix is the image
     * collapsing inward the further the source is dragged - a defect that arrives dressed as the
     * feature working.
     */
    @Test
    fun theTwoChannelsRememberSeparately() {
        val shelf = DistanceShelf()
        val coefficient = DistanceShelf.coefficientFor(rate)
        val depth = DistanceShelf.depthFor(1.0)

        var mine = 0.0
        repeat(2_000) { frame ->
            mine = shelf.left(if (frame % 2 == 0) 8000.0 else -8000.0, coefficient, depth)
            shelf.right(0.0, coefficient, depth)
        }

        val alone = DistanceShelf()
        var undisturbed = 0.0
        repeat(2_000) { frame ->
            undisturbed = alone.left(if (frame % 2 == 0) 8000.0 else -8000.0, coefficient, depth)
        }

        assertEquals(undisturbed, mine, 0.0)
    }

    /**
     * Nothing this hands on is larger than what it was given. Said as a test rather than left to
     * the argument in the class comment, because the gains in this path multiply and the product
     * has been overlooked here before.
     */
    @Test
    fun nothingComesOutLargerThanItWentIn() {
        val shelf = DistanceShelf()
        val coefficient = DistanceShelf.coefficientFor(rate)
        val random = java.util.Random(4)

        for (step in 0..10) {
            val depth = DistanceShelf.depthFor(step / 10.0)
            repeat(5_000) {
                val sample = random.nextDouble() * 65534.0 - 32767.0
                assertTrue(abs(shelf.left(sample, coefficient, depth)) <= 32767.0)
                assertTrue(abs(shelf.right(sample, coefficient, depth)) <= 32767.0)
            }
        }
    }
}
