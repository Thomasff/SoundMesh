package com.soundmesh.product

import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import org.junit.Assert.assertEquals
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
    /**
     * The state an effect leaves behind, driven through the real apply.
     *
     * Not rebuilt from [EffectSettings] by hand, which is what this helper used to do and which
     * proves nothing about whether anything calls it correctly: an apply that forgot to send the
     * reverberation would leave every test here green while the screen lit up 自定的设置 on every
     * tap. So this is a RoomActions that writes into a room, and what comes out is what a finger
     * would have produced.
     */
    private fun roomOn(effect: RoomEffect): RoomState {
        var room = RoomState()
        apply(
            effect,
            RoomActions(
                moveIcon = {},
                fitToMeasured = {},
                measureListener = {},
                setDelayCompensation = {},
                pickMode = { room = room.copy(mode = it) },
                setPan = { room = room.copy(pan = it) },
                setSeparation = { room = room.copy(separation = it) },
                setEnvelopment = { room = room.copy(envelopment = it) },
                setPeriodSeconds = { room = room.copy(periodSeconds = it) },
                setRetreat = { room = room.copy(retreat = it) },
                setReverb = { room = room.copy(reverb = it) },
                pickAxis = { room = room.copy(splitAxis = it) },
                setCrossoverHz = { room = room.copy(crossoverHz = it) },
                togglePart = {}
            )
        )
        return room
    }

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
     * Moving a knob in the fine tuning leaves the chosen row exactly where it was.
     *
     * This used to be the opposite assertion, and the opposite assertion is what shipped: the
     * reverberation counted towards which row lit up, so dragging 回声强度 away from 40% put the
     * list out and dragging it back lit the row again. Reported 2026-09-18 as "不知道这只是显示
     * bug 还是会同时取消功能" - and the honest answer, which is what makes it a bug worth having a
     * test for, is that nothing about the sound changed either time.
     */
    @Test
    fun `a knob moved in the fine tuning does not put the list out`() {
        assertEquals(RoomEffect.SPIN, effectOf(RoomState(mode = SpatialMode.ROTATE, reverb = 0f)))
        assertEquals(
            RoomEffect.PLACE,
            effectOf(RoomState(mode = SpatialMode.PAN, reverb = 1f, envelopment = 0f))
        )
        assertEquals(
            RoomEffect.STEREO,
            effectOf(RoomState(mode = SpatialMode.SPLIT, reverb = 0.5f))
        )
    }

    /**
     * Every mode has a row, which is what lets [effectOf] promise an answer rather than a null.
     * A mode added without a row would not fail to compile - it would throw on the screen that
     * draws the list, five times a second.
     */
    @Test
    fun `every mode the rule has is a row on the list`() {
        assertEquals(
            SpatialMode.entries.toSet(),
            RoomEffect.entries.map { it.settings.mode }.toSet()
        )
        assertEquals(SpatialMode.entries.size, RoomEffect.entries.size)
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
