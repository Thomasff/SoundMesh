package com.soundmesh.product

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundmesh.core.PeerBadge
import com.soundmesh.core.SpatialField
import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.RoomReverb
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import com.soundmesh.core.SplitAxis
import com.soundmesh.core.SpatialMode
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.RoomCommands
import kotlin.math.hypot

/**
 * The drawing, and the three controls that decide what it means.
 *
 * The listener is fixed in the middle and cannot be dragged. That is deliberate rather than
 * unfinished: whether a phone icon is where the phone is can be checked by looking at the room,
 * and whether the app believes the listener is sitting where they are sitting cannot be checked at
 * all - so the one position nobody can verify is the one position nobody is asked for. The circle
 * in the middle carries the word for it, which is what a sentence underneath the map used to do.
 *
 * Nothing here knows about gains. The drawing gives directions, the mode says what moves, and the
 * rule the room plays under is assembled by whoever owns the session.
 */
@Composable
fun SpatialPanel(
    state: RoomState,
    actions: RoomActions,
    /** Whether the settings screen's diagnostic switch is on. See [ArrivalDelays]. */
    showDetails: Boolean = false,
    /** Names standing handsets have reported as not exempt from power saving. See [StandbyLook]. */
    blockedPeerNames: List<String> = emptyList(),
    /** What the rule is asking of each handset at this instant. See [RuleReadings]. */
    readings: List<RoomReading> = emptyList(),
    /** The just-pressed-play ripple's progress, 0f..1f, or null while nothing is animating. */
    ripple: Float? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note(stringResource(R.string.room_hint))
        MeasuredRoom(state, actions.onTheMap(), blockedPeerNames, ripple)
        ArrivalDelays(state, actions, showDetails)
        EffectSection(state, actions, readings)
    }
}

/**
 * The room as it has been measured: the drawing, what disagrees with it, the lengths measured
 * between the phones and to the listener, and the offer to move the drawing onto them.
 *
 * One definition for the two screens that show it, and that is a requirement rather than tidiness.
 * The calibration screen is where the measuring happens and the home screen is where the room is
 * played, and a person who arranges the phones on one and finds a different arrangement on the
 * other has no way to tell which of the two the music is using. So both draw this, both drag it
 * the same way, and both read and write the one file underneath it - see [StoredRoomDrawing].
 *
 * What the two cannot share is who is in the room: the home screen knows who is connected and the
 * calibration screen knows who took part in the round. Everything else is the same room.
 */
