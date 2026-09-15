package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The law that says how far each handset holds its audio back, and how fast that is allowed to move.
 *
 * The parts worth asserting are the ones a listener cannot tell apart from the feature simply not
 * being very good. A wander whose handsets happen to line up sounds like a weak wander. A wander
 * steep enough for the slew limiter to clip it sounds like a wander, on every handset, except that
 * the handsets are no longer rendering the same one they were told - and nothing on screen says so.
 */
class SpatialDelayTest {
    private val rate = 48_000

    private fun roomOf(count: Int): SpatialLayout = SpatialLayout(
        (0 until count).map { SpatialPosition("p$it", (it - count / 2.0 + 0.5), 1.0) }
    )

    private val four = SpatialField(SpatialMode.SPLIT, roomOf(4))

    /** Neither knob up is no delay at all, and nothing carrying a line for it. */
    @Test
    fun `a rule with both knobs down asks nobody to wait`() {
        assertFalse(four.movesInTime)
        for (peerId in four.layout.peerIds) {
            assertEquals(0L, four.playbackDelayNanosFor(peerId, 1_000_000_000L))
        }
    }

    /** Either one up is a rule that needs somewhere to hold frames. See SpatialShaper's refusal. */
    @Test
    fun `either knob up is a rule that moves in time`() {
        assertTrue(four.copy(travelDelayNanos = 1_000_000L).movesInTime)
        assertTrue(four.copy(shimmerDelayNanos = 1_000_000L).movesInTime)
    }

    /**
     * The travel part is the placement rule read as a time: nothing where the source is, all of it
     * where the source is not.
     *
     * Checked against [SpatialField.gainAt] rather than against a second copy of the cosine, so
     * that the two cannot drift apart into a room whose loudest handset is not its earliest.
     */
    @Test
    fun `the handset the source faces waits for nothing and the opposite one waits longest`() {
        val depth = 12_000_000L
        // Two handsets, one hard left and one hard right, and a source pointed at the right one.
        val pair = SpatialField(
            SpatialMode.PAN,
            SpatialLayout(listOf(SpatialPosition("l", -1.0, 0.0), SpatialPosition("r", 1.0, 0.0))),
            pan = 1.0,
            travelDelayNanos = depth
        )
        assertEquals(0L, pair.playbackDelayNanosFor("r", 0L))
        assertEquals(depth, pair.playbackDelayNanosFor("l", 0L))
        assertTrue(pair.gainAt("r", 0L).left > pair.gainAt("l", 0L).left)
    }

    /** And there is nothing to travel between in a split, so the knob does nothing there. */
    @Test
    fun `a split asks nobody to wait however far the travel knob is wound`() {
        val split = four.copy(travelDelayNanos = SpatialField.MAX_TRAVEL_DELAY_NANOS)
        for (peerId in split.layout.peerIds) {
            assertEquals(0L, split.playbackDelayNanosFor(peerId, 3_000_000_000L))
        }
    }

    /**
     * No two handsets are ever doing the same thing, which is the whole of what the wander is for.
     *
     * A hash of the name would have given independent draws, and independent draws collide - two
     * handsets landing near the same phase wander together, and a room where that happened would
     * sound like the feature not working while a room where it did not would sound like it did.
     * Slots divide the cycle, so this holds for every room rather than for most of them.
     *
     * Measured over a cycle rather than at an instant, which is the shape this assertion has to
     * have and did not at first. Evenly spread phases put handsets on opposite sides of the same
     * sine, and opposite sides of a sine are equal twice a turn - so "no two agree right now" is
     * false for a room that is working perfectly, at two instants out of every cycle. Two handsets
     * crossing is not two handsets together. What being together would look like is the difference
     * between them staying near nothing, which is what this measures.
     */
    @Test
    fun `no two handsets wander together`() {
        val depth = SpatialField.MAX_SHIMMER_DELAY_NANOS
        val stepNanos = SpatialField.DEFAULT_SHIMMER_PERIOD_NANOS / 64
        for (count in 2..12) {
            val room = SpatialField(SpatialMode.SPLIT, roomOf(count), shimmerDelayNanos = depth)
            val tracks = room.layout.peerIds.map { peerId ->
                (0 until 64).map { room.playbackDelayNanosFor(peerId, it * stepNanos).toDouble() }
            }
            for (one in tracks.indices) {
                for (other in one + 1 until tracks.size) {
                    val apart = kotlin.math.sqrt(
                        tracks[one].indices.sumOf {
                            val gap = tracks[one][it] - tracks[other][it]
                            gap * gap
                        } / tracks[one].size
                    )
                    assertTrue(
                        "room of $count: $one and $other are $apart ns apart on average",
                        apart >= depth * TOGETHER
                    )
                }
            }
        }
    }

    /**
     * How far apart two handsets' wanders have to stay to count as different ones.
     *
     * A tenth of the depth. Not a taste threshold: two handsets given the same phase score exactly
     * zero, and the closest pair the slots can produce - a room of twelve, a twelfth of a turn
     * apart - scores about twice this. It is a gap wide enough that only a collision falls in it.
     */
    private val TOGETHER = 0.1

