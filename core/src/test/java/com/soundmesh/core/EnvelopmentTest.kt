package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * A handset the source has turned away from goes quiet rather than off.
 *
 * The raised cosine is exactly zero opposite a handset, so under a rotating source each handset in
 * the room falls completely silent once per revolution. A listener heard that on 09-11 and named it
 * better than any of the code did: "听起来是拿着一部手机在转" - one phone playing, then another,
 * rather than a sound travelling round a room. The null stays exactly where it was; it stops being
 * silence.
 */
class EnvelopmentTest {
    private val front = "a1b2c3d4e5f60718"
    private val behind = "0918273645abcdef"

    /** Straight ahead and straight behind, so the source faces one of them exactly. */
    private fun room(envelopment: Double) = SpatialField(
        SpatialMode.ROTATE,
        SpatialLayout(
            listOf(
                SpatialPosition(front, 0.0, 1.0),
                SpatialPosition(behind, 0.0, -1.0)
            )
        ),
        periodNanos = 4_000_000_000L,
        envelopment = envelopment
    )

    /** The instant the source is straight ahead, which is the epoch: azimuth runs from zero. */
    private val facingFront = 0L

    @Test
    fun `with none of it a handset the source faces away from is silent`() {
        val gain = room(0.0).gainAt(behind, facingFront)

        assertEquals(0.0, gain.left, 1e-9)
        assertEquals(0.0, gain.right, 1e-9)
    }

    @Test
    fun `with some of it that handset is quiet and still playing`() {
        val gain = room(0.25).gainAt(behind, facingFront)

        assertTrue("the far handset is at ${gain.left}", gain.left > 0.05)
        // And still clearly the quieter of the two, or there is no direction left to hear.
        assertTrue(gain.left < room(0.25).gainAt(front, facingFront).left / 2)
    }

    /**
     * The null does not move, which is the half of the listener's request that is easy to lose: they
     * asked for every handset to keep something **and** for the quietest direction to stay exactly
     * opposite the loudest. A floor does the first; a wider pattern would have done the first and
     * smeared the second.
     */
    @Test
    fun `the quietest direction is still exactly opposite the loudest`() {
        val field = room(0.25)
        val quarterTurn = 1_000_000_000L

        val ahead = field.gainAt(front, facingFront).left
        val side = field.gainAt(front, quarterTurn).left
        val away = field.gainAt(front, 2 * quarterTurn).left

        assertTrue("ahead $ahead, side $side, away $away", ahead > side)
        assertTrue("ahead $ahead, side $side, away $away", side > away)
    }

    /** Zero is the law exactly as it was written, so every room already tuned renders unchanged. */
    @Test
    fun `none of it is the law this replaced, to the last decimal`() {
        val field = room(0.0)
        for (at in 0L until 4_000_000_000L step 137_000_000L) {
            val weight = (1.0 + kotlin.math.cos(field.sourceAzimuthAt(at))) / 2.0
            val other = (1.0 + kotlin.math.cos(field.sourceAzimuthAt(at) - PI)) / 2.0
            val power = weight * weight + other * other
            if (power <= 0.0) continue
            assertEquals(weight / kotlin.math.sqrt(power), field.gainAt(front, at).left, 1e-12)
        }
    }

    /**
     * At the far end of the **slider** there is still a direction to hear.
     *
     * Half, written out rather than read from the rule, because the rule stopped stopping here on
     * 2026-09-18 - the dot on the map goes further. Half is where MOST_ENVELOPMENT_ON_A_SLIDER
     * holds the 包裹感 knob, and the reason is this assertion: the rotation is a source going
     * round a room, so a knob whose far end leaves no direction at all is a knob with the effect
     * switched off at the end of it.
     */
    @Test
    fun `at the far end of the slider the facing handset is still the louder one`() {
        val field = room(0.5)

        assertTrue(field.gainAt(front, facingFront).left > field.gainAt(behind, facingFront).left)
    }

    /**
     * At the far end of the **rule** there is no direction left, and that is what it is for.
     *
     * Only reachable by dragging the source dot onto the listener - a source in the same place as
     * the ear hearing it, which has no direction to be in. A listener asked for this on 09-18 and
     * described it better than the code did: 相当于在他脑子里放歌.
     *
     * Asserted as "the same as every other handset" rather than as a number, because the number is
     * whatever the room power normalisation lands on and the claim is about the difference.
     */
    @Test
    fun `wound all the way up there is no direction left at all`() {
        val field = room(SpatialField.MAX_ENVELOPMENT)

        assertEquals(
            field.gainAt(front, facingFront).left,
            field.gainAt(behind, facingFront).left,
            1e-12
        )
        // And it stays that way as the source goes round, which is what makes it a place rather
        // than an instant: a room that was flat only while the source pointed somewhere in
        // particular would still be a rotation, heard once per turn.
        for (at in 0L until 4_000_000_000L step 173_000_000L) {
            assertEquals(field.gainAt(front, at).left, field.gainAt(behind, at).left, 1e-12)
        }
    }

    /** The split has no source, so nothing here touches it. */
    @Test
    fun `the split is not a source and does not read this`() {
        val split = room(0.0).copy(mode = SpatialMode.SPLIT)
        val wide = room(SpatialField.MAX_ENVELOPMENT).copy(mode = SpatialMode.SPLIT)

        assertEquals(split.gainAt(front, facingFront), wide.gainAt(front, facingFront))
    }
}
