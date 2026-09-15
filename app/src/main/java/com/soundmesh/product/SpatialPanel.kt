package com.soundmesh.product

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundmesh.core.PeerBadge
import com.soundmesh.core.SpatialField
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
    /** Names standing handsets have reported as not exempt from power saving. See [StandbyLook]. */
    blockedPeerNames: List<String> = emptyList(),
    /** The just-pressed-play ripple's progress, 0f..1f, or null while nothing is animating. */
    ripple: Float? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note(stringResource(R.string.room_hint))
        MeasuredRoom(state, actions.onTheMap(), blockedPeerNames, ripple)
        ArrivalDelays(state, actions)
        ModePicker(state, actions)
        if (state.mode != SpatialMode.SPLIT) EnvelopmentSlider(state, actions)
        if (state.mode == SpatialMode.PAN) PanSlider(state, actions)
        SeparationControl(state, actions)
        DiffusionSlider(state, actions)
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
    ListenerDistances(state, actions)
    MeasuredDistances(state)
    FitOffer(state, actions)
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
    val measureListener: (() -> Unit)?
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
    val pickAxis: (SplitAxis) -> Unit,
    val setCrossoverHz: (Float) -> Unit,
    val togglePart: (String) -> Unit
) {
    fun onTheMap() = RoomMapActions(moveIcon, fitToMeasured, measureListener)
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
private fun ArrivalDelays(state: RoomState, actions: RoomActions) {
    val shown = delayLines(state.icons, state.metresPerUnit)
    if (shown.isEmpty()) return
    OutlinedButton(onClick = { actions.setDelayCompensation(!state.delayCompensation) }) {
        Text(
            stringResource(
                if (state.delayCompensation) R.string.room_delay_on else R.string.room_delay_off
            )
        )
    }
    if (!state.delayCompensation) return
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
        Text(
            stringResource(
                if (overheadRoundCanPlaceTheListener(state.icons)) R.string.room_listener_unmeasured
                else R.string.room_listener_needs_three
            ),
            style = MaterialTheme.typography.bodySmall
        )
        actions.measureListener?.takeIf { overheadRoundCanPlaceTheListener(state.icons) }?.let { go ->
            OutlinedButton(onClick = go) {
                Text(stringResource(R.string.room_measure_listener))
            }
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
        Text(stringResource(R.string.room_fit_done), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (fitOffer(state) == null) return
    // Solid, and that is the whole signal. Hollow, it read as an option somebody might take; what
    // it actually means is that the drawing and the measurement have parted - which only happens
    // because a finger moved an icon - and the state the button is in IS the notice that they have.
    Button(onClick = actions.fitToMeasured) {
        Text(stringResource(R.string.room_fit))
    }
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
                fun put(held: String, at: Offset) = onRoom.moveIcon(
                    SpatialRoom.clamped(
                        RoomIcon(
                            held,
                            (at.x / size.width).coerceIn(0.02f, 0.98f),
                            (at.y / size.height).coerceIn(0.02f, 0.98f)
                        )
                    )
                )
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
                    val held = nearestPeerId(
                        room.icons,
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
    Column {
        Text(stringResource(R.string.room_envelopment), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = state.envelopment,
            onValueChange = actions.setEnvelopment,
            valueRange = 0f..SpatialField.MAX_ENVELOPMENT.toFloat()
        )
        Text(
            stringResource(
                if (state.envelopment <= 0f) R.string.room_envelopment_off
                else R.string.room_envelopment_hint
            ),
            style = MaterialTheme.typography.bodySmall
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
        Text(stringResource(R.string.room_diffusion), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = state.diffusion,
            onValueChange = actions.setDiffusion,
            valueRange = 0f..1f,
            steps = DIFFUSION_STOPS
        )
        Text(
            stringResource(
                if (state.diffusion <= 0f) R.string.room_diffusion_off
                else R.string.room_diffusion_hint
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** The stops between off and all of it, which is one fewer than the filter has sections. */
private const val DIFFUSION_STOPS = 3

/** What a room ships at, which is where a listener put the slider rather than where zero is. */
const val DEFAULT_ENVELOPMENT = 0.25f

@Composable
private fun ModePicker(state: RoomState, actions: RoomActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (mode in SpatialMode.entries) {
            val label = stringResource(
                when (mode) {
                    SpatialMode.ROTATE -> R.string.room_mode_rotate
                    SpatialMode.PAN -> R.string.room_mode_pan
                    SpatialMode.SPLIT -> R.string.room_mode_split
                }
            )
            if (mode == state.mode) {
                Button(onClick = { actions.pickMode(mode) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            } else {
                OutlinedButton(onClick = { actions.pickMode(mode) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            }
        }
    }
    Text(
        when (state.mode) {
            SpatialMode.ROTATE -> stringResource(R.string.room_mode_rotate_hint, state.periodSeconds)
            SpatialMode.PAN -> stringResource(R.string.room_mode_pan_hint)
            SpatialMode.SPLIT -> stringResource(R.string.room_mode_split_hint)
        },
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun PanSlider(state: RoomState, actions: RoomActions) {
    Column {
        Slider(value = state.pan, onValueChange = actions.setPan, valueRange = -1f..1f)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.room_pan_left), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.room_pan_right), style = MaterialTheme.typography.bodySmall)
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
        Text(stringResource(R.string.room_split_content), style = MaterialTheme.typography.bodySmall)
        Slider(value = state.separation, onValueChange = actions.setSeparation, valueRange = 0f..1f)
        if (state.separation <= 0f) {
            Text(stringResource(R.string.room_split_content_off), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        AxisPicker(state, actions)
        if (state.splitAxis == SplitAxis.LOW_HIGH) CrossoverSlider(state, actions)
        for (icon in state.icons) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgeChip(icon.peerId, state.colours[icon.peerId])
                    if (icon.peerId == state.selfId) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.room_this_phone),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                OutlinedButton(onClick = { actions.togglePart(icon.peerId) }) {
                    Text(stringResource(partLabelOf(state.splitAxis, icon.peerId in state.otherHalfIds)))
                }
            }
        }
        // Said before it is heard rather than after. Every one of these sounds like a fault to
        // somebody who was told this separates instruments, and none of them is one.
        Text(
            stringResource(
                when (state.splitAxis) {
                    SplitAxis.MIDDLE_SIDES -> R.string.room_split_content_limits
                    SplitAxis.LOW_HIGH -> R.string.room_split_low_high_limits
                }
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (axis in SplitAxis.entries) {
            val label = stringResource(
                when (axis) {
                    SplitAxis.MIDDLE_SIDES -> R.string.room_axis_middle_sides
                    SplitAxis.LOW_HIGH -> R.string.room_axis_low_high
                }
            )
            if (axis == state.splitAxis) {
                Button(onClick = { actions.pickAxis(axis) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            } else {
                OutlinedButton(onClick = { actions.pickAxis(axis) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            }
        }
    }
}

/** Where the low half stops. Read out in hertz because a slider with no number on it is a guess. */
@Composable
private fun CrossoverSlider(state: RoomState, actions: RoomActions) {
    Column {
        Slider(
            value = state.crossoverHz,
            onValueChange = actions.setCrossoverHz,
            valueRange = SpatialField.LOWEST_CROSSOVER_HZ.toFloat()..SpatialField.HIGHEST_CROSSOVER_HZ.toFloat()
        )
        Text(
            stringResource(R.string.room_crossover_at, state.crossoverHz.roundToInt()),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
