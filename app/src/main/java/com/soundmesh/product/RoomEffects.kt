package com.soundmesh.product

import androidx.annotation.StringRes
import com.soundmesh.probe.R

/**
 * The named things a room can be asked to sound like.
 *
 * Four of them, and they are four answers to one question: **where is the sound**. Nothing else
 * belongs on this list. That was the lesson of the six that were here before, where "人声和伴奏
 * 分开" sat beside "绕着转" as though a listener had to choose between them - and they had not,
 * because splitting the song up and moving it around are different questions that compose. Now
 * the list answers the first question and the chosen row carries the second.
 *
 * Each row is a line of text and, when it is the chosen one, whatever it is played with: a speed
 * for the one that turns, a content split for the two that stand still, nothing at all for the one
 * whose control is the dot on the drawing above. A row that is not chosen shows only its name, so
 * the list is four lines high whichever row is open.
 *
 * Deliberately not stored anywhere. The settings are the state and this is a reading of them:
 * [effectOf] answers "which of these is the room currently set to", off the mode alone.
 */
enum class RoomEffect(
    @StringRes val title: Int,
    @StringRes val line: Int,
    // What each one sets lives in core, with why, because a computer host offers the same four.
    kind: EffectKind
) {
    UNISON(R.string.room_effect_unison, R.string.room_effect_unison_line, EffectKind.UNISON),
    STEREO(R.string.room_effect_stereo, R.string.room_effect_stereo_line, EffectKind.STEREO),
    SPIN(R.string.room_effect_spin, R.string.room_effect_spin_line, EffectKind.SPIN),
    PLACE(R.string.room_effect_place, R.string.room_effect_place_line, EffectKind.PLACE);

    val settings: EffectSettings = kind.settings
}

/**
 * Which effect the room is set to. Always one of them.
 *
 * Read off the mode and nothing else, because the four rows are the four modes. It used to
 * compare the whole of [EffectSettings], which meant the reverberation counted towards which row
 * lit up - so moving the 房间回声 knob in the fine tuning put the list out, with nothing chosen,
 * and moving it back lit the row again. Reported 2026-09-18.
 *
 * Nothing about the sound changed while that was happening, which is what made it worth fixing
 * rather than explaining: a knob that is on screen to be moved may not un-name the thing it is
 * tuning. The row says which effect is playing, the knobs say how it is set, and those are two
 * questions.
 */
fun effectOf(state: RoomState): RoomEffect =
    RoomEffect.entries.first { it.settings.mode == state.mode }