@Composable
fun MeasuredRoom(
    state: RoomState,
    actions: RoomMapActions,
    blockedPeerNames: List<String> = emptyList(),
    ripple: Float? = null
) {
    RoomMap(state, actions, blockedPeerNames, ripple)
    // Standing, not conditional on the arrangement, and that is the decision rather than an
    // omission. What the rule can see is each handset's angle from the listener, and the angles it
    // does badly at are not the same for every effect: handsets spread very wide blur the middle of
    // a stereo image and are exactly what a source going round a room wants. So a warning that
    // fired on an angle would be right about one row of the list and wrong about another, while a
    // standing sentence about the room is true for all of them - and changing the sound to match
    // the arrangement would turn "I moved them and nothing happened" into "I moved them and it
    // undid what I chose", which is worse.
    Note(stringResource(R.string.room_place_evenly))
    if (state.icons.size < 2) {
        Text(stringResource(R.string.room_alone), style = MaterialTheme.typography.bodySmall)
    }
    // Said quietly and never acted on, unlike the lengths below it. A measurement can correct
    // how far apart two icons are; it cannot know which of them is which, so all it can do
    // here is point at two and ask whether they are the right way round. Acting on it would
    // be the app overruling the one thing only a person knows - and it also has to be
    // settled before the lengths are touched, which is what fitOffer refuses on.
    RoomCheck.contradiction(state.icons, state.measuredMetres)?.let { (farther, nearer) ->
        Text(
            stringResource(
                R.string.room_disagrees,
                badgeWords(farther, state.colours[farther]),
                badgeWords(nearer, state.colours[nearer])
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (sourceSpotOf(state, actions) != null) SourceReadout(state)
    ListenerDistances(state, actions)
    MeasuredDistances(state)
    FitOffer(state, actions)
}

/**
 * What the source dot is, and where it has been put.
 *
 * Said in words beside the drawing rather than left to the picture, because half of what the dot
 * means is not to scale and so cannot be read off it - see [SpatialRoom.SOURCE_MAX_RADIUS]. The
 * multiple is the part a person can check against the room they are sitting in; the decibels are
 * there for whoever wants to know what was actually done.
 */
@Composable
private fun SourceReadout(state: RoomState) {
    Note(stringResource(R.string.room_source_hint))
    val decibels = state.retreat * SpatialField.RETREAT_DECIBELS.toFloat()
    val inside = SpatialRoom.envelopmentFor(state.retreat, state.envelopment)
    Note(
        when {
            state.retreat > 0f ->
                stringResource(R.string.room_source_away, 10f.pow(decibels / 20f), decibels)
            // The inside half of the travel, which reads as a share rather than as a distance:
            // there is no "x times nearer" to quote, because nothing is getting louder.
            inside > 0f -> stringResource(
                R.string.room_source_inside,
                (inside / SpatialField.MAX_ENVELOPMENT.toFloat() * 100f).roundToInt()
            )
            else -> stringResource(R.string.room_source_here)
        }
    )
    // The one combination worth a line of its own, because it is the one where the control is
    // working exactly as built and still does not do what it is for. A level on its own is an
    // ambiguous distance cue - it is the ratio against the reverberation that is not - so a source
    // dragged away in a room with no reverberation in it can only sound like the volume going down.
    if (state.retreat > 0f && state.reverb <= 0f) Note(stringResource(R.string.room_source_dry))
}

/**
 * The two things a finger can do to the drawing, and the one way off it.
 *
 * Apart from [RoomActions] because the drawing is shown on a screen that has none of the rest:
 * the calibration screen has no mode to pick and no sliders to pull, and handing it a bag of
 * lambdas that do nothing is how a button that does nothing gets drawn.
 */
class RoomMapActions(
    val moveIcon: (RoomIcon) -> Unit,
    val fitToMeasured: () -> Unit,
    /**
     * Opens the round that places the listener, or null where this screen is that round.
     *
     * Null rather than a lambda that does nothing: offering to go and measure, on the screen the
     * measuring happens on, is a button whose own words are false.
     */
    val measureListener: (() -> Unit)?,
    /**
     * Puts the source somewhere, or null on a screen with no source to put.
     *
     * Null on the calibration screen for the same reason [measureListener] is: that screen draws
     * the same room and has no rule behind it, so a dot a finger could drag would be a control
     * wired to nothing - which is worse than an absent one, because it moves.
     */
    val moveSource: ((SourceSpot) -> Unit)? = null,
    /**
     * Hands a handset the other half of whatever the song is split along, or null on a screen
     * where nothing is being split.
     *
     * Null for the same reason [moveSource] is, and it has one more consequence than the others:
     * it is what decides whether tapping an icon does anything at all, so the calibration screen
     * keeps a map whose icons are only ever dragged. See [partsAreSwitchable].
     */
    val togglePart: ((String) -> Unit)? = null
)

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

class RoomActions(
    val moveIcon: (RoomIcon) -> Unit,
    val fitToMeasured: () -> Unit,
    /** Opens the screen that runs the overhead round, which is the only thing that can. */
    val measureListener: () -> Unit,
    /** Holds the near handsets back, or stops: the A and the B of the only test there is. */
    val setDelayCompensation: (Boolean) -> Unit,
    val pickMode: (SpatialMode) -> Unit,
    val setPan: (Float) -> Unit,
    val setSeparation: (Float) -> Unit,
    val setEnvelopment: (Float) -> Unit,
    /** How long one circuit takes, in whole seconds. Only read by the rotation. */
    val setPeriodSeconds: (Int) -> Unit,
    val setRetreat: (Float) -> Unit,
    val setReverb: (Float) -> Unit,
    val pickAxis: (SplitAxis) -> Unit,
    val setCrossoverHz: (Float) -> Unit,
    val togglePart: (String) -> Unit
) {
    fun onTheMap() = RoomMapActions(
        moveIcon,
        fitToMeasured,
        measureListener,
        // Two setters from one gesture, which is the whole of what the dot is. Where a source is
        // in a room is two numbers and a slider is one, so a slider can only travel a path chosen
        // for it in advance; this hands both numbers over at once and lets the finger pick.
        // Three setters from one gesture now. The third is the half of the travel that is inside
        // the handsets, where a source cannot get louder and so gets less directional instead.
        moveSource = { spot ->
            setPan(SpatialRoom.panOf(spot))
            setRetreat(SpatialRoom.retreatOf(spot))
            setEnvelopment(SpatialRoom.envelopmentOf(spot))
        },
        togglePart = togglePart
    )
}

/**
 * The drawing with one icon where the finger left it, and the offer open again.
 *
 * Moving an icon is the person saying where a handset is, which is exactly the thing the fit
 * is weighted against - so a drawing that has just been touched is a new opinion and deserves
 * a fresh answer, even if it was fitted a moment ago.
 */
internal fun withIconMoved(room: RoomState, moved: RoomIcon): RoomState = room.copy(
    icons = room.icons.map { if (it.peerId == moved.peerId) moved else it },
    fitted = false
)

/**
 * The drawing moved onto the measured shape, or null when there is nothing to offer.
 *
 * Null while [RoomCheck] is pointing at two icons, and that ordering is the point rather than
 * caution. The fit moves positions to match labels, so a drawing with two icons on the wrong
 * handsets would be moved onto each other's measured places, confidently and without a word.
 * Afterwards would be too late a second time over: once this has run, drawn and measured agree
 * by construction and the check can never fire again.
 */
internal fun fitOffer(state: RoomState): FittedRoom? {
    if (state.fitted) return null
    if (RoomCheck.contradiction(state.icons, state.measuredMetres) != null) return null
    return RoomFit.fitted(state.icons, state.measuredMetres, state.listenerMetres)
}

/**
 * How long each handset waits for the furthest one, in milliseconds, in the drawing's order.
 *
 * Empty until the listener has been measured and the fit taken, which is what makes this the
 * one line on the screen that says the delay is switched on. Everything else about the feature
 * is inaudible by design: it exists to make two handsets sound like one.
 */
/**
 * Whether an overhead round could place the listener in this room at all.
 *
 * Three handsets, and the reason is one that had to be computed rather than guessed. The
 * handset being held over somebody's head cannot measure its own distance to that head - it is
 * the head - so the listener comes out of the round with N-1 distances, not N. A point in a
 * plane needs three of them to be pinned: two handsets leave one, which is a circle and places
 * nothing. Three leave two, which is a fold - the listener's own reflection across the line
 * through the two measured handsets fits both distances exactly - and which side they are on
 * comes from the drawing, the same division of labour as the room's own mirror. Four are unique.
 *
 * So this is the line between "cannot" and "can, with the drawing settling one thing", and the
 * button is offered on the second. Under it there is nothing to offer and a minute of somebody
 * standing still holding a phone to lose.
 */
internal fun overheadRoundCanPlaceTheListener(icons: List<RoomIcon>): Boolean = icons.size >= 3

internal fun delayLines(icons: List<RoomIcon>, metresPerUnit: Double): List<Pair<String, Double>> {
    if (metresPerUnit <= 0.0) return emptyList()
    // Caught rather than thrown, for the reason HomeActivity.publish catches: this runs off a
    // five-a-second refresh on the main thread, and a roster that arrived wrong would take the
    // whole app down over one line of small print.
    val layout = runCatching { SpatialRoom.layoutOf(icons) }.getOrNull() ?: return emptyList()
    val field = SpatialField(SpatialMode.SPLIT, layout, metresPerUnit = metresPerUnit)
    return layout.peerIds.map { it to field.arrivalDelayNanosFor(it) / 1_000_000.0 }
}

/**
 * Which measured distances belong on the screen, in the order they are read out.
 *
 * One way round of each pair: the field holds both, so that a caller with two names need not
 * know which sorts first, and a list that took it at face value would print every distance
 * twice.
 *
 * Only pairs still in the drawing. A field outlives the room it was measured in - it is
 * replaced when the next room is measured, and not when somebody goes home - so a line about a
 * handset that is no longer here is a line about nothing the person can look at.
 */
internal fun measuredLines(
    icons: List<RoomIcon>,
    measured: Map<Pair<String, String>, Double>
): List<Pair<Pair<String, String>, Double>> {
    val room = icons.map { it.peerId }.toSet()
    return measured
        .filterKeys { it.first < it.second && it.first in room && it.second in room }
        .toList()
        .sortedBy { it.first.first + it.first.second }
}

/**
 * What an overhead round measured, in the order the icons are drawn.
 *
 * Only handsets still in the drawing, for the reason the pair lines are filtered: a distance
 * outlives the room it was taken in, and a line about a handset that went home is a line about
 * nothing the person can look at.
 */
internal fun listenerLines(
    icons: List<RoomIcon>,
    listenerMetres: Map<String, Double>
): List<Pair<String, Double>> = icons.mapNotNull { icon ->
    listenerMetres[icon.peerId]?.let { icon.peerId to it }
}

/**
 * What the last measurement made of the room, one pair at a time.
 *
 * On screen rather than only in a run's report, because this is the only place a person can
 * hold the numbers against the room they are standing in. Until they can, a check that fires on
 * their drawing is an accusation with nothing visible behind it.
 *
 * Numbers without the colour words, which is the one place in the app that drops half of a
 * handset's name on purpose: the colours are on the icons a few millimetres above, and a room
 * of four spelled out in full is ten pairs of "7 号（绿）" that nobody reads.
 */
/**
 * Where the person was sitting, if anybody ever measured it.
 *
 * Above the pair lengths because it is the one that changes what is heard the most and the one
 * nothing else can check. A listener half a metre from where the drawing assumed is worth about
 * one and a half milliseconds, and past a millisecond the earlier handset takes the image
 * outright however the levels are set.
 */
/**
 * What the room is doing about the near handsets being heard first.
 *
 * On screen because it is the only evidence: a delay that works sounds like nothing at all, and
 * a delay that never switched on sounds like nothing at all as well. Zero for the furthest
 * handset always - it is the one everybody else is waiting for.
 */
@Composable
private fun ArrivalDelays(state: RoomState, actions: RoomActions, showDetails: Boolean) {
    val shown = delayLines(state.icons, state.metresPerUnit)
    if (shown.isEmpty()) return
    Ghost(
        stringResource(
            if (state.delayCompensation) R.string.room_delay_on else R.string.room_delay_off
        ),
        modifier = Modifier.padding(top = 8.dp)
    ) { actions.setDelayCompensation(!state.delayCompensation) }
    if (!state.delayCompensation || !showDetails) return
    Label(R.string.room_arrival_delay)
    Readings(shown.map { (peerId, millis) -> listOf(peerId) to "%.1f".format(millis) }, state.colours)
}

@Composable
private fun ListenerDistances(state: RoomState, actions: RoomMapActions) {
    val shown = listenerLines(state.icons, state.listenerMetres)
    if (shown.isEmpty()) {
        // Nothing at all in a room too small to place the listener, which is the two-phone room
        // almost everybody starts in. The sentence that used to be here explained why the round
        // is unavailable - a handset cannot measure its own distance to the ear holding it - and
        // it was on screen at the one moment nobody can act on it: there is no button under it,
        // and the answer is to fetch a third phone.
        if (!overheadRoundCanPlaceTheListener(state.icons)) return
        // Said once and only while it is true, because it is the one thing on this screen a
        // person cannot find out by looking: the drawing shows where the phones are and has
        // never shown where they are sitting, so an unmeasured listener looks exactly like a
        // measured one that happens to be in the middle.
        Note(stringResource(R.string.room_listener_unmeasured))
        actions.measureListener?.let { go ->
            Ghost(
                stringResource(R.string.room_measure_listener),
                modifier = Modifier.padding(top = 8.dp),
                onClick = go
            )
        }
        return
    }
    Label(R.string.room_listener_measured)
    Readings(shown.map { (peerId, metres) -> listOf(peerId) to "%.2f".format(metres) }, state.colours)
}

@Composable
private fun MeasuredDistances(state: RoomState) {
    val shown = measuredLines(state.icons, state.measuredMetres)
    if (shown.isEmpty()) return
    Label(R.string.room_measured)
    Readings(
        shown.map { (names, metres) -> listOf(names.first, names.second) to "%.2f".format(metres) },
        state.colours
    )
}

/**
 * One measured number per entry, named by the handsets it is about.
 *
 * Named by the same badge the drawing uses - the number on its own colour - rather than by the
 * number alone, which is what these lines used to print. The badge is the identity everywhere
 * else in this app, and a length that says "2-3" beside a picture where 2 and 3 are a teal dot
 * and a blue one makes a person translate between two namings of one room. Smaller than the ones
 * on the drawing, because these are a reading and those are something to drag.
 *
 * The value arrives already written out, because the three readings that use this are metres,
 * metres and milliseconds, and the number of decimal places is a property of the unit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Readings(rows: List<Pair<List<String>, String>>, colours: Map<String, Int>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        for ((peerIds, written) in rows) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (peerId in peerIds) {
                    BadgeChip(
                        peerId,
                        colours[peerId],
                        diameter = LENGTH_BADGE,
                        fontSize = LENGTH_BADGE_TEXT
                    )
                }
                Text(
                    written,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Small enough to read as a label on a number, big enough for two digits on it. */
private val LENGTH_BADGE = 17.dp
private val LENGTH_BADGE_TEXT = 9.sp

/**
 * The one button on this screen that changes the drawing without a finger on an icon.
 *
 * A button rather than a snap that happens on its own. The person put those icons there, and
 * something that quietly moves them afterwards reads as the app arguing; offered instead, the
 * before and the after are both theirs to look at. It goes under the measured lengths because
 * those lengths are what it acts on.
 */
@Composable
private fun FitOffer(state: RoomState, actions: RoomMapActions) {
    if (state.fitted) {
        Note(stringResource(R.string.room_fit_done))
        return
    }
    if (fitOffer(state) == null) return
    // Solid, and that is the whole signal. Hollow, it read as an option somebody might take; what
    // it actually means is that the drawing and the measurement have parted - which only happens
    // because a finger moved an icon - and the state the button is in IS the notice that they have.
    Solid(
        stringResource(R.string.room_fit),
        modifier = Modifier.padding(top = 8.dp),
        onClick = actions.fitToMeasured
    )
}

/**
 * The drawing, with one handset's own buttons over the top of it where a finger has opened them.
 *
 * Which half of a split song a phone carries is a fact about that phone, so it is asked for by
 * pointing at the phone. It used to be a list underneath the panel - a row per handset, a badge
 * and a button - which meant reading a number off a coloured dot on the map, finding the row with
 * that number, and pressing a button nowhere near either. The map already knows which phone is
 * which and where it is standing; the list was a second, worse copy of the room.
 *
 * The buttons live out here rather than inside the Canvas because they are buttons: a shape drawn
 * into a canvas has no press state, no ripple and nothing an accessibility service can find.
 */
@Composable
private fun RoomMap(
    state: RoomState,
    actions: RoomMapActions,
    blockedPeerNames: List<String>,
    ripple: Float?
) {
    var chosenPeerId by remember { mutableStateOf<String?>(null) }
    val switchable = partsAreSwitchable(state, actions)
    // A room that stops splitting the song has no halves to hand out, and buttons left open over
    // it would offer a choice that no longer reaches the rule.
    LaunchedEffect(switchable) { if (!switchable) chosenPeerId = null }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val side = maxWidth
        RoomDrawing(
            state,
            actions,
            blockedPeerNames,
            ripple,
            switchable,
            chosenPeerId
        ) { chosenPeerId = it }
        // Looked up again rather than held: a handset that left the room while its buttons were
        // open would otherwise be drawn a set of controls for a phone that is not there.
        val open = chosenPeerId?.let { id -> state.icons.firstOrNull { it.peerId == id } }
        val toggle = actions.togglePart
        if (switchable && open != null && toggle != null) {
            PartButtons(open, state, toggle, side) { chosenPeerId = null }
        }
    }
}

/**
 * Whether tapping a handset on the drawing has anything to offer.
 *
 * Three conditions and each rules out a different kind of dead control: a screen with no way to
 * hand out parts at all (the calibration screen draws this same room and has no rule behind it),
 * a room that is not splitting the song, and a room whose effect moves a single source - where
 * the split is dropped from the rule even though the screen goes on remembering it, see
 * [ruleOf]. Buttons in that last case would be the worst of the three: they would answer.
 */
internal fun partsAreSwitchable(state: RoomState, actions: RoomMapActions): Boolean =
    actions.togglePart != null && state.separation > 0f && !state.mode.movesASource

@Composable
private fun RoomDrawing(
    state: RoomState,
    actions: RoomMapActions,
    blockedPeerNames: List<String>,
    ripple: Float?,
    /** Whether a tap on a handset opens its own buttons. See [partsAreSwitchable]. */
    switchable: Boolean,
    /** Whose buttons are open just now, or null with none of them. */
    openPeerId: String?,
    /** Called with whose buttons should be open after this gesture. */
    onChoose: (String?) -> Unit
) {
    // The gesture below is a coroutine keyed on who is in the room, so it outlives every
    // recomposition that only moved somebody - and it closes over the room as it was when that
    // coroutine started. Read directly, `state` inside the gesture is the room from before the
    // first drag: a finger reaching for the icon it can see was matched against where the icons
    // used to be, so it picked up whichever handset used to be nearest and that handset jumped
    // across the room to a touch that was nowhere near it. Both halves of what a listener reports
    // as "the icon at the edge will not be picked up" come from this one line.
    //
    // Read through here rather than added to the key: the key restarts the gesture, and a key
    // that moves would cancel the drag that is moving it.
    val room by rememberUpdatedState(state)
    val onRoom by rememberUpdatedState(actions)
    // Read through here for the reason above: the gesture outlives the recompositions that move
    // these, so a tap would decide against whatever was true when it was first started.
    val canSwitch by rememberUpdatedState(switchable)
    val alreadyOpen by rememberUpdatedState(openPeerId)
    val choose by rememberUpdatedState(onChoose)
    val measurer = rememberTextMeasurer()
    val listener = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val handset = MaterialTheme.colorScheme.primary
    val self = MaterialTheme.colorScheme.tertiary
    val source = MaterialTheme.colorScheme.secondary
    val label = MaterialTheme.colorScheme.onPrimary
    val danger = MaterialTheme.colorScheme.error
    // One of three states per icon rather than one hollow circle - see StandbyLook. Worked out
    // here, in composable scope rather than inside the Canvas below, because the killed pulse needs
    // remembered animation state that a DrawScope cannot hold.
    val looks = HashMap<String, StandbyLook>(state.icons.size)
    val killedPulses = HashMap<String, Float>(state.icons.size)
    for (icon in state.icons) {
        val look = standbyLook(
            connected = icon.peerId !in state.silentIds,
            saidNotExempt = RoomCommands.nameOf(icon.peerId)?.let { it in blockedPeerNames } == true
        )
        looks[icon.peerId] = look
        killedPulses[icon.peerId] = key(icon.peerId) { rememberKilledPulse(look) }
    }
    val frame = MaterialTheme.colorScheme.surfaceVariant
    // Resolved out here because a DrawScope cannot read resources, and unconditionally because a
    // stringResource behind an if is a composable whose presence changes with the state.
    val nearWord = stringResource(partLabelOf(state.splitAxis, false))
    val farWord = stringResource(partLabelOf(state.splitAxis, true))
    val listenerWord = stringResource(R.string.room_listener_word)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // A hairline round the drawing, now that there is no card edge to say where it stops.
            // Square on purpose: a place in here is a fraction of the width and a fraction of the
            // height, so any other shape would stretch the room along one axis and the lengths
            // underneath would stop agreeing with the picture.
            .border(1.dp, frame, RoundedCornerShape(10.dp))
            .pointerInput(state.icons.map { it.peerId }) {
                fun put(held: Grabbed, at: Offset) {
                    val x = (at.x / size.width).coerceIn(0.02f, 0.98f)
                    val y = (at.y / size.height).coerceIn(0.02f, 0.98f)
                    when (held) {
                        // Unclamped on purpose: what the source may not do is said once, in
                        // SpatialRoom.panOf and retreatOf, and the dot is redrawn from what they
                        // answered. A second clamp here would be a second opinion about the same
                        // edge, and the two would drift.
                        is Grabbed.Source -> onRoom.moveSource?.invoke(SourceSpot(x, y))
                        is Grabbed.Handset ->
                            onRoom.moveIcon(SpatialRoom.clamped(RoomIcon(held.peerId, x, y)))
                    }
                }
                awaitEachGesture {
                    // Nothing here is consumed until an icon has actually been picked up, which
                    // is the whole point of writing the gesture out rather than using
                    // detectDragGestures. That helper consumes on its way to deciding, so a
                    // finger landing on empty canvas took the gesture and then did nothing with
                    // it - and this map is a full-width square, so the page could not be
                    // scrolled anywhere near it. A dead patch in the middle of the screen, with
                    // nothing on it to explain why. Reported 2026-09-14.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Chosen once and held for the whole gesture. Re-choosing the nearest icon
                    // on every event would let a fast drag hand itself to whichever one it
                    // passed over.
                    val held = grabbedAt(
                        room.icons,
                        sourceSpotOf(room, onRoom),
                        down.position.x / size.width,
                        down.position.y / size.height
                    )
                    if (held == null) {
                        // Landing on bare canvas puts away whatever was open. Somewhere to press
                        // that means "never mind" has to exist, and on a drawing it is the part
                        // of the drawing with nothing on it.
                        choose(null)
                        return@awaitEachGesture
                    }
                    // Touch slop, so that a tap on an icon is still a tap: without it every
                    // stray pixel of a press moves a handset, and the room drifts under anybody
                    // who rests a finger on it.
                    val began = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    }
                    if (began == null) {
                        // Down and up again without travelling: a tap. This is the gesture that
                        // used to mean nothing at all on this map, which is what makes it free -
                        // a drag is the branch below, and it passed the slop to get there.
                        choose(
                            if (canSwitch && held is Grabbed.Handset && held.peerId != alreadyOpen)
                                held.peerId
                            else null
                        )
                        return@awaitEachGesture
                    }
                    // A drag puts the buttons away: the icon is moving out from under them.
                    choose(null)
                    put(held, began.position)
                    drag(down.id) { change ->
                        change.consume()
                        put(held, change.position)
                    }
                }
            }
    ) {
        drawCircle(outline, radius = size.minDimension / 2f, style = Stroke(width = 2f))
        drawListener(listener, listenerWord, measurer)
        state.icons.forEach { icon ->
            val place = state.colours[icon.peerId]
            drawHandset(
                icon,
                BadgePalette.colourOf(place, handset),
                BadgePalette.labelColourOf(place, label),
                danger,
                PeerBadge.numberOf(icon.peerId),
                if (icon.peerId == state.selfId) self else null,
                looks[icon.peerId] ?: StandbyLook.GONE,
                killedPulses[icon.peerId] ?: 0f,
                measurer
            )
            // Only while there is a split to see. Without it every icon would carry the word 人声
            // for a room that is not dividing anything, which is a label that is not false so
            // much as about nothing.
            if (switchable) {
                drawPartWord(
                    icon,
                    if (icon.peerId in state.otherHalfIds) farWord else nearWord,
                    listener,
                    measurer
                )
            }
        }
        sourceSpotOf(state, actions)?.let { drawSource(it, source) }
        // The ring that spreads from this handset's own icon the moment play is pressed - see
        // PlayingScreen's LaunchedEffect(state.running). Nothing to draw before this handset has
        // an icon of its own, which is every drawing with no room yet.
        if (ripple != null) {
            state.icons.firstOrNull { it.peerId == state.selfId }?.let { own ->
                drawRipple(own, ripple, BadgePalette.colourOf(state.colours[state.selfId], handset))
            }
        }
    }
}

