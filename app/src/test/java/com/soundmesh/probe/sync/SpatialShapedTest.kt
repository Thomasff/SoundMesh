package com.soundmesh.probe.sync

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun aStreamedChunkIsShaped() {
        val pcm = steady()

        val shaped = spatialShaped(5, 0L, pcm, hardRight, "left")

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
}
