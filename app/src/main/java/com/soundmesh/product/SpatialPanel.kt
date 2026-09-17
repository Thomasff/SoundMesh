package com.soundmesh.product

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundmesh.core.PeerBadge
import com.soundmesh.core.SpatialField
import com.soundmesh.core.AlignmentAnalysis
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
 * The listener is a fixed dot in the middle and cannot be dragged. That is deliberate rather than
 * unfinished: whether a phone icon is where the phone is can be checked by looking at the room,
 * and whether the app believes the listener is sitting where they are sitting cannot be checked at
 * all - so the one position nobody can verify is the one position nobody is asked for.
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
        EffectSection(state, actions, showDetails, readings)
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
    RoomDrawing(state, actions, blockedPeerNames, ripple)
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
    Note(
        if (state.retreat <= 0f) stringResource(R.string.room_source_here)
        else stringResource(R.string.room_source_away, 10f.pow(decibels / 20f), decibels)
    )
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
    val moveSource: ((SourceSpot) -> Unit)? = null
)

/** What the screen draws about the room, and nothing it decides. */
data class RoomState(
    val icons: List<RoomIcon> = emptyList(),
    val mode: SpatialMode = SpatialMode.SPLIT,
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
     * How hard the handsets are pushed into playing different waveforms, in the rule's own 0..1.
     *
     * Off by default, unlike [envelopment]. Every other knob here rearranges a mix somebody already
     * likes; this one changes the sound of it, and a room that never asked should get the room it
     * had. What it is worth is a question only an ear answers, so it starts where it can be
     * compared against.
     */
    val diffusion: Float = 0f,
    /**
     * How far the far side of the room is held back, as a fraction of what the rule allows.
     *
     * Off, and off on purpose rather than by oversight. This is the one control here whose own
     * project notes argue against it - see [com.soundmesh.core.SpatialField.travelDelayNanos] and
     * the 09-14 note it points at - so it is offered rather than chosen, and the only way it gets
     * turned up is somebody deciding to hear what it does.
     */
    val travel: Float = 0f,
    /**
     * How far each handset's own output wanders, as a fraction of what the rule allows.
     *
     * The other half of the same machinery and the half with nothing against it. Off by default
     * for the same reason [diffusion] is: it changes the sound of a mix somebody already likes.
     */
    val shimmer: Float = 0f,
    /**
     * How fast that wander goes, from 0 at the slowest the rule allows to 1 at the fastest.
     *
     * Beside the depth rather than fixed behind it because the two are one control in two halves:
     * depth over period is the transposition a moving delay produces, so deep and fast together is
     * a tape wobble whether anybody wanted one or not. Somebody who finds the wobble has to be able
     * to trade it back rather than only to give up the depth.
     */
    val shimmerSpeed: Float = DEFAULT_SHIMMER_SPEED,
    /**
     * A fixed gap between this handset and every other, as a fraction of what the rule allows,
     * signed: negative is this one early, positive is the rest of the room early.
     *
     * Temporary instrumentation rather than an effect - see
     * [com.soundmesh.core.SpatialField.skewNanos] - so it is on screen only behind the diagnostic
     * switch, and it is not among the settings the saved drawing writes down. A control with no
     * name a listener would recognise should not be waiting for them the next time they open the
     * app with no memory of having set it.
     */
    val skew: Float = 0f,
    /**
     * Whether the gap also carries the room away, rather than only saying which side it is on.
     *
     * Not a second knob: with this on, [skew] sets the gap and the room's loudness at once - level
     * in the middle and quieter towards either end - so one drag moves the two cues a listener is
     * being asked to hear as one thing. Off is the same drag with the loudness left alone, which
     * makes the pair of settings the comparison rather than a pair of controls.
     *
     * Temporary on the same terms as [skew]: behind the diagnostic switch, absent from the saved
     * drawing, back off tomorrow.
     */
    val skewCarriesDistance: Boolean = false,
    /**
     * How far off the source itself is, from 0 where it stands to 1 at the furthest this allows.
     * Every handset together - see [com.soundmesh.core.SpatialField.retreat].
     *
     * A separate control from [skew] and deliberately so. Which side a sound is on is the
     * difference between the handsets; how far off it is, is what they have in common. A gap that
     * also turned the room down would be describing a source that moves sideways and outwards at
     * once, which is a path somebody might want but not one a slider should pick for them.
     *
     * Temporary on the same terms as [skew]: behind the diagnostic switch, absent from the saved
     * drawing, back at zero tomorrow.
     */
    val retreat: Float = 0f,
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
    val setDiffusion: (Float) -> Unit,
    val setTravel: (Float) -> Unit,
    val setShimmer: (Float) -> Unit,
    val setShimmerSpeed: (Float) -> Unit,
    /** The hand set gap, -1 for this handset earliest to +1 for the rest of the room earliest. */
    val setSkew: (Float) -> Unit,
    val setSkewCarriesDistance: (Boolean) -> Unit,
    val setRetreat: (Float) -> Unit,
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
        moveSource = { spot ->
            setPan(SpatialRoom.panOf(spot))
            setRetreat(SpatialRoom.retreatOf(spot))
        }
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
        // Said once and only while it is true, because it is the one thing on this screen a
        // person cannot find out by looking: the drawing shows where the phones are and has
        // never shown where they are sitting, so an unmeasured listener looks exactly like a
        // measured one that happens to be in the middle.
        Note(
            stringResource(
                if (overheadRoundCanPlaceTheListener(state.icons)) R.string.room_listener_unmeasured
                else R.string.room_listener_needs_three
            )
        )
        actions.measureListener?.takeIf { overheadRoundCanPlaceTheListener(state.icons) }?.let { go ->
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

@Composable
private fun RoomDrawing(
    state: RoomState,
    actions: RoomMapActions,
    blockedPeerNames: List<String>,
    ripple: Float?
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
    val measurer = rememberTextMeasurer()
    val listener = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val handset = MaterialTheme.colorScheme.primary
    val self = MaterialTheme.colorScheme.tertiary
    val source = MaterialTheme.colorScheme.secondary
    val label = MaterialTheme.colorScheme.onPrimary
    val danger = MaterialTheme.colorScheme.error
    // One of four states per icon rather than one hollow circle - see StandbyLook. Worked out here,
    // in composable scope rather than inside the Canvas below, because the killed pulse needs
    // remembered animation state that a DrawScope cannot hold.
    //
    // screenOn is always true: nothing on this host's side yet carries a standing handset's own
    // screen state back to the room drawing (HandsetMoment.screenOn is read on calibration runs,
    // not sent up this channel), so ASLEEP is unreachable until that wiring exists. True is the
    // documented fallback for "not known" - see loudnessOf's neighbour in EdgeGlow.kt for the same
    // convention - which is why this is not a guess so much as the honest default for a signal that
    // is not there yet.
    val looks = HashMap<String, StandbyLook>(state.icons.size)
    val killedPulses = HashMap<String, Float>(state.icons.size)
    for (icon in state.icons) {
        val look = standbyLook(
            connected = icon.peerId !in state.silentIds,
            screenOn = true,
            saidNotExempt = RoomCommands.nameOf(icon.peerId)?.let { it in blockedPeerNames } == true
        )
        looks[icon.peerId] = look
        killedPulses[icon.peerId] = key(icon.peerId) { rememberKilledPulse(look) }
    }
    val frame = MaterialTheme.colorScheme.surfaceVariant
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
                    ) ?: return@awaitEachGesture
                    // Touch slop, so that a tap on an icon is still a tap: without it every
                    // stray pixel of a press moves a handset, and the room drifts under anybody
                    // who rests a finger on it.
                    val began = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    } ?: return@awaitEachGesture
                    put(held, began.position)
                    drag(down.id) { change ->
                        change.consume()
                        put(held, change.position)
                    }
                }
            }
    ) {
        drawCircle(outline, radius = size.minDimension / 2f, style = Stroke(width = 2f))
        drawListener(listener, measurer)
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
    return SpatialRoom.spotOf(state.pan, state.retreat)
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
 * The facing mark is not decoration. Front and back are the one pair of directions a person cannot
 * tell apart by ear on this system - the gain law gives them nothing to distinguish - so the
 * drawing has to say which end of the screen is in front, or half of every arrangement is a
 * coin toss.
 */
private fun DrawScope.drawListener(colour: Color, measurer: TextMeasurer) {
    val middle = Offset(size.width / 2f, size.height / 2f)
    drawCircle(colour, radius = size.minDimension * 0.02f, center = middle)
    val ahead = size.minDimension * 0.055f
    drawLine(colour, middle, Offset(middle.x, middle.y - ahead), strokeWidth = 3f)
    val text = measurer.measure("↑", TextStyle(fontSize = 11.sp, color = colour))
    drawText(text, topLeft = Offset(middle.x + 6f, middle.y - ahead - text.size.height))
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
    // Four states rather than one hollow circle - see StandbyLook. Its colour is the handset's
    // name, so every one of them keeps the halo above and either fills or outlines in it; only
    // KILLED's ring departs from the handset's own colour, because that ring is not naming the
    // handset, it is naming the fault.
    when (look) {
        StandbyLook.FOLLOWING -> drawCircle(colour, radius = radius, center = centre)
        // Dimmed rather than hollowed: it is still following, only its screen is dark, and drawing
        // it exactly like a dropped handset is what cost an hour telling the two apart on
        // 2026-09-14.
        StandbyLook.ASLEEP -> drawCircle(colour.copy(alpha = ASLEEP_ALPHA), radius = radius, center = centre)
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

/** How dim [StandbyLook.ASLEEP] draws: still following, only its screen has gone dark. */
private const val ASLEEP_ALPHA = 0.45f

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
 * two modes that move a source - the split has no source to face away from.
 */
@Composable
private fun EnvelopmentSlider(state: RoomState, actions: RoomActions) {
    val range = 0f..SpatialField.MAX_ENVELOPMENT.toFloat()
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
 * How hard every handset is pushed into playing a different waveform from the others.
 *
 * On screen in every mode, which is the difference between this and the envelopment slider above.
 * The mix being pulled apart is not what makes the room collapse onto the nearest handset - playing
 * the identical waveform is, and the ordinary setting with the separation knob at zero is exactly
 * that.
 *
 * Stops rather than a continuous drag, and the reason is arithmetic rather than taste: this is a
 * chain of allpass sections, and fading one in against the dry signal is a comb filter. Whole
 * sections colour nothing; half of one colours everything. See Decorrelator.
 */
@Composable
private fun DiffusionSlider(state: RoomState, actions: RoomActions) {
    Column {
        Knob(
            title = stringResource(R.string.room_diffusion),
            value = state.diffusion,
            readout = stringResource(R.string.room_knob_percent, knobPercent(state.diffusion, 0f..1f)),
            steps = DIFFUSION_STOPS,
            onChange = actions.setDiffusion
        )
        Note(
            stringResource(
                if (state.diffusion <= 0f) R.string.room_diffusion_off
                else R.string.room_diffusion_hint
            )
        )
    }
}

/** The stops between off and all of it, which is one fewer than the filter has sections. */
private const val DIFFUSION_STOPS = 3

/**
 * How far the sound really travels, rather than how loudly each handset says it has.
 *
 * On the front of the panel next to the pan slider rather than down in the fine tuning, because
 * for the two modes that move a source it is the thing they are played with - and because it is
 * the one control here whose effect nobody, including this project's own notes, can predict
 * without hearing it. Something to be turned up and listened to has to be somewhere it can be
 * found.
 *
 * The line under it says what to listen for on the way up, which is not "more": past a point the
 * image stops travelling between handsets and starts belonging to one of them and then the next.
 */
@Composable
private fun TravelSlider(state: RoomState, actions: RoomActions) {
    Column {
        Knob(
            title = stringResource(R.string.room_travel),
            value = state.travel,
            readout = stringResource(R.string.room_knob_percent, knobPercent(state.travel, 0f..1f)),
            onChange = actions.setTravel
        )
        Note(
            stringResource(
                if (state.travel <= 0f) R.string.room_travel_off else R.string.room_travel_hint
            )
        )
    }
}

/**
 * Each handset drifting on its own, which is the one thing the decorrelator cannot do.
 *
 * Two sliders and not one. How far it wanders and how fast it wanders multiply into a pitch shift -
 * see [com.soundmesh.core.SpatialField.MAX_SHIMMER_DELAY_NANOS] - so a single "more" knob would
 * reach a point where the only way to lose the wobble is to give up the depth that caused it. The
 * speed is only on screen once the depth is up, because on its own it does nothing.
 */
@Composable
private fun ShimmerSlider(state: RoomState, actions: RoomActions) {
    Column {
        Knob(
            title = stringResource(R.string.room_shimmer),
            value = state.shimmer,
            readout = stringResource(R.string.room_knob_percent, knobPercent(state.shimmer, 0f..1f)),
            onChange = actions.setShimmer
        )
        if (state.shimmer > 0f) {
            Knob(
                title = stringResource(R.string.room_shimmer_speed),
                value = state.shimmerSpeed,
                readout = stringResource(R.string.room_knob_percent, knobPercent(state.shimmerSpeed, 0f..1f)),
                onChange = actions.setShimmerSpeed
            )
        }
        Note(
            stringResource(
                if (state.shimmer <= 0f) R.string.room_shimmer_off else R.string.room_shimmer_hint
            )
        )
    }
}

/**
 * A gap between this handset and the rest that a person sets to a number and leaves there.
 *
 * Temporary, and behind the diagnostic switch with the readings it is meant to be read beside.
 * What it is for is written at [com.soundmesh.core.SpatialField.skewNanos]: every other delay in
 * this room is a function of something that will not hold still, so a listener asked what a few
 * milliseconds do to a room has been asked to hear an amount that is never twice the same.
 *
 * Free to drag and rounded to whole milliseconds on the way past. Nothing needs the rounding -
 * the field is nanoseconds and the delay line reads fractional samples - but the answer wanted
 * from this is "at what gap does it start, and at what gap does it become an echo", which is a
 * number somebody has to read off, write down, and set again tomorrow. Rounding is what keeps
 * the figure on the screen and the gap in the room the same figure; drawn stops would only have
 * decided in advance how fine the answer is allowed to be.
 *
 * Untouched by the effect list above, unlike every other knob on this screen. Somebody comparing
 * what a fixed gap does across the three modes should not have it silently zeroed by the change
 * of mode they are making the comparison with.
 *
 * Carries the room's distance with it when [RoomState.skewCarriesDistance] is set: see
 * [RoomState.retreat] for why that rides here rather than on a knob of its own.
 */
@Composable
private fun SkewSlider(state: RoomState, actions: RoomActions) {
    val millis = (state.skew * SKEW_MILLIS).roundToInt()
    Column {
        Knob(
            title = stringResource(R.string.room_skew),
            value = state.skew,
            readout = when {
                millis < 0 -> stringResource(R.string.room_skew_self, -millis)
                millis > 0 -> stringResource(R.string.room_skew_others, millis)
                else -> stringResource(R.string.room_skew_together)
            },
            range = -1f..1f,
            onChange = { actions.setSkew(wholeMillisOf(it)) }
        )
        Note(stringResource(R.string.room_skew_hint))
        Segmented(
            listOf(
                Segment(
                    stringResource(R.string.room_skew_side_only),
                    chosen = !state.skewCarriesDistance
                ) { actions.setSkewCarriesDistance(false) },
                Segment(
                    stringResource(R.string.room_skew_with_distance),
                    chosen = state.skewCarriesDistance
                ) { actions.setSkewCarriesDistance(true) }
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
        Note(
            if (state.skewCarriesDistance) {
                stringResource(R.string.room_skew_distance_on, abs(millis), state.skewMetres)
            } else {
                stringResource(R.string.room_skew_distance_off)
            }
        )
    }
}

/**
 * How far off the source itself has been put, which is the room's level and nothing else.
 *
 * Under [SkewSlider] because the two are read together and separate because they answer different
 * questions. The gap says which side; this says how far. A listener can hear which of the two
 * moved only while each of them moves one thing.
 *
 * Read out as a multiple of where the room stands rather than in decibels alone, because "four
 * times as far away" is a thing somebody can picture and check against the room they are sitting
 * in, and "twelve decibels" is not.
 */
@Composable
private fun RetreatSlider(state: RoomState, actions: RoomActions) {
    val decibels = state.retreat * SpatialField.RETREAT_DECIBELS.toFloat()
    Column {
        Knob(
            title = stringResource(R.string.room_retreat),
            value = state.retreat,
            readout =
                if (state.retreat <= 0f) stringResource(R.string.room_retreat_here)
                else stringResource(
                    R.string.room_retreat_away, 10f.pow(decibels / 20f), decibels
                ),
            onChange = actions.setRetreat
        )
        Note(stringResource(R.string.room_retreat_hint))
    }
}

/**
 * How far back the gap stands for, in metres, which is the one part of this a screen can state on
 * its own.
 *
 * How much quieter that makes the late handset depends on how far off it is standing, which lives
 * in the rule and not here. How far back it has been moved does not: a delay is a distance at 343
 * metres a second and nothing else goes into it.
 */
internal val RoomState.skewMetres: Float
    get() = abs(skew) * SKEW_MILLIS / 1000f * AlignmentAnalysis.SPEED_OF_SOUND_M_S.toFloat()

/** How many milliseconds either end of the gap slider is, which is what the readout counts in. */
private val SKEW_MILLIS = SpatialField.MAX_SKEW_NANOS / 1_000_000L

/** Where the finger is, as the nearest whole millisecond of gap. See [SkewSlider]. */
private fun wholeMillisOf(value: Float): Float =
    (value * SKEW_MILLIS).roundToInt() / SKEW_MILLIS.toFloat()

/**
 * The app's own answer to "where is the sound", next to the knob that moves it.
 *
 * Both numbers are bigger where the sound should be, by the room's two different routes: the
 * loudest handset, and the earliest one. Which of the two is moving says which cue the rule is
 * currently steering with - wind the travel knob up and the loudness numbers stop moving while
 * the leads start to, which is that knob's whole content stated as two columns of digits.
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
            listOf(it.peerId) to stringResource(R.string.room_live_row, it.loudness, it.leadMillis)
        },
        colours
    )
}

/** Where the speed slider sits when nobody has moved it: eight seconds a turn. */
const val DEFAULT_SHIMMER_SPEED = 0.8f

/** What a room ships at, which is where a listener put the slider rather than where zero is. */
const val DEFAULT_ENVELOPMENT = 0.25f

/**
 * What the room can be asked to sound like, and the way to the knobs underneath.
 *
 * A list of named results rather than the five knobs that produce them - see [RoomEffect] for
 * what was wrong with the knobs as the front page. Under the list is exactly one control: the one
 * the chosen effect is actually played with, which is the pan slider for a source somebody drags
 * and the part assignment for anything split between handsets. Everything else is behind 细调,
 * unchanged, because a knob somebody has learnt to use is not a knob to take away.
 */
@Composable
private fun EffectSection(
    state: RoomState,
    actions: RoomActions,
    showDetails: Boolean = false,
    readings: List<RoomReading> = emptyList()
) {
    val current = effectOf(state)
    Label(R.string.room_effect_title)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (effect in RoomEffect.entries) {
            EffectRow(effect, chosen = current == effect) { apply(effect, actions) }
        }
    }
    // Said rather than hidden: somebody who moved a slider is between two of these, and a list
    // with nothing lit up and no explanation reads as the list having broken.
    if (current == null) Note(stringResource(R.string.room_effect_custom))
    if (state.mode == SpatialMode.PAN) PanSlider(state, actions)
    if (state.mode != SpatialMode.SPLIT) {
        TravelSlider(state, actions)
        RuleReadings(readings, state.colours)
    }
    // Above the fine tuning rather than inside it, and in every mode including the split, because
    // the one thing it asks about is a gap between two handsets - which a room has whether or not
    // there is a source being moved around it.
    if (showDetails) {
        SkewSlider(state, actions)
        // With the pan mode, because that is the mode the source dot is in and this is the same
        // number written the other way round. Anywhere else it would be a control that survives
        // leaving the screen it belongs to: a room left quiet by a dot, in a mode with no dot on
        // it to put back - see HomeActivity, where the rule is given the same condition.
        if (state.mode == SpatialMode.PAN) RetreatSlider(state, actions)
        if (state.mode == SpatialMode.SPLIT) RuleReadings(readings, state.colours)
    }
    if (state.separation > 0f) PartPicker(state, actions)
    FineTuning(state, actions, readings)
}

/** One named result: what it is called, what it does in a line, and whether it is the one on. */
@Composable
private fun EffectRow(effect: RoomEffect, chosen: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (chosen) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
            Text(
                stringResource(effect.title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (chosen) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(effect.line),
                style = MaterialTheme.typography.bodySmall,
                color = if (chosen) MaterialTheme.colorScheme.surface.copy(alpha = CHOSEN_LINE_ALPHA)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** How much quieter the line under a chosen effect's name is than the name. */
private const val CHOSEN_LINE_ALPHA = 0.78f

/**
 * Sets a room to one named result.
 *
 * Every knob the effect names is sent, including the ones it leaves at zero: an effect chosen
 * after another one has to undo that one, and a room carrying half of each is the state nobody
 * asked for and nothing on screen can name.
 */
private fun apply(effect: RoomEffect, actions: RoomActions) {
    val settings = effect.settings
    actions.pickMode(settings.mode)
    actions.setSeparation(settings.separation)
    actions.pickAxis(settings.axis)
    actions.setEnvelopment(settings.envelopment)
    actions.setDiffusion(settings.diffusion)
    actions.setTravel(settings.travel)
    actions.setShimmer(settings.shimmer)
    actions.setShimmerSpeed(settings.shimmerSpeed)
}

/**
 * The knobs, folded away.
 *
 * Nothing here is new and nothing here has been taken out. What changed is that it is no longer
 * the first thing on the screen: five controls with a paragraph each, above the list of things
 * they are for.
 */
@Composable
private fun FineTuning(
    state: RoomState,
    actions: RoomActions,
    readings: List<RoomReading> = emptyList()
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Ghost(stringResource(if (open) R.string.room_fine_hide else R.string.room_fine)) {
            open = !open
        }
    }
    if (!open) return
    ModePicker(state, actions)
    if (state.mode != SpatialMode.SPLIT) EnvelopmentSlider(state, actions)
    SeparationControl(state, actions)
    DiffusionSlider(state, actions)
    ShimmerSlider(state, actions)
    // Beside the slider rather than once at the bottom of the screen, and beside the travel
    // slider too. The reason is that it is read with a finger already on a control: a number
    // somewhere else on a page that has to be scrolled is a number nobody checks while dragging.
    RuleReadings(readings, state.colours)
}

@Composable
private fun ModePicker(state: RoomState, actions: RoomActions) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Segmented(
            SpatialMode.entries.map { mode ->
                Segment(
                    stringResource(
                        when (mode) {
                            SpatialMode.ROTATE -> R.string.room_mode_rotate
                            SpatialMode.PAN -> R.string.room_mode_pan
                            SpatialMode.SPLIT -> R.string.room_mode_split
                        }
                    ),
                    chosen = mode == state.mode
                ) { actions.pickMode(mode) }
            }
        )
        Note(
            when (state.mode) {
                SpatialMode.ROTATE -> stringResource(R.string.room_mode_rotate_hint, state.periodSeconds)
                SpatialMode.PAN -> stringResource(R.string.room_mode_pan_hint)
                SpatialMode.SPLIT -> stringResource(R.string.room_mode_split_hint)
            }
        )
    }
}

@Composable
private fun PanSlider(state: RoomState, actions: RoomActions) {
    Column {
        Knob(
            title = stringResource(R.string.room_pan_title),
            value = state.pan,
            range = -1f..1f,
            onChange = actions.setPan
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Note(stringResource(R.string.room_pan_left))
            Note(stringResource(R.string.room_pan_right))
        }
    }
}

/**
 * The knob that pulls the mix apart, and who gets which half of it.
 *
 * Beside the mode rather than inside it, because it answers a different question: the mode says
 * where a handset stands in the room, this says which part of the song it carries there. Either
 * is useful without the other, and the two compose - a room can rotate what it has separated.
 *
 * A knob rather than a switch, and the reason is on screen as much as in the rule: the further it
 * goes the more the handsets depend on being in step with each other, so the way this degrades is
 * for the listener to wind it back until the room sounds right rather than for the app to decide.
 */
@Composable
private fun SeparationControl(state: RoomState, actions: RoomActions) {
    Column {
        Knob(
            title = stringResource(R.string.room_split_content),
            value = state.separation,
            readout = stringResource(R.string.room_knob_percent, knobPercent(state.separation, 0f..1f)),
            onChange = actions.setSeparation
        )
        if (state.separation <= 0f) {
            Note(stringResource(R.string.room_split_content_off))
            return@Column
        }
        AxisPicker(state, actions)
        if (state.splitAxis == SplitAxis.LOW_HIGH) CrossoverSlider(state, actions)
    }
}

/**
 * Which handset carries which half, for whichever axis the room is split along.
 *
 * On the front of the panel rather than down among the knobs, because it is the one control a
 * split effect is played with: the effect decides that the song comes apart, and this decides
 * which phone gets which piece of it. Every handset in the room has a row, this one included.
 */
@Composable
private fun PartPicker(state: RoomState, actions: RoomActions) {
    Label(R.string.room_parts)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (icon in state.icons) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgeChip(icon.peerId, state.colours[icon.peerId], diameter = PART_BADGE)
                    if (icon.peerId == state.selfId) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.room_this_phone),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Chip(stringResource(partLabelOf(state.splitAxis, icon.peerId in state.otherHalfIds))) {
                    actions.togglePart(icon.peerId)
                }
            }
        }
    }
    // Said before it is heard rather than after: this sounds like a fault to somebody who was
    // told it separates instruments, and it is not one.
    Note(
        stringResource(
            when (state.splitAxis) {
                SplitAxis.MIDDLE_SIDES -> R.string.room_split_content_limits
                SplitAxis.LOW_HIGH -> R.string.room_split_low_high_limits
            }
        )
    )
}

/** Big enough to read a number on beside a line of text, small enough not to be a button. */
private val PART_BADGE = 19.dp

/** Which name a handset button carries, since the two halves are named by the axis they divide. */
private fun partLabelOf(axis: SplitAxis, farHalf: Boolean): Int = when (axis) {
    SplitAxis.MIDDLE_SIDES -> if (farHalf) R.string.room_part_sides else R.string.room_part_middle
    SplitAxis.LOW_HIGH -> if (farHalf) R.string.room_part_high else R.string.room_part_low
}

/**
 * Which way the mix is pulled apart - by where a sound sits in the image, or by how fast it moves.
 *
 * One at a time, and below the knob rather than beside the mode, because the knob is the thing
 * that decides whether any of this is happening: with it at zero there is no axis to choose.
 */
@Composable
private fun AxisPicker(state: RoomState, actions: RoomActions) {
    Segmented(
        SplitAxis.entries.map { axis ->
            Segment(
                stringResource(
                    when (axis) {
                        SplitAxis.MIDDLE_SIDES -> R.string.room_axis_middle_sides
                        SplitAxis.LOW_HIGH -> R.string.room_axis_low_high
                    }
                ),
                chosen = axis == state.splitAxis
            ) { actions.pickAxis(axis) }
        },
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** Where the low half stops. Read out in hertz because a slider with no number on it is a guess. */
@Composable
private fun CrossoverSlider(state: RoomState, actions: RoomActions) {
    Knob(
        title = stringResource(R.string.room_crossover),
        value = state.crossoverHz,
        readout = stringResource(R.string.room_crossover_hz, state.crossoverHz.roundToInt()),
        range = SpatialField.LOWEST_CROSSOVER_HZ.toFloat()..SpatialField.HIGHEST_CROSSOVER_HZ.toFloat(),
        onChange = actions.setCrossoverHz
    )
}
