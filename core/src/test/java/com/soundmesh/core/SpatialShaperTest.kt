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
     * What this class interpolates for. A gain held constant across a chunk and stepped at its
     * edge puts a discontinuity into the music every 20 ms, at a fixed 50 Hz. At the default six
     * second period that discontinuity was measured at 1.64% of full scale at its worst, and was
     * not audible to a listener looking for it - so this test does not guard an audible fault
     * today. It guards the shape of the code that keeps the fault proportional to the source's
     * speed, which is the only reason a faster circuit stays safe.
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

    /**
     * One handset straight ahead under [SpatialMode.SPLIT] carries both sides equally, and the
     * whole-room normalisation then scales it to exactly unity - so what comes back out is the
     * fold on its own, with no placement gain mixed into the arithmetic being checked.
     */
    private fun soloField(separation: Double, sides: Set<String> = emptySet()) = SpatialField(
        mode = SpatialMode.SPLIT,
        layout = SpatialLayout(listOf(SpatialPosition("solo", 0.0, 1.0))),
        separation = separation,
        sideIds = sides
    )

    /** A chunk whose two channels carry different constants, so a fold is visible in the output. */
    private fun steadyPair(left: Int, right: Int, frames: Int = framesPerChunk): ByteArray {
        val pcm = ByteArray(frames * 4)
        for (frame in 0 until frames) {
            val at = frame * 4
            pcm[at] = (left and 0xFF).toByte()
            pcm[at + 1] = (left shr 8).toByte()
            pcm[at + 2] = (right and 0xFF).toByte()
            pcm[at + 3] = (right shr 8).toByte()
        }
        return pcm
    }

    /** What both channels agree about, on both channels of the handset carrying it. */
    @Test
    fun aHandsetCarryingTheMiddlePlaysWhatTheTwoChannelsShare() {
        val shaped = SpatialShaper.shape(steadyPair(10_000, 4_000), soloField(1.0), "solo", 0L, sampleRate)

        assertEquals(7_000, leftChannel(shaped)[0])
        assertEquals(7_000, rightChannel(shaped)[0])
    }

    /** What they disagree about, and the two channels of it are opposites - that is what a side is. */
    @Test
    fun aHandsetCarryingTheSidesPlaysWhatTheTwoChannelsDisagreeAbout() {
        val field = soloField(1.0, setOf("solo"))

        val shaped = SpatialShaper.shape(steadyPair(10_000, 4_000), field, "solo", 0L, sampleRate)

        assertEquals(3_000, leftChannel(shaped)[0])
        assertEquals(-3_000, rightChannel(shaped)[0])
    }

    /**
     * The limit this effect has to be honest about. Anything panned to the centre lives in both
     * channels identically, so the sides of it are nothing at all - and a mono recording is that
     * case for the whole song. A handset given the sides of mono material is silent, not quiet.
     *
     * This is why the rule is "everything sitting in the middle" and never "the vocal": the kick
     * and the bass usually sit there too, and they leave with it.
     */
    @Test
    fun theSidesOfMaterialTheChannelsAgreeOnAreSilent() {
        val field = soloField(1.0, setOf("solo"))

        val shaped = SpatialShaper.shape(steady(12_000), field, "solo", 0L, sampleRate)

        assertArrayEquals(ByteArray(shaped.size), shaped)
    }

    /**
     * The two parts are a decomposition, not two effects that merely sound different: played
     * together they are the mix that was sent, sample for sample. A rule that failed this would be
     * throwing part of the song away, and nothing else here would notice.
     */
    @Test
    fun theMiddleAndTheSidesAddBackUpToWhatWasSent() {
        val pcm = steadyPair(10_000, 4_000)

        val middle = SpatialShaper.shape(pcm, soloField(1.0), "solo", 0L, sampleRate)
        val sides = SpatialShaper.shape(pcm, soloField(1.0, setOf("solo")), "solo", 0L, sampleRate)

        assertEquals(10_000, leftChannel(middle)[0] + leftChannel(sides)[0])
        assertEquals(4_000, rightChannel(middle)[0] + rightChannel(sides)[0])
    }

    /** The knob at zero has to leave the samples exactly as they were, or it is not a knob. */
    @Test
    fun aKnobAtZeroPassesBothChannelsThrough() {
        val pcm = steadyPair(10_000, 4_000)

        val shaped = SpatialShaper.shape(pcm, soloField(0.0, setOf("solo")), "solo", 0L, sampleRate)

        assertArrayEquals(pcm, shaped)
    }

    /**
     * The knob has the same edge the gain had, and for the same reason. Dragging it publishes a new
     * rule several times a second, and each one moves the fold - so a fold that were held for a whole
     * chunk would step at every chunk edge while a finger is down. The step is worth up to half of the
     * other channel, which is the class of thing a listener already reported hearing once
     * (the gain arriving in one jump, fixed separately), not the class measured as inaudible.
     *
     * Checked as arithmetic rather than as a shape: at the halfway frame the fold must be halfway,
     * which for these two channels is one exact sample value and nothing else.
     */
    @Test
    fun theFoldRampsThroughAChunkRatherThanSteppingAtItsEdge() {
        val pcm = steadyPair(10_000, 4_000)

        val shaped = SpatialShaper.shape(
            pcm, soloField(1.0), "solo", 0L, sampleRate, fromFold = 0.0
        )

        val left = leftChannel(shaped)
        assertEquals("the first frame is where the previous chunk left off", 10_000, left.first())
        // Halfway to a fold of 0.5: 0.75 of its own channel and 0.25 of the other.
        assertEquals("the fold did not move across the chunk", 8_500, left[framesPerChunk / 2])
    }

    /** A handset already under this same rule has nothing to ramp from, and must not invent one. */
    @Test
    fun aFoldThatDidNotChangeIsHeldFlatAcrossTheChunk() {
        val shaped = SpatialShaper.shape(steadyPair(10_000, 4_000), soloField(1.0), "solo", 0L, sampleRate)

        val left = leftChannel(shaped)
        assertEquals(7_000, left.first())
        assertEquals(7_000, left.last())
    }
}
