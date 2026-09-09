package com.soundmesh.probe.sync

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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

    /**
     * The one that matters. The chirp is the instrument every alignment number is measured with,
     * and a gain on it changes the correlation peak, the ratios between the handsets, and the
     * verdict - while sounding exactly like a working room. Same rule as the trim deadband and the
     * splice fade, both of which already step around the chirp band for the same reason.
     */
    @Test
    fun aChirpChunkIsNeverTouched() {
        val pcm = steady()

        val shaped = spatialShaped(SyncRenderer.CHIRP_SEQUENCE_BASE, 0L, pcm, hardRight, "left")

        assertSame(pcm, shaped)
    }

    @Test
    fun aChirpRepeatIsNeverTouchedEither() {
        val pcm = steady()
        val laterRepeat = SyncRenderer.CHIRP_SEQUENCE_BASE + 3 * SyncRenderer.CHIRP_REPEAT_STRIDE

        assertSame(pcm, spatialShaped(laterRepeat, 0L, pcm, hardRight, "left"))
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

        val shaped = spatialShaped(5, 0L, pcm, hardRight, "left", wasUnder = hardRight)

        assertFalse("the rule was not applied", shaped.contentEquals(pcm))
        assertArrayEquals(ByteArray(pcm.size), shaped)
    }

    @Test
    fun withNoRuleTheChunkGoesOutAsItCame() {
        val pcm = steady()

        assertSame(pcm, spatialShaped(5, 0L, pcm, null, "left"))
    }

    @Test
    fun beforeThisHandsetKnowsItsOwnNameTheChunkGoesOutAsItCame() {
        val pcm = steady()

        assertSame(pcm, spatialShaped(5, 0L, pcm, hardRight, null))
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

        assertSame(pcm, spatialShaped(5, 0L, pcm, hardRight, "someone-else"))
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

        val shaped = spatialShaped(0, 0L, pcm, hardRight, "left", wasUnder = null)

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

        val shaped = spatialShaped(0, 0L, pcm, hardRight, "left", wasUnder = hardRight)

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

        val shaped = spatialShaped(0, 0L, pcm, hardRight, "left", wasUnder = centred)

        val left = firstLeftSample(shaped)
        assertTrue("it started at $left, not where the old rule left it (~8485)", left in 8_300..8_700)
    }
}