/** Runs the killed flash the moment [look] becomes [StandbyLook.KILLED], and answers its alpha. */
@Composable
private fun rememberKilledPulse(look: StandbyLook): Float {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(look) {
        if (look != StandbyLook.KILLED) return@LaunchedEffect
        repeat(KILLED_PULSE_REPEATS) {
            pulse.animateTo(1f, animationSpec = tween(KILLED_PULSE_MILLIS))
            pulse.animateTo(0f, animationSpec = tween(KILLED_PULSE_MILLIS))
        }
    }
    return pulse.value
}

/** How many 150ms red pulses a fresh kill gets, and how long each half of one takes. */
private const val KILLED_PULSE_REPEATS = 2
private const val KILLED_PULSE_MILLIS = 150

/**
 * Where the source is on the drawing just now, or null when this room has none to show.
 *
 * Worked out from the rule's own two numbers rather than stored beside them. A stored position
 * would be a third account of where the source is, and this project has met what happens when two
 * places hold the same fact: they agree until something writes one of them, and then the screen
 * and the sound disagree with nothing on either to say which is stale.
 *
 * Only the pan mode has a source a person puts anywhere. The rotation drives the same law from the
 * clock, so a dot there would be a control fighting the thing it is drawing; the split has no
 * source at all.
 */