    /** It is a wander, not a silence and not a lead: never negative, never past its depth. */
    @Test
    fun `the wander stays between nothing and its depth`() {
        val depth = SpatialField.MAX_SHIMMER_DELAY_NANOS
        val room = four.copy(shimmerDelayNanos = depth)
        for (step in 0 until 20_000) {
            val at = room.playbackDelayNanosFor("p1", step * 1_000_000L)
            assertTrue("$at at step $step", at in 0L..depth)
        }
    }

    /**
     * The steepest wander any legal rule can ask for stays under the slew limit.
     *
     * The point of [SpatialField.MAX_SHIMMER_DELAY_NANOS] and
     * [SpatialField.SHORTEST_SHIMMER_PERIOD_NANOS] being capped at all, written as an assertion
     * rather than left in a comment - because the failure it prevents is quiet. Clipped by the
     * limiter, every handset still wanders and still sounds like it is working, but no longer by
     * the amount the rule told it to, so two handsets asked for the same thing render different
     * ones and the room's agreement - the thing all the clock work buys - is gone.
     *
     * Measured off the function over a whole cycle rather than derived from the formula, so that
     * a change to the shape of the wander is caught too and not only a change to its numbers.
     */
    @Test
    fun `no legal wander moves faster than the delay line may follow`() {
        val room = SpatialField(
            SpatialMode.SPLIT,
            // Enough handsets to reach the fastest rate slot, which is where the steepest is.
            roomOf(SpatialField.SHIMMER_RATE_SLOTS * 2),
            shimmerDelayNanos = SpatialField.MAX_SHIMMER_DELAY_NANOS,
            shimmerPeriodNanos = SpatialField.SHORTEST_SHIMMER_PERIOD_NANOS
        )
        val frameNanos = 1_000_000_000L / rate
        var steepest = 0.0
        for (peerId in room.layout.peerIds) {
            var before = TravellingDelay.samplesFor(room.playbackDelayNanosFor(peerId, 0L), rate)
            // One full cycle of the slowest slot covers every phase every slot passes through.
            for (frame in 1..(SpatialField.SHORTEST_SHIMMER_PERIOD_NANOS / frameNanos)) {
                val now = TravellingDelay.samplesFor(
                    room.playbackDelayNanosFor(peerId, frame * frameNanos),
                    rate
                )
                steepest = maxOf(steepest, abs(now - before))
                before = now
            }
        }
        assertTrue(
            "steepest wander $steepest frames per frame against a limit of " +
                "${TravellingDelay.MAX_SLEW_SAMPLES}",
            steepest < TravellingDelay.MAX_SLEW_SAMPLES
        )
    }

    /** And both knobs at once still fit in the line that has to hold them. */
    @Test
    fun `the longest delay the two knobs can ask for together fits in the line`() {
        val longest = SpatialField.MAX_TRAVEL_DELAY_NANOS + SpatialField.MAX_SHIMMER_DELAY_NANOS
        assertTrue(
            "$longest ns asked for, ${TravellingDelay.LONGEST_NANOS} ns held",
            longest <= TravellingDelay.LONGEST_NANOS
        )
    }

    /** A wander fast enough to be a vibrato is refused rather than clipped. */
    @Test(expected = IllegalArgumentException::class)
    fun `a wander faster than the floor is refused`() {
        four.copy(shimmerPeriodNanos = SpatialField.SHORTEST_SHIMMER_PERIOD_NANOS - 1)
    }

    /**
     * A circuit too fast for the delay line to follow gets a shallower sweep, not a clipped one.
     *
     * The distinction is the whole reason the cap is in the law rather than left to the limiter.
     * A limiter clips a trajectory, and two handsets sit at different points of the same
     * trajectory - so what they render after clipping is not one shape scaled down, it is two
     * different shapes, and the room stops agreeing about where the source is. A depth every
     * handset works out from the rule is a shape they all still agree on.
     */
    @Test
    fun `a circuit faster than the delay line can follow is given a shallower sweep`() {
        val room = SpatialField(
            SpatialMode.ROTATE,
            roomOf(4),
            periodNanos = 1_000_000_000L,
            travelDelayNanos = SpatialField.MAX_TRAVEL_DELAY_NANOS
        )
        val frameNanos = 1_000_000_000L / rate
        for (peerId in room.layout.peerIds) {
            var before = TravellingDelay.samplesFor(room.playbackDelayNanosFor(peerId, 0L), rate)
            for (frame in 1..(room.periodNanos / frameNanos)) {
                val now = TravellingDelay.samplesFor(
                    room.playbackDelayNanosFor(peerId, frame * frameNanos),
                    rate
                )
                assertTrue(
                    "$peerId moved ${now - before} frames in one frame",
                    abs(now - before) <= TravellingDelay.MAX_SLEW_SAMPLES
                )
                before = now
            }
        }
    }

    /** And at the circuit anybody actually uses, the cap does nothing: the knob means what it says. */
    @Test
    fun `at the default circuit the travel knob reaches its full depth`() {
        val room = SpatialField(
            SpatialMode.ROTATE,
            SpatialLayout(listOf(SpatialPosition("l", -1.0, 0.0), SpatialPosition("r", 1.0, 0.0))),
            travelDelayNanos = SpatialField.MAX_TRAVEL_DELAY_NANOS
        )
        val deepest = (0..2_000).maxOf { room.playbackDelayNanosFor("l", it * 3_000_000L) }
        assertEquals(SpatialField.MAX_TRAVEL_DELAY_NANOS, deepest)
    }
}
