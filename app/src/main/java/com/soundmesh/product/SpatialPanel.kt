package com.soundmesh.product

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
fun SpatialPanel(state: RoomState, actions: RoomActions) {
    Section(R.string.room_title) {
        Text(stringResource(R.string.room_hint), style = MaterialTheme.typography.bodySmall)
        RoomDrawing(state, actions)
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
        MeasuredDistances(state)
        FitOffer(state, actions)
        ModePicker(state, actions)
        if (state.mode == SpatialMode.PAN) PanSlider(state, actions)
        SeparationControl(state, actions)
    }
}

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
    val pickMode: (SpatialMode) -> Unit,
    val setPan: (Float) -> Unit,
    val setSeparation: (Float) -> Unit,
    val pickAxis: (SplitAxis) -> Unit,
    val setCrossoverHz: (Float) -> Unit,
    val togglePart: (String) -> Unit
)

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
internal fun fitOffer(state: RoomState): List<RoomIcon>? {
    if (state.fitted) return null
    if (RoomCheck.contradiction(state.icons, state.measuredMetres) != null) return null
    return RoomFit.corrected(state.icons, state.measuredMetres)
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
@Composable
private fun MeasuredDistances(state: RoomState) {
    val shown = measuredLines(state.icons, state.measuredMetres)
    if (shown.isEmpty()) return
    Text(
        stringResource(
            R.string.room_measured,
            shown.joinToString("   ") { (names, metres) ->
                "${PeerBadge.numberOf(names.first)}-${PeerBadge.numberOf(names.second)} " +
                    "%.2f".format(metres)
            }
        ),
        style = MaterialTheme.typography.bodySmall
    )
}

/**
 * The one button on this screen that changes the drawing without a finger on an icon.
 *
 * A button rather than a snap that happens on its own. The person put those icons there, and
 * something that quietly moves them afterwards reads as the app arguing; offered instead, the
 * before and the after are both theirs to look at. It goes under the measured lengths because
 * those lengths are what it acts on.
 */
@Composable
private fun FitOffer(state: RoomState, actions: RoomActions) {
    if (state.fitted) {
        Text(stringResource(R.string.room_fit_done), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (fitOffer(state) == null) return
    OutlinedButton(onClick = actions.fitToMeasured) {
        Text(stringResource(R.string.room_fit))
    }
}

@Composable
private fun RoomDrawing(state: RoomState, actions: RoomActions) {
    // Which icon the finger picked up, held for the whole gesture. Re-choosing the nearest icon on
    // every drag event would let a fast drag hand itself to whichever one it passed over.
    var dragging by remember { mutableStateOf<String?>(null) }
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
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(state.icons.map { it.peerId }) {
                detectDragGestures(
                    onDragStart = { at ->
                        dragging = nearestPeerId(room.icons, at.x / size.width, at.y / size.height)
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null }
                ) { change, _ ->
                    change.consume()
                    val held = dragging ?: return@detectDragGestures
                    onRoom.moveIcon(
                        SpatialRoom.clamped(
                            RoomIcon(
                                held,
                                (change.position.x / size.width).coerceIn(0.02f, 0.98f),
                                (change.position.y / size.height).coerceIn(0.02f, 0.98f)
                            )
                        )
                    )
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
                PeerBadge.numberOf(icon.peerId),
                if (icon.peerId == state.selfId) self else null,
                icon.peerId in state.silentIds,
                measurer
            )
        }
    }
}

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
    number: Int,
    /** Drawn as a ring when this icon is the handset in the listener's hand, or null when not. */
    selfRing: Color?,
    /** True for a handset in the room that is not being sent audio: one that has stopped. */
    silent: Boolean,
    measurer: TextMeasurer
) {
    val centre = Offset(icon.x * size.width, icon.y * size.height)
    val radius = size.minDimension * 0.06f
    // Hollow rather than a different colour or a missing icon. Its colour is its name, so it has
    // to stay - and an icon that vanished would say the handset left the room, which is a
    // different thing from one that is here and silent, and points at a different phone to pick
    // up. Empty is what the eye reads as nothing coming out of it.
    if (silent) drawCircle(colour, radius = radius, center = centre, style = Stroke(width = 4f))
    else drawCircle(colour, radius = radius, center = centre)
    // Which one is in your hand was said by the colour until the colour became the name. A ring
    // rather than a second hue, because a colour that meant both would have to give one of the
    // two up the moment a second handset joined - and the one it would give up is the name.
    selfRing?.let {
        drawCircle(it, radius = radius + 4f, center = centre, style = Stroke(width = 3f))
    }
    // The handset's number, which is the same number every other screen shows for it and the
    // same one anybody writing this down would use. Four characters of its real name were what
    // this said before, and nobody ever read one out loud.
    val text = measurer.measure(
        "$number",
        TextStyle(fontSize = 10.sp, color = if (silent) colour else labelColour)
    )
    drawText(
        text,
        topLeft = Offset(centre.x - text.size.width / 2f, centre.y - text.size.height / 2f)
    )
}

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
