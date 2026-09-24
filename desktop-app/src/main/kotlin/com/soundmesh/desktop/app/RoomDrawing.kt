package com.soundmesh.desktop.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundmesh.core.BadgeHues
import com.soundmesh.core.PeerBadge
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import com.soundmesh.product.Grabbed
import com.soundmesh.product.RoomIcon
import com.soundmesh.product.RoomState
import com.soundmesh.product.SourceSpot
import com.soundmesh.product.SpatialRoom
import com.soundmesh.product.grabbedAt
import com.soundmesh.product.movesASource

/** What can be done on the drawing; null for a drawing that is only looked at, as a sink's is. */
class DrawingActions(
    val moveIcon: (RoomIcon) -> Unit,
    val moveSource: (SourceSpot) -> Unit,
    val togglePart: (String) -> Unit
)

/**
 * The room, drawn the way the handset host draws it (SpatialPanel's RoomDrawing): the listener in
 * the middle facing up, one disc per device in its colour with its number, hollow for one that has
 * stopped, a ring round this machine's own, and the source's dot under 自定义声音位置.
 *
 * With [actions], a device is dragged to where it stands and the dot to where the sound should be.
 * A click on a device swaps which half of the split it carries, where the handset opens two
 * buttons: a mouse has no finger covering the icon, and the word under it already says which half.
 *
 * [ripple] is the handset's just-started ring round this machine's own icon, 0..1 while it spreads
 * - see [rememberRipple].
 *
 * [killed] are the hollow ones that said their own power saving was not letting SoundMesh be - the
 * handset host's StandbyLook.KILLED: ringed in the error colour rather than their own, since that
 * ring names the fault and not the device, and flashed twice the moment they become it.
 */
@Composable
fun RoomDrawing(room: RoomState, actions: DrawingActions?, ripple: Float? = null, killed: Set<String> = emptySet()) {
    // Read through here rather than keyed on: the gesture outlives the recompositions that move
    // the icons, and would otherwise pick up whatever was nearest where the icons used to be -
    // the handset drawing's reason, word for word.
    val current by rememberUpdatedState(room)
    val onRoom by rememberUpdatedState(actions)
    val measurer = rememberTextMeasurer()
    val listener = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val fallback = MaterialTheme.colorScheme.primary
    val self = MaterialTheme.colorScheme.tertiary
    val source = MaterialTheme.colorScheme.secondary
    val frame = MaterialTheme.colorScheme.surfaceVariant
    val danger = MaterialTheme.colorScheme.error
    // Out here rather than in the Canvas, as on the handset: a flash is remembered animation state,
    // which a DrawScope cannot hold.
    val pulses = room.icons.associate { icon ->
        icon.peerId to key(icon.peerId) { rememberKilledPulse(icon.peerId in killed) }
    }
    val english = LocalEnglish.current
    var modifier = Modifier
        .size(DRAWING_SIDE)
        .border(1.dp, frame, RoundedCornerShape(10.dp))
    if (actions != null) {
        modifier = modifier.pointerInput(room.icons.map { it.peerId }) {
            fun put(held: Grabbed, at: Offset) {
                val x = (at.x / size.width).coerceIn(0.02f, 0.98f)
                val y = (at.y / size.height).coerceIn(0.02f, 0.98f)
                val act = onRoom ?: return
                when (held) {
                    is Grabbed.Source -> act.moveSource(SourceSpot(x, y))
                    is Grabbed.Handset -> act.moveIcon(SpatialRoom.clamped(RoomIcon(held.peerId, x, y)))
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Chosen once and held for the whole drag, so a fast one does not hand itself to
                // whatever icon it passes over.
                val held = grabbedAt(
                    current.icons,
                    sourceSpotOf(current),
                    down.position.x / size.width,
                    down.position.y / size.height
                ) ?: return@awaitEachGesture
                // No slop: the handset's is there for a finger that drifts while it rests, and a
                // resting mouse does not drift. Compose's touch slop applies to a mouse as well,
                // and left the icon lying still under the first stretch of every drag.
                down.consume()
                val press = Press(down.position, centreOf(held, current, size.width, size.height) ?: down.position)
                val lifted = drag(down.id) { change ->
                    change.consume()
                    put(held, press.follow(change.position))
                }
                if (lifted && press.wasClick && held is Grabbed.Handset && splitting(current)) {
                    onRoom?.togglePart(held.peerId)
                }
            }
        }
    }
    Canvas(modifier) {
        drawCircle(outline, radius = size.minDimension / 2f, style = Stroke(width = 2f))
        drawListener(listener, Phrases.room_you.of(english), measurer)
        for (icon in room.icons) {
            val place = room.colours[icon.peerId]
            val hue = place?.let { BadgeHues.argb.getOrNull(it) }?.let { Color(it) } ?: fallback
            val label = if (place?.let { BadgeHues.whiteLabel.getOrNull(it) } != false) Color.White else Color.Black
            drawDevice(
                icon,
                hue,
                label,
                PeerBadge.numberOf(icon.peerId),
                selfRing = if (icon.peerId == room.selfId) self else null,
                hollow = icon.peerId in room.silentIds,
                ring = danger.takeIf { icon.peerId in killed },
                pulse = pulses[icon.peerId] ?: 0f,
                danger = danger,
                measurer = measurer
            )
            if (splitting(room)) {
                drawPartWord(icon, partWord(room.splitAxis, icon.peerId in room.otherHalfIds).of(english), listener, measurer)
            }
        }
        sourceSpotOf(room)?.let { drawSource(it, source) }
        if (ripple != null) {
            room.icons.firstOrNull { it.peerId == room.selfId }?.let { own ->
                val place = room.colours[own.peerId]
                drawRipple(own, ripple, place?.let { BadgeHues.argb.getOrNull(it) }?.let { Color(it) } ?: fallback)
            }
        }
    }
}

/**
 * The handset's PlayingScreen ripple: 0..1 over [RIPPLE_MILLIS] each time [running] turns true,
 * and null while nothing is playing, when "just started" has nothing to be said beside. Keyed on
 * the state rather than on a click, so a sink ripples when its host starts the room.
 */
@Composable
fun rememberRipple(running: Boolean): Float? {
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        ripple.snapTo(0f)
        ripple.animateTo(1f, animationSpec = tween(RIPPLE_MILLIS))
    }
    return ripple.value.takeIf { running }
}

