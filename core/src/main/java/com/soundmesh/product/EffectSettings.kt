package com.soundmesh.product

import com.soundmesh.core.SpatialMode

/**
 * The four things a room can be asked to sound like, as settings - what each of them sets.
 *
 * In core, apart from the words a screen shows for them, because a computer host offers the same
 * four and must set the same knobs: the handset's RoomEffect gives each its title and line, and
 * the desktop window gives its own. One table, so the two cannot drift apart.
 */
enum class EffectKind(val settings: EffectSettings) {
    /**
     * Every handset playing the same thing, which is the control the other three are heard against.
     *
     * First in the list because that is where a comparison has to be: judging any of the others is
     * two taps, and the music does not stop in between. It is also what a room should open on -
     * several phones playing one song in step is the thing this project does, and everything below
     * is an opinion about it.
     */
    UNISON(EffectSettings(SpatialMode.UNISON)),

    /** Two channels, placed by where the phones are standing. */
    STEREO(EffectSettings(SpatialMode.SPLIT)),

    /**
     * A source going round the room, with the room switched on underneath it.
     *
     * The reverberation comes with it rather than being a knob somebody finds later, and this is
     * the decision of 2026-09-17: a source that moves is the only kind that has anywhere to move
     * *to*, and without a room to move in, going further off is only going quieter. The two rows
     * above do not move a source anywhere, so they get none of it and stay bit for bit what they
     * were.
     */
    SPIN(EffectSettings(SpatialMode.ROTATE, envelopment = DEFAULT_ENVELOPMENT, reverb = DEFAULT_REVERB)),

    /** The same source, put where a finger says instead of where the clock says. */
    PLACE(EffectSettings(SpatialMode.PAN, reverb = DEFAULT_REVERB))
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
 * [room] set to one named result - the handset's `apply(effect, actions)`, as one step.
 *
 * Every knob the effect names is set, including the ones it leaves at zero: an effect chosen after
 * another one has to undo that one. The content split is not touched - see [EffectSettings].
 */
fun RoomState.withEffect(kind: EffectKind): RoomState {
    val settings = kind.settings
    return copy(
        mode = settings.mode,
        envelopment = settings.envelopment ?: envelopment,
        reverb = settings.reverb
    )
}