private fun sourceSpotOf(state: RoomState, actions: RoomMapActions): SourceSpot? {
    if (actions.moveSource == null || state.mode != SpatialMode.PAN) return null
    return SpatialRoom.spotOf(state.pan, state.retreat, state.envelopment)
}

/**
 * The source: where the sound is coming from, as against where the handsets are.
 *
 * The line back to the listener is not decoration. Which side a source is on is legible from the
 * dot alone, and how far off it is is not - the radius is even in decibels rather than to scale,
 * so the eye has nothing to measure it against except the listener it is drawn from.
 */
private fun DrawScope.drawSource(spot: SourceSpot, colour: Color) {
    val centre = Offset(spot.x * size.width, spot.y * size.height)
    val middle = Offset(size.width / 2f, size.height / 2f)
    drawLine(colour.copy(alpha = SOURCE_LINE_ALPHA), middle, centre, strokeWidth = 2f)
    val radius = size.minDimension * 0.032f
    val halo = radius * 3f
    drawCircle(
        Brush.radialGradient(
            colors = listOf(colour.copy(alpha = 0.5f), colour.copy(alpha = 0f)),
            center = centre,
            radius = halo
        ),
        radius = halo,
        center = centre
    )
    drawCircle(colour, radius = radius, center = centre)
    // A ring outside the disc, so that the mark reads as something sounding rather than as one
    // more handset. The handsets are discs with numbers in them; this is the only ringed thing
    // on the drawing that is not a listener.
    drawCircle(
        colour.copy(alpha = 0.55f),
        radius = radius * 1.7f,
        center = centre,
        style = Stroke(width = 2f)
    )
}

