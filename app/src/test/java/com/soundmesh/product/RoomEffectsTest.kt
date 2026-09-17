package com.soundmesh.product

import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The list of named results, read back off the settings it writes.
 *
 * Worth testing rather than eyeballing because the two directions are written separately: one
 * table says what an effect sets, and [effectOf] works out which effect a room is on. Two entries
 * that quietly produce the same room, or an entry nothing can read back, both look on screen like
 * a list that does not respond to being tapped.
 */
class RoomEffectsTest {
    /** The state an effect leaves behind, the way [SpatialPanel]'s apply leaves it. */
    private fun roomOn(effect: RoomEffect): RoomState = RoomState(
        mode = effect.settings.mode,
        envelopment = effect.settings.envelopment,
        reverb = effect.settings.reverb
    )

    @Test
    fun `every effect is recognised in the room it makes`() {
        for (effect in RoomEffect.entries) {
            assertEquals(effect, effectOf(roomOn(effect)))
        }
    }

    /**
     * And no two of them make the same room. A duplicate would be a row nobody could ever light
     * up - whichever one came second in the list would be unreachable, and tapping it would
     * highlight the other one.
     */
    @Test
    fun `no two effects settle on the same room`() {
        val rooms = RoomEffect.entries.map { effectOf(roomOn(it)) }
        assertEquals(RoomEffect.entries.size, rooms.toSet().size)
    }

    /** A room nobody has touched is the one the list opens on, not a room with no name. */
    @Test
    fun `a fresh room is playing the plainest thing on the list`() {
        assertEquals(RoomEffect.UNISON, effectOf(RoomState()))
    }

    /**
     * The two that stand still get no reverberation and the two that move a source get the number
     * a listener picked. That split is the 09-17 decision and it is the whole reason the knob left
     * the front of the panel: a room is worth having when there is somewhere to move in it.
     */
    @Test
    fun `only the effects that move a source bring a room with them`() {
        assertEquals(0f, RoomEffect.UNISON.settings.reverb)
        assertEquals(0f, RoomEffect.STEREO.settings.reverb)
        assertEquals(DEFAULT_REVERB, RoomEffect.SPIN.settings.reverb)
        assertEquals(DEFAULT_REVERB, RoomEffect.PLACE.settings.reverb)
    }

    /**
     * A room somebody tuned by hand has no name, and is told so. Highlighting the nearest entry
     * would say their change did not take - and the reverberation is exactly the knob somebody
     * goes into the fine tuning to move, so it has to be one of the things that counts.
     */
    @Test
    fun `a room tuned by hand matches nothing`() {
        assertNull(effectOf(RoomState(mode = SpatialMode.SPLIT, reverb = 0.5f)))
        assertNull(effectOf(RoomState(mode = SpatialMode.ROTATE, reverb = 0f)))
    }

    /**
     * The content split is not one of the things that counts, and that is the point of leaving it
     * out of [EffectSettings]: somebody who has told four phones which of them carry the voice,
     * and then taps 同步齐奏, has to see 同步齐奏 light up rather than 自定的设置.
     */
    @Test
    fun `how the song is divided up is not part of which effect this is`() {
        assertEquals(
            RoomEffect.STEREO,
            effectOf(
                RoomState(
                    mode = SpatialMode.SPLIT,
                    separation = 1f,
                    splitAxis = SplitAxis.LOW_HIGH,
                    otherHalfIds = setOf("aa")
                )
            )
        )
        assertEquals(
            RoomEffect.UNISON,
            effectOf(RoomState(mode = SpatialMode.UNISON, separation = 0.4f))
        )
    }

    /**
     * The settings nobody can hear do not count. An envelopment left over from 旋转 is still in
     * the state after a switch to a split, where no source ever turns away from anything - and if
     * it counted, the list would light up nothing at all after that switch.
     */
    @Test
    fun `a leftover setting that changes no sound is not a different effect`() {
        assertEquals(
            RoomEffect.STEREO,
            effectOf(RoomState(mode = SpatialMode.SPLIT, envelopment = DEFAULT_ENVELOPMENT))
        )
        assertEquals(
            RoomEffect.UNISON,
            effectOf(RoomState(mode = SpatialMode.UNISON, envelopment = DEFAULT_ENVELOPMENT))
        )
    }
}
