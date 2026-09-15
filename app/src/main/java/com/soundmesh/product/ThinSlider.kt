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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * A tap anywhere on the track is deliberately NOT a jump. Material treats a touch on the track as
 * a complete gesture and reports it exactly like a drag, which is how a sleeve brushing the screen
 * used to set the whole room's volume; here a tap does nothing at all.
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
    val shown = (dragging ?: percent).coerceIn(0, 100)
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
                    // Swallowed rather than acted on: see the note above about sleeves.
                    detectTapGestures { }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { at: Offset ->
                            dragging = ((at.x / size.width) * 100).toInt().coerceIn(0, 100)
                        },
                        onDragEnd = {
                            dragging?.let(onSet)
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                        onHorizontalDrag = { change, _ ->
                            dragging = ((change.position.x / size.width) * 100).toInt().coerceIn(0, 100)
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

/** Wide enough for a system device name to be recognisable, narrow enough to leave a track. */
private val NAME_WIDTH = 92.dp

/** Tall enough to catch a finger, though only two of its points are drawn. */
private val TOUCH_HEIGHT = 28.dp

private const val TRACK_PIXELS = 5f
private const val DOT_PIXELS = 11f
private const val HALO_PIXELS = 24f

private val PERCENT_WIDTH = 34.dp