/** How plainly the line from the listener to the source is drawn. A reach, not a wire. */
private const val SOURCE_LINE_ALPHA = 0.35f

/**
 * The listener, and which way they are facing.
 *
 * A named circle rather than the bare dot it was until 2026-09-18, and the size is the point of
 * it: the ring is drawn at [SpatialRoom.LISTENER_RADIUS], which is also the distance within which
 * a source counts as being on top of the listener. So the one place on this map where the sound
 * stops having a direction is a place a finger can actually be put, and it is exactly the shape
 * that is drawn there. A dot said the same thing and was one pixel wide.
 *
 * The facing mark is not decoration. Front and back are the one pair of directions a person cannot
 * tell apart by ear on this system - the gain law gives them nothing to distinguish - so the
 * drawing has to say which end of the screen is in front, or half of every arrangement is a
 * coin toss.
 */
private fun DrawScope.drawListener(colour: Color, word: String, measurer: TextMeasurer) {
    val middle = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension * SpatialRoom.LISTENER_RADIUS
    drawCircle(colour, radius = radius, center = middle, style = Stroke(width = 2f))
    val name = measurer.measure(word, TextStyle(fontSize = 11.sp, color = colour))
    drawText(
        name,
        topLeft = Offset(middle.x - name.size.width / 2f, middle.y - name.size.height / 2f)
    )
    // From the edge of the ring rather than from the middle of it, which a filled dot did not
    // have to care about: a line starting at the centre now runs through the word.
    val ahead = radius + size.minDimension * 0.035f
    drawLine(
        colour,
        Offset(middle.x, middle.y - radius),
        Offset(middle.x, middle.y - ahead),
        strokeWidth = 3f
    )
    val arrow = measurer.measure("↑", TextStyle(fontSize = 11.sp, color = colour))
    drawText(arrow, topLeft = Offset(middle.x + 6f, middle.y - ahead - arrow.size.height))
}

