package com.soundmesh.product

import com.soundmesh.core.SpatialMode
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectSettingsTest {
    /** Choosing an effect undoes the last one's knobs, so a room never carries half of each. */
    @Test
    fun anEffectSetsEveryKnobItNames() {
        val spun = RoomState().withEffect(EffectKind.SPIN)
        assertEquals(SpatialMode.ROTATE, spun.mode)
        assertEquals(DEFAULT_REVERB, spun.reverb)
        assertEquals(DEFAULT_ENVELOPMENT, spun.envelopment)

        val back = spun.withEffect(EffectKind.UNISON)
        assertEquals(SpatialMode.UNISON, back.mode)
        assertEquals(0f, back.reverb)
    }

    /**
     * What an effect does not name survives it: where the dot was dragged, and which devices carry
     * which half of the split.
     */
    @Test
    fun anEffectLeavesWhatItDoesNotName() {
        val room = RoomState(envelopment = 0.6f, separation = 0.7f, otherHalfIds = setOf("b"))
        val placed = room.withEffect(EffectKind.PLACE)
        assertEquals(0.6f, placed.envelopment)
        assertEquals(0.7f, placed.separation)
        assertEquals(setOf("b"), placed.otherHalfIds)
    }

    /** One effect per mode, so the mode alone says which effect a room is on. */
    @Test
    fun everyModeHasExactlyOneEffect() {
        assertEquals(SpatialMode.entries.toSet(), EffectKind.entries.map { it.settings.mode }.toSet())
        assertEquals(SpatialMode.entries.size, EffectKind.entries.size)
    }
}
