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
        ModePicker(state, actions)
        if (state.mode == SpatialMode.PAN) PanSlider(state, actions)
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
    val periodSeconds: Int = (SpatialField.DEFAULT_PERIOD_NANOS / 1_000_000_000L).toInt()
)

class RoomActions(
    val moveIcon: (RoomIcon) -> Unit,
    val pickMode: (SpatialMode) -> Unit,
    val setPan: (Float) -> Unit
)

@Composable
private fun RoomDrawing(state: RoomState, actions: RoomActions) {
    // Which icon the finger picked up, held for the whole gesture. Re-choosing the nearest icon on
    // every drag event would let a fast drag hand itself to whichever one it passed over.
    var dragging by remember { mutableStateOf<String?>(null) }
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
                        dragging = nearestPeerId(state.icons, at.x / size.width, at.y / size.height)
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null }
                ) { change, _ ->
                    change.consume()
                    val held = dragging ?: return@detectDragGestures
                    actions.moveIcon(
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
