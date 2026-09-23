package com.soundmesh.product

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis

/** What the screen draws about the room, and nothing it decides. */
data class RoomState(
    val icons: List<RoomIcon> = emptyList(),
    /**
     * Unison, which is what a room that nobody has touched should be doing: several phones playing
     * one song in step, with no claim about where the sound is that could be wrong.
     */
    val mode: SpatialMode = SpatialMode.UNISON,
    /** Where the pan slider is, in the same -1..1 the rule uses. Ignored outside [SpatialMode.PAN]. */
    val pan: Float = 0f,
    /** Which icon is this phone, so a listener can tell which one is in their hand. */
    val selfId: String? = null,
    /**
     * Where in the palette each handset sits, from [com.soundmesh.core.RoomColours].
     *
     * Missing is the ordinary state rather than a fault: a room is read off the spatial
     * channel, and a handset appears in the drawing as soon as it is named. Everything here
     * falls back to one colour for all of them, which is what every build before this drew.
     */
    val colours: Map<String, Int> = emptyMap(),
    /**
     * Handsets in the room that are not being sent audio: the ones that have stopped playing.
     *
     * Drawn rather than only counted because the drawing is the one screen that already says
     * which handset is which. Two counts that disagree can say a handset left and cannot say
     * which one, and the symptom - one phone gone quiet - reads as the content split having
     * gone wrong rather than as a handset having dropped off the network.
     */
    val silentIds: Set<String> = emptySet(),
    /**
     * How far apart two handsets were measured to be, in metres, keyed by the two of them and
     * held both ways round.
     *
     * Keyed by a pair rather than by a peer because a room measures distances this handset is
     * not an end of, and those are the ones worth having: two handsets swapped with each other
     * at the same distance from here cannot be told apart by any length that starts here.
     *
     * Read when the roster changes rather than every pass: it comes off disk, and the roster is
     * re-read five times a second. Empty is the ordinary state - nothing has measured a room
     * whose phones have never run a calibration.
     */
    val measuredMetres: Map<Pair<String, String>, Double> = emptyMap(),
    /**
     * How far the listener was from each handset, in metres, when an overhead round measured it.
     *
     * Keyed by one handset rather than by a pair because the listener is the other end of every
     * one of them, and the listener is not a handset: it has no icon, no colour and no name, and
     * it is the only thing in the drawing a person cannot check by looking at the room.
     *
     * Empty is the ordinary state. Everything still works from the drawing alone, with the
     * listener where it has always been assumed to be - in the middle of the handsets, which is
     * where nobody sits.
     */
    val listenerMetres: Map<String, Double> = emptyMap(),
    /**
     * How many metres one unit of the drawing is, or zero while nothing knows.
     *
     * Comes out of the fit rather than off disk, because it is a property of the answer and not
     * of any one measurement: the fit is what reconciles every measured length with the drawing
     * into one room at one size. Zero until the overhead round has measured the listener and the
     * fit has been taken - and zero again the moment a fresh measurement lands, because the old
     * scale belongs to the old room. Only the arrival delay reads it.
     */
    val metresPerUnit: Double = 0.0,
    /**
     * Whether the room is holding the near handsets back, when it knows how far away they are.
     *
     * On by default, because a measured room that plays without it is the thing the measuring
     * was for. It is a switch rather than a fixed rule for one reason: a correction that works
     * sounds like nothing, and so does one that never started. Without a way back to the room
     * as it was a moment ago, nobody can tell those two apart by ear - and the ear is the only
     * instrument that can answer this one. The scale is kept while it is off, so the two states
     * differ in the delay and in nothing else.
     */
    val delayCompensation: Boolean = true,
    /**
     * How much of itself each handset keeps when the moving source faces away from it.
     *
     * A quarter rather than the zero [SpatialField] defaults to, and the two defaults mean
     * different things on purpose: the rule's zero is the law with nothing added, which is what
     * a handset told nothing should render, and this is what a listener asked for after hearing
     * the law with nothing added. At zero each handset falls silent once per revolution, which
     * is one phone playing and then another rather than a source going round a room.
     */
    val envelopment: Float = DEFAULT_ENVELOPMENT,
    /**
     * How far off the source itself is, from 0 where it stands to 1 at the furthest this allows.
     * Every handset together - see [com.soundmesh.core.SpatialField.retreat].
     *
     * No control of its own since 2026-09-17: the dot on the room drawing is where it comes from,
     * and it is the half of that gesture the drawing cannot show by itself. Which side a sound is
     * on is the difference between the handsets; how far off it is, is what they have in common,
     * and one dot has to be able to say both because a position is two numbers.
     *
     * Not written to the saved drawing, so a room opens with its source where it stands.
     */
    val retreat: Float = 0f,
    /**
     * How live the room is, from 0 for none of it to 1 for as much as this allows - see
     * [com.soundmesh.core.SpatialField.reverb].
     *
     * Not something [retreat] drives, and that separation is the point. How far off the source is
     * and how much room there is are two facts, and it is the **ratio** between what arrives
     * straight from the source and what arrives off the walls that a listener reads as distance.
     * Tie them together and there is no ratio left to change.
     *
     * Zero here, and not zero in practice: the two effects that move a source around set it to
     * [DEFAULT_REVERB] when they are chosen, and the two that do not leave it alone. This default
     * is what the room plays before anybody has chosen anything, which is 同步齐奏 - and that one
     * is the control the others are heard against, so it has to be the arrangement with nothing
     * added rather than the arrangement with one thing added.
     *
     * [DEFAULT_REVERB] itself is the one number in this screen a listener picked rather than a
     * neutral somebody defaulted to: 2026-09-17, on the first build that had a reverberation in
     * it, four tenths of what a handset plays being the room. If a later room disagrees, that is
     * the number to move, and it should be moved by somebody listening rather than by an argument.
     *
     * Stated as the knob rather than as the share, because the knob is what is stored: the share
     * the readout names is this times [RoomReverb.MOST_WET], so eight tenths there is four tenths
     * heard.
     */
    val reverb: Float = 0f,
    val periodSeconds: Int = (SpatialField.DEFAULT_PERIOD_NANOS / 1_000_000_000L).toInt(),
    /** How far apart the mix is pulled, in the same 0..1 the rule uses. Zero is every handset playing all of it. */
    val separation: Float = 0f,
    /** Which way the mix is pulled apart. One at a time: the knob and the parts below mean whatever this says. */
    val splitAxis: SplitAxis = SplitAxis.MIDDLE_SIDES,
    /** Where the low half stops. Only on screen while the split runs along that axis. */
    val crossoverHz: Float = SpatialField.DEFAULT_CROSSOVER_HZ.toFloat(),
    /** Which handsets carry the sides. Everything in [icons] and not in here carries the middle. */
    val otherHalfIds: Set<String> = emptySet(),
    /**
     * Whether the drawing as it stands has already been moved onto the measurement.
     *
     * Here so that the offer can be made once and not again. Fitting a fit walks the drawing
     * further off what the person drew every time - see RoomFitTest - and the weight that stops
     * that only works while the thing it is anchored to is a person's opinion. Anything that
     * makes the drawing somebody's opinion again clears this: a drag, or a fresh measurement.
     */
    val fitted: Boolean = false
)

/** What a room ships at, which is where a listener put the slider rather than where zero is. */
const val DEFAULT_ENVELOPMENT = 0.25f