private fun DrawScope.drawHandset(
    icon: RoomIcon,
    colour: Color,
    labelColour: Color,
    /** The colour a kill is flagged in - see [StandbyLook.KILLED]. */
    dangerColour: Color,
    number: Int,
    /** Drawn as a ring when this icon is the handset in the listener's hand, or null when not. */
    selfRing: Color?,
    /** What this handset's icon is saying right now. See [StandbyLook]. */
    look: StandbyLook,
    /** The killed flash's current alpha, 0 outside of one. See [rememberKilledPulse]. */
    killedPulseAlpha: Float,
    measurer: TextMeasurer
) {
    val centre = Offset(icon.x * size.width, icon.y * size.height)
    val radius = size.minDimension * 0.06f
    // A halo in the handset's own colour, because the colour is the handset's name and the name
    // has to be readable from where the listener is sitting rather than from where the phone is.
    // A six percent disc is a few millimetres across a room; the same hue spread over three times
    // that is what the eye picks out, and it costs one draw call per icon.
    val halo = radius * 2.4f
    drawCircle(
        Brush.radialGradient(
            colors = listOf(colour.copy(alpha = 0.45f), colour.copy(alpha = 0f)),
            center = centre,
            radius = halo
        ),
        radius = halo,
        center = centre
    )
    // Three states rather than one hollow circle - see StandbyLook. Its colour is the handset's
    // name, so every one of them keeps the halo above and either fills or outlines in it; only
    // KILLED's ring departs from the handset's own colour, because that ring is not naming the
    // handset, it is naming the fault.
    when (look) {
        StandbyLook.FOLLOWING -> drawCircle(colour, radius = radius, center = centre)
        StandbyLook.GONE -> drawCircle(colour, radius = radius, center = centre, style = Stroke(width = 4f))
        StandbyLook.KILLED -> drawCircle(dangerColour, radius = radius, center = centre, style = Stroke(width = 4f))
    }
    if (killedPulseAlpha > 0f) {
        drawCircle(
            dangerColour.copy(alpha = killedPulseAlpha),
            radius = radius + KILLED_PULSE_RING_PX,
            center = centre,
            style = Stroke(width = 4f)
        )
    }
    // Which one is in your hand was said by the colour until the colour became the name. A ring
    // rather than a second hue, because a colour that meant both would have to give one of the
    // two up the moment a second handset joined - and the one it would give up is the name.
    selfRing?.let {
        drawCircle(it, radius = radius + 4f, center = centre, style = Stroke(width = 3f))
    }
    // The handset's number, which is the same number every other screen shows for it and the
    // same one anybody writing this down would use. Four characters of its real name were what
    // this said before, and nobody ever read one out loud.
    val hollow = look == StandbyLook.GONE || look == StandbyLook.KILLED
    val text = measurer.measure(
        "$number",
        TextStyle(fontSize = 10.sp, color = if (hollow) colour else labelColour)
    )
    drawText(
        text,
        topLeft = Offset(centre.x - text.size.width / 2f, centre.y - text.size.height / 2f)
    )
}

/**
 * Which half of the split this handset is carrying, written under its icon.
 *
 * The buttons say it too, but only for the one handset whose buttons are open - and the question
 * somebody actually has is about the room, not about one phone: which of these is on the voice.
 * Four taps to read four answers is not reading, so the answers are all on the map at once and
 * the buttons are only for changing one.
 */
private fun DrawScope.drawPartWord(
    icon: RoomIcon,
    word: String,
    colour: Color,
    measurer: TextMeasurer
) {
    val centre = Offset(icon.x * size.width, icon.y * size.height)
    val text = measurer.measure(word, TextStyle(fontSize = 9.sp, color = colour))
    drawText(
        text,
        topLeft = Offset(
            centre.x - text.size.width / 2f,
            centre.y + size.minDimension * HANDSET_RADIUS + PART_WORD_GAP_PX
        )
    )
}

/** The icon's own radius as a share of the drawing's side. What drawHandset draws. */
private const val HANDSET_RADIUS = 0.06f

/** Clear of the icon without floating away from it. */
private const val PART_WORD_GAP_PX = 4f

/** How far outside the icon's own radius the killed pulse ring sits, in pixels. */
private const val KILLED_PULSE_RING_PX = 6f

/**
 * The ring that spreads out from an icon the moment play is pressed.
 *
 * [progress] is 0f..1f: the radius grows and the alpha fades together, so the ring both expands
 * and disappears over the same 600ms rather than leaving a static circle behind once it stops
 * moving.
 */
private fun DrawScope.drawRipple(icon: RoomIcon, progress: Float, colour: Color) {
    val centre = Offset(icon.x * size.width, icon.y * size.height)
    val radius = size.minDimension * (0.06f + RIPPLE_SPREAD * progress)
    drawCircle(
        colour.copy(alpha = (1f - progress) * RIPPLE_MAX_ALPHA),
        radius = radius,
        center = centre,
        style = Stroke(width = 3f)
    )
}

private const val RIPPLE_SPREAD = 0.3f
private const val RIPPLE_MAX_ALPHA = 0.8f

/**
 * Which icon a finger landing here meant, or null when it landed on nothing.
 *
 * Internal rather than private so it can be tested: what it decides is which phone a drag moves,
 * and getting it wrong swaps two handsets - the one defect in this screen that a listener cannot
 * diagnose by ear, because a swapped pair sounds exactly like a working room.
 */
internal fun nearestPeerId(icons: List<RoomIcon>, x: Float, y: Float): String? = icons
    .minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
    ?.takeIf { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) <= GRAB_RADIUS }
    ?.peerId

/** What a finger landing on the drawing meant. */
internal sealed interface Grabbed {
    /** The source, which is the one thing here that is not a handset. */
    data object Source : Grabbed

    data class Handset(val peerId: String) : Grabbed
}

/**
 * Which of the things on the drawing a finger landing here meant, or null for none of them.
 *
 * The source wins a tie, and that is the only interesting line in it. The dot is drawn over the
 * handsets and a person reaches for what they can see; handing a drag to the icon underneath
 * instead would move a phone across the room in answer to a touch aimed at the sound. That failure
 * has been reported on this screen once already, from the other cause - see the rememberUpdatedState
 * note in RoomDrawing - and it reads to a listener as the drawing ignoring them.
 *
 * [source] is null wherever there is no source to grab, which makes this exactly [nearestPeerId].
 */
