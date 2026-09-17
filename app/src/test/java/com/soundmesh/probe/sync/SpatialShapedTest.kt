package com.soundmesh.probe.sync

import com.soundmesh.core.Crossover
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import com.soundmesh.core.SplitAxis
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gain law itself is core's, and tested there. What is decided here is which chunks it is
 * allowed near, which is the part that can quietly break a measurement rather than a listen.
 */
class SpatialShapedTest {
    /** Hard over to the right, so anything actually shaped for "left" comes back silent. */
    private val hardRight = SpatialField(
        SpatialMode.PAN,
        SpatialLayout(listOf(SpatialPosition("left", -1.0, 0.0), SpatialPosition("right", 1.0, 0.0))),
        pan = 1.0
    )

    private fun steady(level: Int = 12_000, frames: Int = SyncRenderer.FRAMES_PER_CHUNK): ByteArray {
        val pcm = ByteArray(frames * SyncRenderer.CHANNELS * 2)
        for (index in 0 until pcm.size / 2) {
            pcm[index * 2] = (level and 0xFF).toByte()
            pcm[index * 2 + 1] = (level shr 8).toByte()
        }
        return pcm
    }

    @Test
    fun aChirpChunkIsNeverTouched() {
        val pcm = steady()

        val shaped = spatialShaped(SyncRenderer.CHIRP_SEQUENCE_BASE, 0L, pcm, hardRight, "left", crossover = Crossover())

        assertSame(pcm, shaped)
    }

    @Test
    fun aChirpRepeatIsNeverTouchedEither() {
        val pcm = steady()
        val laterRepeat = SyncRenderer.CHIRP_SEQUENCE_BASE + 3 * SyncRenderer.CHIRP_REPEAT_STRIDE

        assertSame(pcm, spatialShaped(laterRepeat, 0L, pcm, hardRight, "left", crossover = Crossover()))
    }

    /**
     * `wasUnder` names the rule the previous chunk was heard under, and passing this same one is
     * what makes this the steady state rather than the moment the rule arrived. Left out, the first
     * chunk ramps down from unity instead of being silent throughout - which is the point of
     * [theFirstRuleIsRampedIntoRatherThanSteppedInto] and would make the assertion below false for
     * the right reason.
     */
    @Test
    fun aStreamedChunkIsShaped() {
        val pcm = steady()

        val shaped = spatialShaped(5, 0L, pcm, hardRight, "left", wasUnder = hardRight, crossover = Crossover())

        assertFalse("the rule was not applied", shaped.contentEquals(pcm))
        assertArrayEquals(ByteArray(pcm.size), shaped)
    }

    @Test
    fun withNoRuleTheChunkGoesOutAsItCame() {
        val pcm = steady()

        assertSame(pcm, spatialShaped(5, 0L, pcm, null, "left", crossover = Crossover()))
    }

    @Test
    fun beforeThisHandsetKnowsItsOwnNameTheChunkGoesOutAsItCame() {
        val pcm = steady()

        assertSame(pcm, spatialShaped(5, 0L, pcm, hardRight, null, crossover = Crossover()))
    }

    /**
     * A handset missing from the drawing plays flat rather than silent. Absence means a stale rule
     * or a bug, and the two answers are not symmetric: playing on is the room behaving as it did
     * before spatial audio existed, while going silent is a handset dropping out of a room the
     * listener is still looking at, with nothing on screen saying why.
     */
    @Test
    fun aHandsetTheDrawingDoesNotNameIsLeftAlone() {
        val pcm = steady()

        assertSame(pcm, spatialShaped(5, 0L, pcm, hardRight, "someone-else", crossover = Crossover()))
    }
    private fun firstLeftSample(pcm: ByteArray): Int =
        ((pcm[0].toInt() and 0xFF) or (pcm[1].toInt() shl 8)).toShort().toInt()

