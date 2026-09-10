package com.soundmesh.product

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
        // Said quietly and never acted on. The measurement cannot know which side is left, so it
        // can never correct a drawing - only point at two icons and ask whether they are the right
        // way round. Acting on it would be the app overruling the one thing only a person knows.
        state.selfId?.let { self ->
            RoomCheck.contradiction(state.icons, self, state.measuredMetres)?.let { (farther, nearer) ->
                Text(
                    stringResource(
                        R.string.room_disagrees,
                        farther.take(4),
                        nearer.take(4)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
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
     * How far this handset measured itself from each peer it has calibrated with, in metres.
     *
     * Read when the roster changes rather than every pass: it comes off disk, and the roster is
     * re-read five times a second. Empty is the ordinary state - a pair nobody has calibrated has
     * no measurement, and neither has a room whose phones have only ever met this one.
     */
    val measuredMetres: Map<String, Double> = emptyMap(),
    val periodSeconds: Int = (SpatialField.DEFAULT_PERIOD_NANOS / 1_000_000_000L).toInt(),
    /** How far apart the mix is pulled, in the same 0..1 the rule uses. Zero is every handset playing all of it. */
    val separation: Float = 0f,
    /** Which way the mix is pulled apart. One at a time: the knob and the parts below mean whatever this says. */
    val splitAxis: SplitAxis = SplitAxis.MIDDLE_SIDES,
    /** Where the low half stops. Only on screen while the split runs along that axis. */
    val crossoverHz: Float = SpatialField.DEFAULT_CROSSOVER_HZ.toFloat(),
    /** Which handsets carry the sides. Everything in [icons] and not in here carries the middle. */
    val otherHalfIds: Set<String> = emptySet()
)

class RoomActions(
    val moveIcon: (RoomIcon) -> Unit,
    val pickMode: (SpatialMode) -> Unit,
    val setPan: (Float) -> Unit,
    val setSeparation: (Float) -> Unit,
    val pickAxis: (SplitAxis) -> Unit,
    val setCrossoverHz: (Float) -> Unit,
    val togglePart: (String) -> Unit
)

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
            drawHandset(icon, if (icon.peerId == state.selfId) self else handset, label, measurer)
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
    measurer: TextMeasurer
) {
    val centre = Offset(icon.x * size.width, icon.y * size.height)
    val radius = size.minDimension * 0.06f
    drawCircle(colour, radius = radius, center = centre)
    // The first four characters of the handset's own name. Enough to tell two phones apart in a
    // room, and the same prefix the calibration screens already show, so one screen's "3f2a" is
    // the other screen's "3f2a".
    val text = measurer.measure(
        icon.peerId.take(4),
        TextStyle(fontSize = 10.sp, color = labelColour)
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
                Text(
                    if (icon.peerId == state.selfId) stringResource(R.string.room_this_phone)
                    else icon.peerId.take(4),
                    style = MaterialTheme.typography.bodySmall
                )
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