/** The handset's rememberKilledPulse: two red flashes the moment [killed] turns true, and its alpha. */
@Composable
private fun rememberKilledPulse(killed: Boolean): Float {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(killed) {
        if (!killed) return@LaunchedEffect
        repeat(KILLED_PULSE_REPEATS) {
            pulse.animateTo(1f, animationSpec = tween(KILLED_PULSE_MILLIS))
            pulse.animateTo(0f, animationSpec = tween(KILLED_PULSE_MILLIS))
        }
    }
    return pulse.value
}

/**
 * One press on the drawing, in pixels. What was grabbed keeps the grip it was taken by, so it
 * moves with the first pixel of travel and never jumps to put its middle under the pointer.
 */
internal class Press(private val down: Offset, private val grabbedCentre: Offset) {
    private var farthest = 0f

    /** Where the grabbed thing's middle goes with the pointer at [at]. */
    fun follow(at: Offset): Offset {
        farthest = maxOf(farthest, (at - down).getDistance())
        return grabbedCentre + (at - down)
    }

    /** Released without really travelling: a click, which swaps a device's half. */
    val wasClick: Boolean get() = farthest < CLICK_TRAVEL_PX
}

/** Where [held] is drawn now, in pixels. */
private fun centreOf(held: Grabbed, room: RoomState, width: Int, height: Int): Offset? {
    val (x, y) = when (held) {
        is Grabbed.Source -> sourceSpotOf(room)?.let { it.x to it.y }
        is Grabbed.Handset -> room.icons.firstOrNull { it.peerId == held.peerId }?.let { it.x to it.y }
    } ?: return null
    return Offset(x * width, y * height)
}

/** Whether the room is splitting the song, which is when a device's half means anything. */
fun splitting(room: RoomState): Boolean = room.separation > 0f && !room.mode.movesASource

/** The handset's words for the two halves. */
internal fun partWord(axis: SplitAxis, farHalf: Boolean): Phrase = when (axis) {
    SplitAxis.MIDDLE_SIDES -> if (farHalf) Phrases.room_part_sides else Phrases.room_part_middle
    SplitAxis.LOW_HIGH -> if (farHalf) Phrases.room_part_high else Phrases.room_part_low
}

/** Where the source is drawn, from the rule's own numbers; only 自定义声音位置 has one to drag. */
private fun sourceSpotOf(room: RoomState): SourceSpot? =
    if (room.mode != SpatialMode.PAN) null else SpatialRoom.spotOf(room.pan, room.retreat, room.envelopment)

private fun DrawScope.drawListener(colour: Color, you: String, measurer: TextMeasurer) {
    val middle = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension * SpatialRoom.LISTENER_RADIUS
    drawCircle(colour, radius = radius, center = middle, style = Stroke(width = 2f))
    val word = measurer.measure(you, TextStyle(fontSize = 11.sp, color = colour))
    drawText(word, topLeft = Offset(middle.x - word.size.width / 2f, middle.y - word.size.height / 2f))
    // Which way is in front, which the ear cannot tell from behind on this system.
    val ahead = radius + size.minDimension * 0.035f
    drawLine(colour, Offset(middle.x, middle.y - radius), Offset(middle.x, middle.y - ahead), strokeWidth = 3f)
    val arrow = measurer.measure("↑", TextStyle(fontSize = 11.sp, color = colour))
    drawText(arrow, topLeft = Offset(middle.x + 6f, middle.y - ahead - arrow.size.height))
}

