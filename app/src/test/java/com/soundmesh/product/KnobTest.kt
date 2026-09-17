package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a knob on the spatial panel does with a finger, and where it draws the dot afterwards.
 *
 * These replaced Material sliders, which did all of this themselves - so every case below is one
 * the screen used to get for free and now has to be right about on its own.
 */
class KnobTest {
    private val plain = 0f..1f

    /** Either side of zero, which is what the pan and the manual gap knobs are. */
    private val bothWays = -1f..1f

    @Test
    fun `a finger is read as a share of the track it is on`() {
        assertEquals(0f, knobValue(0f, 200f, plain), TOLERANCE)
        assertEquals(0.5f, knobValue(100f, 200f, plain), TOLERANCE)
        assertEquals(1f, knobValue(200f, 200f, plain), TOLERANCE)
    }

    /**
     * A horizontal drag goes on being reported after the finger has left the track. The volume
     * rows learnt this the expensive way - a drag past the right edge asked for 104% - and these
     * knobs feed a live audio rule rather than a volume somebody can set back.
     */
    @Test
    fun `a drag off either end stops at the end`() {
        assertEquals(1f, knobValue(360f, 200f, plain), TOLERANCE)
        assertEquals(0f, knobValue(-40f, 200f, plain), TOLERANCE)
    }

    /**
     * The one that the 0..1 arithmetic would get silently wrong. Two of these knobs run from minus
     * one to plus one, and on those the middle of the track is "neither way" rather than "half".
     */
    @Test
    fun `on a track that runs either side of zero, the middle is zero`() {
        assertEquals(-1f, knobValue(0f, 200f, bothWays), TOLERANCE)
        assertEquals(0f, knobValue(100f, 200f, bothWays), TOLERANCE)
        assertEquals(1f, knobValue(200f, 200f, bothWays), TOLERANCE)
    }

    /**
     * Stops count the ones BETWEEN the ends, the way Material's slider counted them, so the
     * three-stop knob still means five places a finger can land. Half a section of an
     * allpass chain is a comb filter - see DiffusionSlider - so landing between them is not a
     * finer setting, it is a different sound.
     */
    @Test
    fun `with stops, a finger lands on one of them and never between`() {
        assertEquals(0.5f, knobValue(120f, 200f, plain, steps = 3), TOLERANCE)
        assertEquals(0.75f, knobValue(140f, 200f, plain, steps = 3), TOLERANCE)
        // Both ends are stops as well, or the knob cannot reach all of what it controls.
        assertEquals(0f, knobValue(10f, 200f, plain, steps = 3), TOLERANCE)
        assertEquals(1f, knobValue(190f, 200f, plain, steps = 3), TOLERANCE)
    }

    /**
     * Where the dot goes, which is also where the filled part of the track starts: it grows from
     * wherever zero is, and on a range that never reaches zero that is simply the left end.
     */
    @Test
    fun `the drawing puts zero where zero is`() {
        assertEquals(0f, knobFraction(0f, plain), TOLERANCE)
        assertEquals(0.5f, knobFraction(0f, bothWays), TOLERANCE)
        assertEquals(0f, knobFraction(0f, 250f..4000f), TOLERANCE)
    }

    /** A value from outside the range still draws inside the track rather than off the end of it. */
    @Test
    fun `a value past the end draws at the end`() {
        assertEquals(100, knobPercent(3f, plain))
        assertEquals(0, knobPercent(-5f, plain))
    }

    @Test
    fun `the readout counts how far along the track the dot is`() {
        assertEquals(40, knobPercent(0.4f, plain))
        assertEquals(50, knobPercent(0f, bothWays))
    }

    private companion object {
        /** Floats off a division: near enough that no knob setting could be the difference. */
        const val TOLERANCE = 1e-5f
    }
}
