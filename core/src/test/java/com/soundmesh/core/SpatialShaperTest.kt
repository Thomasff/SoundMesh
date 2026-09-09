package com.soundmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpatialShaperTest {
    private val sampleRate = 48000
    private val framesPerChunk = 960

    private fun facingPair() = SpatialLayout(
        listOf(SpatialPosition("left", -1.0, 0.0), SpatialPosition("right", 1.0, 0.0))
    )

    /** A chunk of constant amplitude, so the shaped result reads back as the gain envelope. */
    private fun steady(level: Int, frames: Int = framesPerChunk): ByteArray {
        val pcm = ByteArray(frames * 4)
        for (index in 0 until frames * 2) {
            pcm[index * 2] = (level and 0xFF).toByte()
            pcm[index * 2 + 1] = (level shr 8).toByte()
        }
        return pcm
    }

    private fun sampleAt(pcm: ByteArray, at: Int): Int =
        ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()

    private fun leftChannel(pcm: ByteArray): IntArray =
        IntArray(pcm.size / 4) { sampleAt(pcm, it * 4) }

    private fun rightChannel(pcm: ByteArray): IntArray =
        IntArray(pcm.size / 4) { sampleAt(pcm, it * 4 + 2) }

    @Test
    fun aHandsetTheSourceHasLeftBehindGoesQuiet() {
        val field = SpatialField(SpatialMode.PAN, facingPair(), pan = 1.0)

        val shaped = SpatialShaper.shape(steady(12_000), field, "left", 0L, sampleRate)

        assertArrayEquals(ByteArray(shaped.size), shaped)
    }

    /**
     * The reason this class interpolates at all. A gain held constant across a chunk and stepped
     * at its edge puts a discontinuity into the music every 20 ms - inaudible as a change in
     * loudness, audible as a tick, and the ticks arrive at a fixed 50 Hz, which is exactly the
     * kind of artefact a listener reports as a buzz rather than as a fault in the panning.
     *
     * So the property is not "the gain moves" but "the join between two chunks is no coarser than
     * the ramp inside one". A stepped implementation has no ramp inside a chunk at all, which
     * makes the second assertion fail and the first one vacuous - both are here.
     */
    @Test
    fun theGainRampsThroughAChunkRatherThanSteppingAtItsEdge() {
        val field = SpatialField(SpatialMode.ROTATE, facingPair())
        val chunkNanos = framesPerChunk * 1_000_000_000L / sampleRate

        val first = leftChannel(SpatialShaper.shape(steady(10_000), field, "left", 0L, sampleRate))
        val next = leftChannel(SpatialShaper.shape(steady(10_000), field, "left", chunkNanos, sampleRate))

        val moved = abs(first.first() - first.last())
        assertTrue("the gain did not move across the chunk at all ($moved)", moved >= 20)
        var inside = 0
        for (frame in 1 until first.size) inside = maxOf(inside, abs(first[frame] - first[frame - 1]))
        val join = abs(next.first() - first.last())
        // Plus one: both sides are rounded to whole 16-bit samples, and the ramp's own step here
        // is a fraction of a count. Without interpolation the join is the whole chunk's change.
        assertTrue("the join steps by $join where the ramp steps by $inside", join <= inside + 1)
    }

    @Test
    fun eachChannelIsScaledOnItsOwn() {
        val field = SpatialField(SpatialMode.SPLIT, facingPair())

        val shaped = SpatialShaper.shape(steady(10_000), field, "right", 0L, sampleRate)

        assertEquals(0, leftChannel(shaped).max())
        assertTrue("the right handset lost its own side", rightChannel(shaped).min() > 9_000)
    }

    /**
     * The chunk handed in is the one the session is holding: on a run with no pending frame
     * adjustment the renderer passes the AudioChunk's own array straight through, and a shaper
     * that wrote into it would leave the room's audio permanently panned wherever it happened to
     * be pointing when the chunk went past.
     */
    @Test
    fun theChunkItWasGivenIsLeftAsItWas() {
        val field = SpatialField(SpatialMode.PAN, facingPair(), pan = 1.0)
        val pcm = steady(12_000)
        val before = pcm.copyOf()

        SpatialShaper.shape(pcm, field, "left", 0L, sampleRate)

        assertArrayEquals(before, pcm)
    }

    /**
     * Whole-room power normalisation can ask for more than unity: one handset carrying a whole
     * side by itself is asked for sqrt(2). A 16-bit sample that overflows does not get louder, it
     * changes sign - the loudest defect available - so it clamps.
     */
    @Test
    fun aGainAboveOneClampsRatherThanWrapping() {
        val solo = SpatialLayout(listOf(SpatialPosition("solo", 1.0, 0.0)))
        val field = SpatialField(SpatialMode.SPLIT, solo)

        val shaped = SpatialShaper.shape(steady(30_000), field, "solo", 0L, sampleRate)

        assertEquals(Short.MAX_VALUE.toInt(), rightChannel(shaped).max())
        assertTrue("a clamp must not wrap", rightChannel(shaped).min() > 0)
    }

    @Test
    fun aChunkThatIsNotWholeStereoFramesIsRefused() {
        val field = SpatialField(SpatialMode.ROTATE, facingPair())

        val thrown = runCatching { SpatialShaper.shape(ByteArray(6), field, "left", 0L, sampleRate) }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun anUnknownHandsetIsRefusedRatherThanSilenced() {
        val field = SpatialField(SpatialMode.ROTATE, facingPair())

        val thrown = runCatching { SpatialShaper.shape(steady(10_000), field, "absent", 0L, sampleRate) }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }
}
