package com.soundmesh.product

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A two-point track with a glowing dot on it, in the handset's own colour.
 *
 * Hand-drawn rather than a Material slider, and the reason is the colour rather than the height: a
 * Material thumb takes one colour from the scheme, and the whole point of these rows is that the
 * dot on this one matches the icon on the room drawing for the same phone. Somebody glancing down
 * from the drawing has to be able to say which row is which without reading a name.
 *
 * A drag reports at its end and not through it - see [RoomVolumePanel]'s own note: one drag is
 * fifty values, and each one told to the room is a frame to every handset and a thread to send it
 * on. The dot still moves under the finger, because one that does not is a broken control.
 *
 * A tap on the track lands the dot there. It used to be swallowed - Material reports a touch on a
 * track exactly as it reports a drag, so a sleeve across the screen could set the whole room - but
 * refusing every tap costs more than it saves: a row that only answers to a drag reads as broken,
 * which is what it was reported as on 2026-09-15. What a tap cannot do is start music or empty a
 * queue; the worst it buys is a volume somebody sets back.
 */
@Composable
fun VolumeLine(
    name: String,
    percent: Int,
    colour: Color,
    strong: Boolean = false,
    complaint: String? = null,
    onSet: (Int) -> Unit
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    // What was last asked for, and what this row was reading at the moment it was asked. Held
    // together because one without the other cannot say whether the answer has come back yet -
    // see [volumeShown].
    var asked by remember { mutableStateOf<Int?>(null) }
    var before by remember { mutableStateOf<Int?>(null) }
    var asks by remember { mutableStateOf(0) }
    LaunchedEffect(asks) {
        if (asks == 0) return@LaunchedEffect
        delay(VOLUME_GRACE_MILLIS)
        asked = null
        before = null
    }
    val ask: (Int) -> Unit = { value ->
        before = percent
        asked = value
        asks++
        onSet(value)
    }
    // Held live, because the gesture handlers below are remembered against Unit and would
    // otherwise go on calling the very first one of these - with the very first [percent] in it.
    val askNow by rememberUpdatedState(ask)
    val shown = volumeShown(dragging, asked, before, percent)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            name,
            modifier = Modifier.width(NAME_WIDTH),
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
            color = if (strong) MaterialTheme.colorScheme.onSurface else colour
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(TOUCH_HEIGHT)
                .pointerInput(Unit) {
                    detectTapGestures { at -> askNow(trackPercent(at.x, size.width.toFloat())) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { at: Offset ->
                            dragging = trackPercent(at.x, size.width.toFloat())
                        },
                        onDragEnd = {
                            dragging?.let(askNow)
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                        onHorizontalDrag = { change, _ ->
                            dragging = trackPercent(change.position.x, size.width.toFloat())
                        }
                    )
                }
        ) {
            val y = size.height / 2f
            val at = size.width * (shown / 100f)
            drawLine(empty, Offset(0f, y), Offset(size.width, y), strokeWidth = TRACK.toPx())
            drawLine(colour, Offset(0f, y), Offset(at, y), strokeWidth = TRACK.toPx())
            // The same halo the room drawing puts round a handset's icon, so that a glance from
            // one to the other lands on the same phone. See SpatialPanel's drawHandset.
            drawCircle(colour.copy(alpha = 0.22f), radius = HALO.toPx(), center = Offset(at, y))
            drawCircle(colour, radius = DOT.toPx(), center = Offset(at, y))
        }
        Text(
            "$shown%",
            modifier = Modifier.width(PERCENT_WIDTH),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    // Under the row rather than in place of the number, because the number is still true: it is
    // what the handset says it is at, and the complaint is about it not being where it was told.
    complaint?.let { Note(it, Tone.WRONG) }
}

/**
 * Where a finger at [x] on a track [width] across is pointing.
 *
 * Clamped rather than trusted: a horizontal drag goes on being reported after the finger has left
 * the track, and a drag that ended past the right-hand edge used to ask for 104%.
 */
internal fun trackPercent(x: Float, width: Float): Int =
    if (width <= 0f) 0 else ((x / width) * 100).toInt().coerceIn(0, 100)

/**
 * Which of the three numbers in play this row draws.
 *
 * A handset's volume is the handset's own reading, sent back over the wire - so between letting go
 * of the dot and that reading arriving, the row has nothing to draw but the old one, and the dot
 * snapped back to where it started and returned a moment later. Reported on 2026-09-15, and it is
 * the shape memory calls "控件位置画的是别人的答案": the fix is not downstream, it is holding the
 * asked-for number on screen for exactly as long as the reading is still crossing the room.
 *
 * It is a hold and never a lie. The moment the reported number moves at all - to what was asked
 * for, or to the nearest step this handset actually has, or to something a person at that phone
 * pressed a volume key for - the hold is over and the reading wins. And it is only ever a hold:
 * [VolumeLine]'s timer drops it after [VOLUME_GRACE_MILLIS] whatever happens, which is the same
 * silence [volumeComplaint] keeps before it will call a handset out for not moving.
 */
internal fun volumeShown(dragging: Int?, asked: Int?, before: Int?, reported: Int): Int = when {
    dragging != null -> dragging.coerceIn(0, 100)
    asked != null && before == reported -> asked.coerceIn(0, 100)
    else -> reported.coerceIn(0, 100)
}

/**
 * A knob, drawn the way [VolumeLine] is drawn, with its number beside its name.
 *
 * Every control on the spatial panel used to be a Material slider, which is a different object in
 * the same column as the volume rows: taller, with a thumb the size of a fingertip and a track in
 * the accent colour. This one is the same two-point track with a dot on it, in ink rather than in
 * a handset's hue - the hues mean "this particular phone" and nothing here is about one phone.
 *
 * The number on the right is the point of it as much as the drawing is. Six of these knobs had no
 * number at all, and the one that did says why in its own comment: a slider with no number on it
 * is a guess, and the question people bring to this screen is "at what setting does it start".
 *
 * Reported all the way through the drag, unlike [VolumeLine]. That is not an oversight either -
 * the room follows a finger here (see HomeActivity's updateRoom: the control channel holds one
 * rule and a new one replaces what is waiting), and these are knobs somebody turns while listening
 * to what they do.
 */
@Composable
fun Knob(
    title: String,
    value: Float,
    readout: String? = null,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    // Held live: the gesture handlers below are remembered against Unit, so a captured lambda or
    // a captured range would go on answering with the first one this knob was ever given.
    val change by rememberUpdatedState(onChange)
    val bounds by rememberUpdatedState(range)
    val stops by rememberUpdatedState(steps)
    val ink = MaterialTheme.colorScheme.onSurface
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            readout?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOUCH_HEIGHT)
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        change(knobValue(at.x, size.width.toFloat(), bounds, stops))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { moved, _ ->
                        change(knobValue(moved.position.x, size.width.toFloat(), bounds, stops))
                    }
                }
        ) {
            val y = size.height / 2f
            val at = size.width * knobFraction(value, bounds)
            // Filled from wherever zero is rather than from the left edge. Two of these knobs run
            // either side of zero, and a bar that always grows from the left says "less of this"
            // at the setting that means "neither way".
            val origin = size.width * knobFraction(0f, bounds)
            drawLine(empty, Offset(0f, y), Offset(size.width, y), strokeWidth = TRACK.toPx())
            drawLine(ink, Offset(minOf(origin, at), y), Offset(maxOf(origin, at), y), strokeWidth = TRACK.toPx())
            // Drawn where the value can actually land, so that a dot between two marks is a dot
            // somebody is still dragging rather than a control that missed.
            if (stops > 0) {
                for (mark in 0..(stops + 1)) {
                    val x = size.width * (mark.toFloat() / (stops + 1))
                    drawCircle(empty, radius = STOP.toPx(), center = Offset(x, y))
                }
            }
            drawCircle(ink, radius = DOT.toPx(), center = Offset(at, y))
        }
    }
}

