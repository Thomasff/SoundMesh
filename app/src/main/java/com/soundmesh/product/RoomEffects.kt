package com.soundmesh.product

import androidx.annotation.StringRes
import com.soundmesh.core.SpatialMode
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
    val settings: EffectSettings
) {
    /**
     * Every handset playing the same thing, which is the control the other three are heard against.
     *
     * First in the list because that is where a comparison has to be: judging any of the others is
     * two taps, and the music does not stop in between. It is also what a room should open on -
     * several phones playing one song in step is the thing this project does, and everything below
     * is an opinion about it.
     */
    UNISON(
        R.string.room_effect_unison,
        R.string.room_effect_unison_line,
        EffectSettings(SpatialMode.UNISON)
    ),

    /** Two channels, placed by where the phones are standing. */
    STEREO(
        R.string.room_effect_stereo,
        R.string.room_effect_stereo_line,
        EffectSettings(SpatialMode.SPLIT)
    ),

    /**
     * A source going round the room, with the room switched on underneath it.
     *
     * The reverberation comes with it rather than being a knob somebody finds later, and this is
     * the decision of 2026-09-17: a source that moves is the only kind that has anywhere to move
     * *to*, and without a room to move in, going further off is only going quieter. The two rows
     * above do not move a source anywhere, so they get none of it and stay bit for bit what they
     * were.
     */
    SPIN(
        R.string.room_effect_spin,
        R.string.room_effect_spin_line,
        EffectSettings(SpatialMode.ROTATE, envelopment = DEFAULT_ENVELOPMENT, reverb = DEFAULT_REVERB)
    ),

    /** The same source, put where a finger says instead of where the clock says. */
    PLACE(
        R.string.room_effect_place,
        R.string.room_effect_place_line,
        EffectSettings(SpatialMode.PAN, reverb = DEFAULT_REVERB)
    )
}

/**
 * Everything one effect decides. Whatever is not in here survives being switched away from.
 *
 * Which is the whole reason the content split is not in here. Somebody who has told four phones
 * which of them carry the voice, then tries the rotation for a minute, has to find that assignment
 * where they left it - and an effect that reset it would make trying anything expensive. See
 * HomeActivity, where the split is dropped from the **rule** while a source is moving and the
 * screen goes on remembering it.
 */
data class EffectSettings(
    val mode: SpatialMode,
    /**
     * What to set the envelopment to, or null to leave whatever is there.
     *
     * Null for three of the four, and each for its own reason. 同步齐奏 and 双声道 never read it,
     * so writing it would be a setter with no sound behind it. 自定义声音位置 reads it as **where
     * the dot is** - how far in from the handsets it has been dragged - so an effect that set it
     * would move the dot every time somebody chose that row, which is the assignment problem
     * [EffectSettings] exists to avoid, in a second place.
     */
    val envelopment: Float? = null,
    val reverb: Float = 0f
)

/** Where a room is put the moment somebody asks for a source that moves. The listener's number. */
const val DEFAULT_REVERB = 0.8f

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
