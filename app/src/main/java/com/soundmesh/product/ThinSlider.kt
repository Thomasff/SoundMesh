package com.soundmesh.product

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
            drawLine(empty, Offset(0f, y), Offset(size.width, y), strokeWidth = TRACK_PIXELS)
            drawLine(colour, Offset(0f, y), Offset(at, y), strokeWidth = TRACK_PIXELS)
            // The same halo the room drawing puts round a handset's icon, so that a glance from
            // one to the other lands on the same phone. See SpatialPanel's drawHandset.
            drawCircle(colour.copy(alpha = 0.22f), radius = HALO_PIXELS, center = Offset(at, y))
            drawCircle(colour, radius = DOT_PIXELS, center = Offset(at, y))
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

/** Wide enough for a system device name to be recognisable, narrow enough to leave a track. */
private val NAME_WIDTH = 92.dp

/** Tall enough to catch a finger, though only two of its points are drawn. */
private val TOUCH_HEIGHT = 28.dp

private const val TRACK_PIXELS = 5f
private const val DOT_PIXELS = 11f
private const val HALO_PIXELS = 24f

private val PERCENT_WIDTH = 34.dp
