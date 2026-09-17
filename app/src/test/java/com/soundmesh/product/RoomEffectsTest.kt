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
        separation = effect.settings.separation,
        splitAxis = effect.settings.axis,
        envelopment = effect.settings.envelopment
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

    /**
     * A room somebody tuned by hand has no name, and is told so. Highlighting the nearest entry
     * would say their change did not take.
     */
    @Test
    fun `a room tuned by hand matches nothing`() {
        assertNull(
            effectOf(
                RoomState(
                    mode = SpatialMode.SPLIT,
                    separation = 0.4f,
                    splitAxis = SplitAxis.MIDDLE_SIDES
                )
            )
        )
    }

    /**
     * The settings nobody can hear do not count. An envelopment left over from 绕着转 is still in
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
            RoomEffect.STEREO,
            effectOf(RoomState(mode = SpatialMode.SPLIT, splitAxis = SplitAxis.LOW_HIGH))
        )
    }
}