private fun DrawScope.drawDevice(
    icon: RoomIcon,
    colour: Color,
    labelColour: Color,
    number: Int,
    selfRing: Color?,
    hollow: Boolean,
    /** The hollow ring's colour when it is not the device's own - see RoomDrawing's killed. */
    ring: Color?,
    /** The killed flash's alpha, 0 outside of one. */
    pulse: Float,
    danger: Color,
    measurer: TextMeasurer
) {
    val centre = Offset(icon.x * size.width, icon.y * size.height)
    val radius = size.minDimension * DEVICE_RADIUS
    val halo = radius * 2.4f
    drawCircle(
        Brush.radialGradient(listOf(colour.copy(alpha = 0.45f), colour.copy(alpha = 0f)), center = centre, radius = halo),
        radius = halo,
        center = centre
    )
    if (hollow) drawCircle(ring ?: colour, radius = radius, center = centre, style = Stroke(width = 4f))
    else drawCircle(colour, radius = radius, center = centre)
    if (pulse > 0f) {
        drawCircle(danger.copy(alpha = pulse), radius = radius + KILLED_PULSE_RING_PX, center = centre, style = Stroke(width = 4f))
    }
    selfRing?.let { drawCircle(it, radius = radius + 4f, center = centre, style = Stroke(width = 3f)) }
    val text = measurer.measure("$number", TextStyle(fontSize = 10.sp, color = if (hollow) colour else labelColour))
    drawText(text, topLeft = Offset(centre.x - text.size.width / 2f, centre.y - text.size.height / 2f))
}

private fun DrawScope.drawPartWord(icon: RoomIcon, word: String, colour: Color, measurer: TextMeasurer) {
    val centre = Offset(icon.x * size.width, icon.y * size.height)
    val text = measurer.measure(word, TextStyle(fontSize = 9.sp, color = colour))
    drawText(text, topLeft = Offset(centre.x - text.size.width / 2f, centre.y + size.minDimension * DEVICE_RADIUS + 4f))
}

private fun DrawScope.drawSource(spot: SourceSpot, colour: Color) {
    val centre = Offset(spot.x * size.width, spot.y * size.height)
    val middle = Offset(size.width / 2f, size.height / 2f)
    drawLine(colour.copy(alpha = 0.35f), middle, centre, strokeWidth = 2f)
    val radius = size.minDimension * 0.032f
    val halo = radius * 3f
    drawCircle(
        Brush.radialGradient(listOf(colour.copy(alpha = 0.5f), colour.copy(alpha = 0f)), center = centre, radius = halo),
        radius = halo,
        center = centre
    )
    drawCircle(colour, radius = radius, center = centre)
    drawCircle(colour.copy(alpha = 0.55f), radius = radius * 1.7f, center = centre, style = Stroke(width = 2f))
}

/** The handset's drawRipple: the ring grows and fades together, so nothing is left behind. */
private fun DrawScope.drawRipple(icon: RoomIcon, progress: Float, colour: Color) {
    val centre = Offset(icon.x * size.width, icon.y * size.height)
    val radius = size.minDimension * (DEVICE_RADIUS + RIPPLE_SPREAD * progress)
    drawCircle(colour.copy(alpha = (1f - progress) * RIPPLE_MAX_ALPHA), radius = radius, center = centre, style = Stroke(width = 3f))
}

/** The handset's ripple, unchanged: how long, how far out as a share of the side, how strong. */
private const val RIPPLE_MILLIS = 600
private const val RIPPLE_SPREAD = 0.3f
private const val RIPPLE_MAX_ALPHA = 0.8f

/** The handset's killed flash, unchanged: how many, each half of one, and how far outside the icon. */
private const val KILLED_PULSE_REPEATS = 2
private const val KILLED_PULSE_MILLIS = 150
private const val KILLED_PULSE_RING_PX = 6f

/** The handset drawing's icon radius, as a share of the side. */
private const val DEVICE_RADIUS = 0.06f

/**
 * How far a press may wander and still be a click: Compose's own allowance for a mouse (its touch
 * slop of 18 dp times 1/8), in pixels since that is what a hand's wobble is measured in.
 */
private const val CLICK_TRAVEL_PX = 3f

/** Square, as on the handset: a place is a fraction of the width and of the height alike. */
private val DRAWING_SIDE = 360.dp