/**
 * What a finger at [x] on a track [width] across is asking for.
 *
 * Clamped rather than trusted, for the reason [trackPercent] is: a horizontal drag goes on being
 * reported after the finger has left the track.
 *
 * [steps] counts the stops BETWEEN the two ends, the way Material's slider counts them, so that
 * the knob that had three of them still has three.
 */
internal fun knobValue(
    x: Float,
    width: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0
): Float {
    if (width <= 0f) return range.start
    val fraction = (x / width).coerceIn(0f, 1f)
    val intervals = steps + 1
    val landed = if (steps <= 0) fraction else (fraction * intervals).roundToInt().toFloat() / intervals
    return range.start + landed * (range.endInclusive - range.start)
}

/** Where along the track a value sits, from 0 at the left end to 1 at the right. */
internal fun knobFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return if (span <= 0f) 0f else ((value - range.start) / span).coerceIn(0f, 1f)
}

/** The same position as a whole number, for the knobs whose readout is just "how far along". */
internal fun knobPercent(value: Float, range: ClosedFloatingPointRange<Float>): Int =
    (knobFraction(value, range) * 100).roundToInt()

/**
 * Long enough for a handset to hear, set its stream and answer across a room. Also how long
 * HomeScreen's volumeComplaint keeps quiet after an ask.
 */
internal const val VOLUME_GRACE_MILLIS = 1_500L

/** Wide enough for a system device name to be recognisable, narrow enough to leave a track. */
private val NAME_WIDTH = 92.dp

/** Tall enough to catch a finger, though only two of its points are drawn. */
private val TOUCH_HEIGHT = 28.dp

/*
 * In points, since 2026-09-24. They were pixels, drawn on handsets at 3 and 3.5 pixels a point, and
 * the same pixels on a computer at one or one and a quarter are a dot two or three times the size.
 * These are those pixels at about 3.2 a point, so neither handset moves by more than a pixel.
 */
private val TRACK = 1.6.dp
private val DOT = 3.5.dp
private val HALO = 7.5.dp

/** Small enough to read as a mark on the track rather than as a second dot on it. */
private val STOP = 1.dp

private val PERCENT_WIDTH = 34.dp
