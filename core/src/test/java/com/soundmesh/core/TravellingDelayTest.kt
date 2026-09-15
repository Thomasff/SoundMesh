package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The delay line both moving-delay features are built on.
 *
 * Worth testing rather than listening to, unusually for anything in this corner of the project,
 * because a delay line has exact answers: a delay of zero is the identity, a whole number of
 * frames is a shift, and one between two frames is a stated interpolation. Everything about how
 * it *sounds* is a person in a room; everything here is arithmetic that can be wrong silently -
 * an off-by-one in a ring buffer is a few samples of the wrong thing, which against music reads
 * as the effect being subtle rather than as the code being broken.
 */
class TravellingDelayTest {
    private val rate = 48_000

    /** A recognisable signal: every frame's value says which frame it is. */
    private fun rampInto(line: TravellingDelay, frames: Int, wanted: Double): DoubleArray {
        val out = DoubleArray(frames)
        for (frame in 0 until frames) {
            line.step(frame.toDouble(), -frame.toDouble(), wanted)
            out[frame] = line.left
        }
        return out
    }

    /**
     * Off costs nothing and changes nothing, exactly.
     *
     * The case that runs on every handset of every session the moment either feature has ever been
     * switched on, because the line is kept and fed at a delay of zero rather than dropped. If
     * that path were lossy - an interpolation between a frame and its neighbour, a rounding - then
     * turning the knob on and off again would not return the room to where it was.
     */
    @Test
    fun `a delay of nothing hands every frame straight back`() {
        val line = TravellingDelay(rate)
        val out = rampInto(line, 500, 0.0)
        for (frame in out.indices) assertEquals(frame.toDouble(), out[frame], 0.0)
        assertEquals(0.0, line.heldSamples, 0.0)
    }

    /** And once it has been allowed to get there, a whole number of frames is exactly a shift. */
    @Test
    fun `a settled whole delay is the signal moved by that many frames`() {
        val line = TravellingDelay(rate)
        val want = 8.0
        // Long enough for the slew limit to have carried it all the way: 8 frames at 1/64 each.
        val out = rampInto(line, 2_000, want)
        assertEquals(want, line.heldSamples, 1e-9)
        for (frame in 1_000 until out.size) {
            assertEquals(frame.toDouble() - want, out[frame], 1e-9)
        }
    }

    /** Between two frames it is the straight line between them, which is what [TravellingDelay.step] claims. */
    @Test
    fun `a fractional delay is the line between the two frames it falls between`() {
        val line = TravellingDelay(rate)
        val want = 8.25
        val out = rampInto(line, 2_000, want)
        assertEquals(want, line.heldSamples, 1e-9)
        assertEquals(1_500.0 - want, out[1_500], 1e-9)
    }

    /**
     * The limit that makes every other claim here safe.
     *
     * Asked for the longest delay it can hold, from zero, on the first frame - which is the shape
     * of a rule arriving or a mode switching. Without the limit that is a step of milliseconds in
     * one frame, which is a click; with it the delay creeps and the click is a glide.
     */
    @Test
    fun `however far the delay is thrown it moves by at most the slew limit per frame`() {
        val line = TravellingDelay(rate)
        var before = 0.0
        for (frame in 0 until 5_000) {
            line.step(1.0, 1.0, if (frame < 2_500) line.longestSamples else 0.0)
            assertTrue(
                "moved ${abs(line.heldSamples - before)} at frame $frame",
                abs(line.heldSamples - before) <= TravellingDelay.MAX_SLEW_SAMPLES + 1e-12
            )
            before = line.heldSamples
        }
    }

    /**
     * And the limit is also why there is no priming case.
     *
     * A fresh line holds silence, so a delay it has not been fed enough frames to satisfy would
     * read that silence back - a dropout at the start of every session that turned this on. It
     * cannot happen, and not because anything checks: rising by at most 1/64 of a frame per frame,
     * the delay after n frames is at most n/64, which is always inside the n frames written. Fed a
     * constant, every frame out is that constant from the very first one.
     */
    @Test
    fun `a fresh line asked for its longest delay never reads the silence it starts full of`() {
        val line = TravellingDelay(rate)
        for (frame in 0 until 4_000) {
            line.step(1.0, 1.0, line.longestSamples)
            assertEquals("silence at frame $frame", 1.0, line.left, 1e-9)
            assertEquals(1.0, line.right, 1e-9)
        }
    }

    /** More than it can hold is clamped to what it can, not an index off the end of the buffer. */
    @Test
    fun `asking for more delay than the line holds is clamped`() {
        val line = TravellingDelay(rate)
        rampInto(line, 200_000, line.longestSamples * 4)
        assertEquals(line.longestSamples, line.heldSamples, 1e-9)
    }

    /** The two channels are two lines, not one read twice. */
    @Test
    fun `the channels are delayed independently of each other`() {
        val line = TravellingDelay(rate)
        rampInto(line, 2_000, 8.0)
        assertEquals(-(1_999.0 - 8.0), line.right, 1e-9)
    }
}