internal fun grabbedAt(
    icons: List<RoomIcon>,
    source: SourceSpot?,
    x: Float,
    y: Float
): Grabbed? {
    val handset = nearestPeerId(icons, x, y)
    val toSource = source
        ?.let { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
        ?.takeIf { it <= GRAB_RADIUS }
        ?: return handset?.let { Grabbed.Handset(it) }
    val nearest = handset ?: return Grabbed.Source
    val toHandset = icons.first { it.peerId == nearest }
        .let { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
    return if (toSource <= toHandset) Grabbed.Source else Grabbed.Handset(nearest)
}

/** How far from an icon a finger may land and still mean it. A fingertip on a phone screen. */
internal const val GRAB_RADIUS = 0.12

/**
 * How much of itself a handset keeps when the source has turned away from it.
 *
 * A slider rather than a number somebody picked, because what it is for is a thing only an ear
 * can judge and an ear judges "better" far more reliably than "how much". Only on screen for the
 * rotation: the split has no source to face away from, and 自定义声音位置 has this on the map,
 * where it is how far in from the handsets the dot has been dragged. Two controls for one number
 * is two controls that disagree, and the one to keep is the one somebody can point at.
 */
@Composable
private fun EnvelopmentSlider(state: RoomState, actions: RoomActions) {
    val range = 0f..MOST_ENVELOPMENT_ON_A_SLIDER
    Column {
        Knob(
            title = stringResource(R.string.room_envelopment),
            value = state.envelopment,
            readout = stringResource(R.string.room_knob_percent, knobPercent(state.envelopment, range)),
            range = range,
            onChange = actions.setEnvelopment
        )
        Note(
            stringResource(
                if (state.envelopment <= 0f) R.string.room_envelopment_off
                else R.string.room_envelopment_hint
            )
        )
    }
}


/**
 * How live the room is: how much of what a handset plays came off the walls rather than out of the
 * source.
 *
 * Read out as a share of the output rather than in decibels, because "a third of what you hear is
 * the room" is a thing a person can check by listening.
 *
 * The share is in the title rather than off to the right, and there is nothing under it. What was
 * under it was three sentences about how the reverberation is computed, which belong in the
 * feasibility notes: somebody who has opened the fine tuning wants to move a knob.
 *
 * In every mode, unlike the retreat: see [RoomState.reverb].
 */
@Composable
private fun ReverbSlider(state: RoomState, actions: RoomActions) {
    Knob(
        title = stringResource(
            R.string.room_reverb,
            state.reverb * RoomReverb.MOST_WET.toFloat() * 100f
        ),
        value = state.reverb,
        onChange = actions.setReverb
    )
}

/**
 * The app's own answer to "where is the sound", next to the knob that moves it.
 *
 * One number per handset, bigger where the sound should be. There used to be a second column for
 * which handset plays first, and it went on 2026-09-17 with the knobs that could move it: every
 * rule left in this room places a source by loudness, so a lead column would be a row of zeroes.
 *
 * Diagnostic, and shown only with the switch on. It is here because a listener cannot check an
 * effect they can only just hear against a claim nobody has written down - see [roomReadings].
 */
@Composable
private fun RuleReadings(readings: List<RoomReading>, colours: Map<String, Int>) {
    if (readings.isEmpty()) return
    Label(R.string.room_live)
    Readings(
        readings.map {
            listOf(it.peerId) to stringResource(R.string.room_live_row, it.loudness)
        },
        colours
    )
}

/** What a room ships at, which is where a listener put the slider rather than where zero is. */
const val DEFAULT_ENVELOPMENT = 0.25f

/**
 * How far the 包裹感 slider goes: half, where the rule itself goes all the way to one.
 *
 * At one every handset plays everything whatever the source is doing, which for the rotation is
 * the effect switched off - and a control whose far end switches off the thing it controls has a
 * trap at the end of it. The dot on the map may reach one because there it means something a
 * person can see: the source standing where the listener is.
 */
const val MOST_ENVELOPMENT_ON_A_SLIDER = 0.5f

/**
 * The list of named results, and under whichever one is chosen, what it is played with.
 *
 * Four rows, and every row is an answer to one question - where is the sound. A row that is not
 * chosen is its name and nothing else; the chosen one carries its own description and its own
 * controls, inside the same card. That shape is the whole of why the page stays legible: what
 * belongs to what is said by where it sits rather than by a sentence somebody has to read, and
 * switching rows moves a block of at most three lines from one card to another instead of
 * rearranging the screen.
 *
 * What is deliberately **not** in the list: how the song is divided up. That is not a fourth
 * place for a sound to be, it is a different question, and it lives inside the two rows that can
 * answer it. The other two move a source around, and a source is one thing going one place.
 */
@Composable
private fun EffectSection(
    state: RoomState,
    actions: RoomActions,
    readings: List<RoomReading> = emptyList()
) {
    val current = effectOf(state)
    Label(R.string.room_effect_title)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (effect in RoomEffect.entries) {
            EffectRow(effect, chosen = current == effect, state = state, actions = actions) {
                apply(effect, actions)
            }
        }
    }
    // Empty unless the diagnostic switch is on - see HomeActivity, which is where they are worked
    // out. Under the list rather than inside a card, because they are about the room and not
    // about any one row of it.
    RuleReadings(readings, state.colours)
    FineTuning(state, actions)
}

/**
 * One named result: its name, and when it is the chosen one, what it does and what moves it.
 *
 * Only clickable while it is **not** chosen. A chosen card holds sliders and segmented buttons,
 * and a card that is also a button would take the finger that was aiming at one of them.
 */
@Composable
private fun EffectRow(
    effect: RoomEffect,
    chosen: Boolean,
    state: RoomState,
    actions: RoomActions,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(
            if (chosen) Modifier else Modifier.clickable(onClick = onClick)
        ),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (chosen) 2.dp else 1.dp,
            if (chosen) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
            Text(
                stringResource(effect.title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!chosen) return@Column
            Text(
                stringResource(effect.line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            EffectOwn(effect, state, actions)
        }
    }
}

/**
 * The controls that belong to one effect and to no other.
 *
 * 自定义声音位置 has none, and that is not an omission: its control is the dot on the drawing
 * further up this page, which is already on screen and which appears the moment this row is
 * chosen. A second control here saying the same thing would be a second place for the answer to
 * be - see the line this row carries, which points at the dot instead.
 */
@Composable
private fun EffectOwn(effect: RoomEffect, state: RoomState, actions: RoomActions) {
    when (effect) {
        RoomEffect.UNISON, RoomEffect.STEREO -> ContentSplit(state, actions)
        RoomEffect.SPIN -> SpinSpeed(state, actions)
        RoomEffect.PLACE -> Unit
    }
}

/**
 * Whether the song comes apart between the handsets, and along which axis.
 *
 * Three buttons rather than a knob and a picker, because the question a person has is "分不分",
 * not "分多少". How far it goes is still there in the fine tuning, and it is still the way this
 * degrades - see [SeparationControl] - but it is not the first thing anybody is asked.
 *
 * Only under the two rows that stand still. A source being moved around the room is one thing
 * going one place, so there is nothing to hand out; the rule drops the split for those two modes
 * while this screen goes on remembering it - see HomeActivity.
 */
@Composable
private fun ContentSplit(state: RoomState, actions: RoomActions) {
    val split = state.separation > 0f
    Note(stringResource(R.string.room_split_label))
    Segmented(
        listOf(
            Segment(stringResource(R.string.room_split_none), chosen = !split) {
                actions.setSeparation(0f)
            },
            Segment(
                stringResource(R.string.room_split_voice),
                chosen = split && state.splitAxis == SplitAxis.MIDDLE_SIDES
            ) {
                actions.pickAxis(SplitAxis.MIDDLE_SIDES)
                actions.setSeparation(1f)
            },
            Segment(
                stringResource(R.string.room_split_bass),
                chosen = split && state.splitAxis == SplitAxis.LOW_HIGH
            ) {
                actions.pickAxis(SplitAxis.LOW_HIGH)
                actions.setSeparation(1f)
            }
        )
    )
    if (!split) return
    if (state.splitAxis == SplitAxis.LOW_HIGH) CrossoverSlider(state, actions)
    // Where the assignment is, rather than the assignment itself: it moved onto the map on
    // 2026-09-18 and a feature nothing points at is a feature nobody finds.
    Note(stringResource(R.string.room_parts_tap))
    // Said before it is heard rather than after: this sounds like a fault to somebody who was
    // told it separates instruments, and it is not one. One line for both axes, because what
    // goes wrong is the same on either - the split is by where a sound sits in the mix or by how
    // high it is, never by which instrument it is, and either way some songs come apart cleanly
    // and some do not.
    Note(stringResource(R.string.room_split_limits))
}

/** How long one circuit takes. Whole seconds, because that is how anybody would say it. */
@Composable
private fun SpinSpeed(state: RoomState, actions: RoomActions) {
    Knob(
        title = stringResource(R.string.room_spin_period),
        value = state.periodSeconds.toFloat(),
        readout = stringResource(R.string.room_spin_seconds, state.periodSeconds),
        range = SHORTEST_SPIN_SECONDS.toFloat()..LONGEST_SPIN_SECONDS.toFloat(),
        onChange = { actions.setPeriodSeconds(it.roundToInt()) }
    )
}

/** Fast enough to be a circuit rather than a stutter, slow enough to be a walk rather than a wait. */
const val SHORTEST_SPIN_SECONDS = 2
const val LONGEST_SPIN_SECONDS = 20

/**
 * Sets a room to one named result.
 *
 * Every knob the effect names is sent, including the ones it leaves at zero: an effect chosen
 * after another one has to undo that one, and a room carrying half of each is the state nobody
 * asked for and nothing on screen can name.
 *
 * The content split is **not** among them, deliberately - see [EffectSettings]. Somebody who has
 * told four phones which of them carry the voice and then tries the rotation for a minute has to
 * find that assignment where they left it.
 */
internal fun apply(effect: RoomEffect, actions: RoomActions) {
    val settings = effect.settings
    actions.pickMode(settings.mode)
    // Only where the effect names one. Under 自定义声音位置 this number is where the dot is, and
    // an effect that wrote it would put the dot back every time its row was tapped.
    settings.envelopment?.let(actions.setEnvelopment)
    actions.setReverb(settings.reverb)
}

/**
 * The knobs, folded away.
 *
 * Three of them now, and each is a taste rather than a thing to understand: how live the room is,
 * how much a handset keeps when the source turns away from it, and how far the split goes. Every
 * one of them has a default somebody listened to, so this is a fold for people who disagree with
 * a listener rather than a fold for people who have not read far enough.
 */
@Composable
private fun FineTuning(state: RoomState, actions: RoomActions) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Ghost(stringResource(if (open) R.string.room_fine_hide else R.string.room_fine)) {
            open = !open
        }
    }
    if (!open) return
    // First, because it is the one of the three that every effect reads and the only one an
    // effect sets for you. Somebody who came here came here for this.
    ReverbSlider(state, actions)
    // The rotation only. Under 自定义声音位置 the same number is how far in the dot has been
    // dragged, and a slider beside a map that both write one number is two controls that disagree.
    if (state.mode == SpatialMode.ROTATE) EnvelopmentSlider(state, actions)
    if (state.separation > 0f) SeparationControl(state, actions)
}

