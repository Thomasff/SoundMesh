package com.soundmesh.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class HeardLoudnessTest {
    /**
     * What the engine is playing now was handed over a buffer ago, so the answer is the level of
     * the stretch that holds that frame - not the one just written, which is heard 200 ms later.
     */
    @Test
    fun theLevelIsTheOneOfTheStretchBeingPlayedNotTheOneJustWritten() {
        val trail = HeardLoudness()
        trail.wrote(0, 480, 0.1f)
        trail.wrote(480, 480, 0.5f)
        trail.wrote(960, 480, 0.9f)
        assertEquals(0.1f, trail.at(0), 0f)
        assertEquals(0.1f, trail.at(479), 0f)
        assertEquals(0.5f, trail.at(480), 0f)
        assertEquals(0.9f, trail.at(1439), 0f)
    }

    /** Before anything was written, and past what has been, nothing is sounding. */
    @Test
    fun framesNobodyWroteAreSilent() {
        val trail = HeardLoudness()
        assertEquals(0f, trail.at(0), 0f)
        trail.wrote(1000, 480, 0.7f)
        assertEquals(0f, trail.at(999), 0f)
        assertEquals(0f, trail.at(1480), 0f)
    }

    /** It keeps a fixed number of stretches, well over a buffer's worth, and forgets the oldest. */
    @Test
    fun onlyTheLatestStretchesAreKept() {
        val trail = HeardLoudness()
        for (k in 0 until HeardLoudness.KEPT + 5) trail.wrote(k * 100L, 100, k.toFloat())
        assertEquals(0f, trail.at(0), 0f)
        assertEquals((HeardLoudness.KEPT + 4).toFloat(), trail.at((HeardLoudness.KEPT + 4) * 100L + 50), 0f)
        assertEquals(5f, trail.at(5 * 100L), 0f)
    }

    /** The handset's loudnessOf: RMS over every sample, as a share of full scale. */
    @Test
    fun theLevelIsTheRootMeanSquareOfTheSamplesAsAShareOfFullScale() {
        val squares = 4 * 16384.0 * 16384.0
        assertEquals(0.5f, HeardLoudness.levelOf(squares, 4), 0.001f)
        assertEquals(0f, HeardLoudness.levelOf(0.0, 0), 0f)
        assertEquals(1f, HeardLoudness.levelOf(4 * 65536.0 * 65536.0, 4), 0f)
    }
}