    /**
     * The step a listener heard in the first second of a session.
     *
     * A handset plays unshaped - at unity - until the first rule reaches it, and the rule then
     * arrives mid-song and takes effect on a chunk boundary. Evaluated straight, that chunk starts
     * at whatever the rule says, so the gain jumps the whole way in one sample: up to unity, which
     * is sixty times the 1.64% a chunk edge is worth and the one step in this class the room can
     * actually hear.
     *
     * `hardRight` silences "left" completely, so the first sample of the first shaped chunk is the
     * whole question: near the sample it came in as means it ramped away from unity, near zero
     * means it stepped.
     */
    @Test
    fun theFirstRuleIsRampedIntoRatherThanSteppedInto() {
        val pcm = steady()

        val shaped = spatialShaped(0, 0L, pcm, hardRight, "left", wasUnder = null, crossover = Crossover())

        val arrived = firstLeftSample(pcm)
        val left = firstLeftSample(shaped)
        assertTrue("the first frame stepped to $left rather than starting at $arrived", left > arrived - 40)
        // And it does get there: the ramp is a ramp, not a rule that failed to apply.
        val last = ((shaped[shaped.size - 4].toInt() and 0xFF) or (shaped[shaped.size - 3].toInt() shl 8)).toShort().toInt()
        assertTrue("the ramp never arrived; it ended at $last", last < arrived / 4)
    }

    /**
     * The other half, and the reason this is not simply "always start at unity": once a rule is in
     * force every chunk continues the one before it, and starting each of them at unity would put
     * back the 50 Hz edge the interpolation exists to remove - at full depth.
     */
    @Test
    fun aChunkAlreadyUnderThisRuleStartsWhereTheRuleSaysNotAtUnity() {
        val pcm = steady()

        val shaped = spatialShaped(0, 0L, pcm, hardRight, "left", wasUnder = hardRight, crossover = Crossover())

        assertArrayEquals(ByteArray(shaped.size), shaped)
    }

    /**
     * Dragging an icon publishes a new rule several times a second, and each one is a different
     * object. Ramping from the rule that was actually heard - not from unity - is what keeps a drag
     * sounding like a movement rather than like a stutter.
     */
    @Test
    fun aReplacedRuleIsRampedAwayFromTheOneThatWasHeard() {
        val pcm = steady()
        // Straight ahead, which is 1/sqrt(2) to both handsets - chosen because it is neither of
        // the two answers a wrong implementation gives. Stepping into the new rule starts this
        // chunk at 0; ramping from unity starts it at 12000; ramping from the rule that was
        // actually heard starts it at 12000/sqrt(2). Hard left would not separate the last two:
        // it gives this handset a gain of exactly 1.
        val centred = SpatialField(
            SpatialMode.PAN,
            SpatialLayout(listOf(SpatialPosition("left", -1.0, 0.0), SpatialPosition("right", 1.0, 0.0))),
            pan = 0.0
        )

        val shaped = spatialShaped(0, 0L, pcm, hardRight, "left", wasUnder = centred, crossover = Crossover())

        val left = firstLeftSample(shaped)
        assertTrue("it started at $left, not where the old rule left it (~8485)", left in 8_300..8_700)
    }

    /**
     * One handset straight ahead under [SpatialMode.SPLIT] carries both sides equally and the
     * whole-room normalisation scales it to unity, so what comes back is the fold with no placement
     * gain folded into the arithmetic.
     */
    /**
     * A rule waits for the instant it was stamped with, so every handset swaps on the same chunk.
     *
     * The gain has never needed this - it is a function of the host instant, so two handsets
     * evaluating it at the same instant agree whenever they happen to have been told. The fold
     * and the spectrum are not: they step when a message lands, and two handsets are told a few
     * milliseconds apart. Measured 09-11 on the pair that splits a mix by frequency: one chunk of
     * disagreement over a single large change - the first rule of a session, a knob thrown across
     * its range - leaves the two halves summing to 10-14 dB under the music instead of to the
     * music. Over the small steps a finger makes while dragging it is -39 dB, which is the
     * territory a listener could not hear a 09-09 chunk edge in.
     */
    @Test
    fun aRuleWaitsForTheInstantItWasStampedWith() {
        val was = solo(0.0)
        val next = solo(1.0).copy(effectiveAtHostNanos = 5_000L)

        // Early: the chunk is heard before the instant, so it plays under the rule in force.
        assertSame(was, ruleInForce(was, next, playAtHostNanos = 4_999L))
        // And on the instant, and after it.
        assertSame(next, ruleInForce(was, next, playAtHostNanos = 5_000L))
        assertSame(next, ruleInForce(was, next, playAtHostNanos = 6_000L))
    }