/**
 * How far the mix is pulled apart, once somebody has said that it is.
 *
 * A knob rather than a switch, and the reason is on screen as much as in the rule: the further it
 * goes the more the handsets depend on being in step with each other, so the way this degrades is
 * for the listener to wind it back until the room sounds right rather than for the app to decide.
 * The buttons that turn it on set it to all of it; this is where somebody who hears the seams
 * takes it back.
 */
@Composable
private fun SeparationControl(state: RoomState, actions: RoomActions) {
    Knob(
        title = stringResource(R.string.room_split_content),
        value = state.separation,
        readout = stringResource(R.string.room_knob_percent, knobPercent(state.separation, 0f..1f)),
        onChange = actions.setSeparation
    )
}

/**
 * One handset's two halves, put beside its icon on the drawing.
 *
 * Opened by tapping the icon and closed by either button or by a tap on bare canvas - see
 * [RoomMap]. Two named buttons rather than one that toggles, because a toggle only says what it
 * will become and a listener looking at a room wants to see what each phone is on.
 */
@Composable
private fun PartButtons(
    icon: RoomIcon,
    state: RoomState,
    togglePart: (String) -> Unit,
    side: Dp,
    onDone: () -> Unit
) {
    val carriesFarHalf = icon.peerId in state.otherHalfIds
    // Beside the icon rather than on it, and on whichever side there is room for: an icon near
    // the right edge would otherwise get buttons half off the drawing, and the edge is exactly
    // where the handsets in a real room end up.
    val clearance = side * (HANDSET_RADIUS + 0.03f)
    val wanted =
        if (icon.x < 0.55f) side * icon.x + clearance
        else side * icon.x - clearance - PART_ROW_WIDTH
    val limitX = (side - PART_ROW_WIDTH).coerceAtLeast(0.dp)
    val limitY = (side - PART_ROW_HEIGHT).coerceAtLeast(0.dp)
    Row(
        modifier = Modifier
            .offset(
                x = wanted.coerceIn(0.dp, limitX),
                y = (side * icon.y - PART_ROW_HEIGHT / 2).coerceIn(0.dp, limitY)
            )
            .width(PART_ROW_WIDTH)
            .height(PART_ROW_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp))
    ) {
        for (farHalf in listOf(false, true)) {
            val chosen = farHalf == carriesFarHalf
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (chosen) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable {
                        // The action underneath is a toggle and these are two named halves, so
                        // the one already in force does nothing rather than turning itself off.
                        // Pressing what a button already says has to be a no-op; anything else
                        // is a control that disagrees with its own label.
                        if (!chosen) togglePart(icon.peerId)
                        onDone()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(partLabelOf(state.splitAxis, farHalf)),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (chosen) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Two words of two characters each, and the same on both axes. */
private val PART_ROW_WIDTH = 96.dp
private val PART_ROW_HEIGHT = 30.dp

/** Which name a handset button carries, since the two halves are named by the axis they divide. */
private fun partLabelOf(axis: SplitAxis, farHalf: Boolean): Int = when (axis) {
    SplitAxis.MIDDLE_SIDES -> if (farHalf) R.string.room_part_sides else R.string.room_part_middle
    SplitAxis.LOW_HIGH -> if (farHalf) R.string.room_part_high else R.string.room_part_low
}

/** Where the low half stops. Read out in hertz because a slider with no number on it is a guess. */
@Composable
private fun CrossoverSlider(state: RoomState, actions: RoomActions) {
    Knob(
        // The number reads inside the sentence rather than off to the right, because on its own
        // "800 Hz" does not say which side of it is the low half.
        title = stringResource(R.string.room_crossover, state.crossoverHz.roundToInt()),
        value = state.crossoverHz,
        range = SpatialField.LOWEST_CROSSOVER_HZ.toFloat()..SpatialField.HIGHEST_CROSSOVER_HZ.toFloat(),
        onChange = actions.setCrossoverHz
    )
}