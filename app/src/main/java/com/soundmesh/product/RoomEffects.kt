package com.soundmesh.product

import androidx.annotation.StringRes
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import com.soundmesh.probe.R

/**
 * The named things a room can be asked to sound like, since 2026-09-15.
 *
 * What was here before was the settings themselves: a mode, a separation knob, an axis, an
 * envelopment slider and a diffusion slider, each with a paragraph under it explaining the rule it
 * drives. Every one of those paragraphs is true and none of them answers the question somebody
 * opening this screen is actually asking, which is "what can four phones do that one cannot".
 * Five controls with no named result between them is also a shape nothing can be added to: a
 * sixth knob is a sixth thing to understand before anything can be heard.
 *
 * So the knobs are still all there - see SpatialPanel's fine tuning - and in front of them is a
 * list of results. Each is one line. Adding one means adding an entry here and two strings.
 *
 * Deliberately not stored anywhere. The settings are the state, and this is a reading of them:
 * [effectOf] answers "which of these is the room currently set to", and a room that matches none
 * of them says so rather than pretending to be the nearest one.
 */
enum class RoomEffect(
    @StringRes val title: Int,
    @StringRes val line: Int,
    val settings: EffectSettings
) {
    /** Two channels, placed by where the phones are standing. The plainest thing this app does. */
    STEREO(
        R.string.room_effect_stereo,
        R.string.room_effect_stereo_line,
        EffectSettings(SpatialMode.SPLIT)
    ),

    SPIN(
        R.string.room_effect_spin,
        R.string.room_effect_spin_line,
        EffectSettings(SpatialMode.ROTATE, envelopment = DEFAULT_ENVELOPMENT)
    ),

    SWEEP(
        R.string.room_effect_sweep,
        R.string.room_effect_sweep_line,
        EffectSettings(SpatialMode.PAN, envelopment = DEFAULT_ENVELOPMENT)
    ),

    VOICE(
        R.string.room_effect_voice,
        R.string.room_effect_voice_line,
        EffectSettings(SpatialMode.SPLIT, separation = 1f, axis = SplitAxis.MIDDLE_SIDES)
    ),

    BASS(
        R.string.room_effect_bass,
        R.string.room_effect_bass_line,
        EffectSettings(SpatialMode.SPLIT, separation = 1f, axis = SplitAxis.LOW_HIGH)
    )
}

/** Everything one effect decides. Whatever is not in here is the listener's to set. */
data class EffectSettings(
    val mode: SpatialMode,
    val separation: Float = 0f,
    val axis: SplitAxis = SplitAxis.MIDDLE_SIDES,
    val envelopment: Float = 0f
)

/**
 * Which effect the room is set to, or null where it is set to something with no name.
 *
 * Null is a real answer and is shown as one. Anybody who has moved a slider in the fine tuning is
 * somewhere between two of these, and a list that highlighted the nearest one would be telling
 * them their change did not take.
 */
fun effectOf(state: RoomState): RoomEffect? {
    val here = settled(
        EffectSettings(
            state.mode,
            state.separation,
            state.splitAxis,
            state.envelopment
        )
    )
    return RoomEffect.entries.firstOrNull { settled(it.settings) == here }
}

/**
 * The settings with the parts nobody can hear taken out of them.
 *
 * An axis nothing is being split along, and an envelopment for a source that never turns away,
 * are both carried in the state and change nothing about the room - so two rooms differing only
 * there are one effect, not two. Without this, choosing 绕着转 and then 双声道 would leave
 * an envelopment behind and the list would show nothing as chosen.
 */
private fun settled(settings: EffectSettings): EffectSettings = EffectSettings(
    mode = settings.mode,
    separation = rounded(settings.separation),
    axis = if (settings.separation <= 0f) SplitAxis.MIDDLE_SIDES else settings.axis,
    envelopment = if (settings.mode == SpatialMode.SPLIT) 0f else rounded(settings.envelopment)
)

/**
 * Sliders report floats and a preset is written as one, and the two only ever have to agree to
 * about a stop's width - which for every knob here is a twentieth of its range.
 */
private fun rounded(value: Float): Float = (value * SETTLE_STEPS).toInt() / SETTLE_STEPS

private const val SETTLE_STEPS = 20f