    /**
     * An unstamped rule applies at once, which is what a handset that has fallen behind gets and
     * what every rule got before this existed. Late is the failure this degrades to, and late is
     * exactly as good as it was before - never worse.
     */
    @Test
    fun aRuleWithNoInstantOnItAppliesAtOnce() {
        val next = solo(1.0)

        assertSame(next, ruleInForce(solo(0.0), next, playAtHostNanos = 0L))
        // Nothing waiting leaves the room where it is, including a room under no rule at all.
        assertSame(next, ruleInForce(next, null, playAtHostNanos = 0L))
        assertEquals(null, ruleInForce(null, null, playAtHostNanos = 0L))
    }

    private fun solo(separation: Double) = SpatialField(
        SpatialMode.SPLIT,
        SpatialLayout(listOf(SpatialPosition("solo", 0.0, 1.0))),
        separation = separation
    )

    private fun steadyPair(left: Int, right: Int): ByteArray {
        val pcm = ByteArray(SyncRenderer.FRAMES_PER_CHUNK * SyncRenderer.CHANNELS * 2)
        for (frame in 0 until SyncRenderer.FRAMES_PER_CHUNK) {
            val at = frame * 4
            pcm[at] = (left and 0xFF).toByte()
            pcm[at + 1] = (left shr 8).toByte()
            pcm[at + 2] = (right and 0xFF).toByte()
            pcm[at + 3] = (right shr 8).toByte()
        }
        return pcm
    }

    private fun leftSampleAt(pcm: ByteArray, frame: Int): Int {
        val at = frame * 4
        return ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
    }

    /**
     * The separation knob is dragged, not switched, so a drag publishes a new rule several times a
     * second and each one moves the fold. The renderer knows what the previous chunk was heard under
     * and has to say so, exactly as it already does for the gain - otherwise every one of those
     * publications is a step at a chunk edge, worth up to half of the other channel.
     *
     * The two cannot share one argument: dragging an icon moves the gain and leaves the fold alone,
     * dragging the knob does the reverse, and this test is the second of those.
     */
    @Test
    fun aChangedSeparationIsRampedIntoRatherThanSteppedInto() {
        val pcm = steadyPair(10_000, 4_000)

        val shaped = spatialShaped(5, 0L, pcm, solo(1.0), "solo", wasUnder = solo(0.0), crossover = Crossover())

        assertEquals("the first frame is not where the previous chunk left off", 10_000, leftSampleAt(shaped, 0))
        assertEquals("the fold did not move across the chunk", 8_500, leftSampleAt(shaped, SyncRenderer.FRAMES_PER_CHUNK / 2))
    }

    /** A handset that has been playing unshaped was folding in none of the other channel, not half of it. */
    @Test
    fun aHandsetUnderNoRuleAtAllIsRampedInFromTheWholeMix() {
        val pcm = steadyPair(10_000, 4_000)

        val shaped = spatialShaped(5, 0L, pcm, solo(1.0), "solo", wasUnder = null, crossover = Crossover())

        assertEquals(10_000, leftSampleAt(shaped, 0))
    }

    /** The same solo room, dividing by frequency instead of by where a sound sits in the image. */
    private fun soloByFrequency(separation: Double) = SpatialField(
        SpatialMode.SPLIT,
        SpatialLayout(listOf(SpatialPosition("solo", 0.0, 1.0))),
        separation = separation,
        splitAxis = SplitAxis.LOW_HIGH,
        otherHalfIds = setOf("solo")
    )

    /**
     * The third control that a drag moves, and it needs saying separately from the other two for the
     * same reason they needed saying separately from each other: this knob leaves the gain and the
     * fold where they stand, so nothing else in the chunk reports that it moved.
     *
     * Read on the handset carrying the high half, where the arithmetic is the mix with a share of its
     * low half taken out - a steady level is all low half, so half a knob is half the level.
     */
    @Test
    fun aChangedSpectrumSplitIsRampedIntoRatherThanSteppedInto() {
        val pcm = steadyPair(10_000, 10_000)

        val shaped = spatialShaped(
            5, 0L, pcm, soloByFrequency(1.0), "solo",
            wasUnder = soloByFrequency(0.0), crossover = Crossover()
        )

        assertEquals("the first frame is not where the previous chunk left off", 10_000, leftSampleAt(shaped, 0))
        val midpoint = leftSampleAt(shaped, SyncRenderer.FRAMES_PER_CHUNK / 2)
        assertTrue("the split did not move across the chunk: $midpoint", midpoint in 4_700..5_300)
    }
}
