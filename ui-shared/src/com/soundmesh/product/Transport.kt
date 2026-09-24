package com.soundmesh.product

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/** Which of the four the button is at this moment. */
enum class TransportIcon { PREVIOUS, PLAY, PAUSE, NEXT }

/**
 * The four transport buttons, drawn rather than typed.
 *
 * They were characters until 2026-09-15 - "⏮ ⏸ ▶ ⏭" - and on a real handset the font decides what
 * a character looks like. It picked the emoji face for the pause and the text face for the play,
 * so the middle button turned orange and twice the weight of the two beside it while its other
 * half stayed a plain dark triangle. Nothing in the app could correct that: an emoji glyph carries
 * its own colour and ignores the one it is asked to be. Four shapes on a canvas take the colour
 * they are given, which is the only way these four can look like one set.
 *
 * The middle one is [filled]: a disc in the ink colour with the shape knocked out of it, the way
 * the drawing of 2026-09-15 has it. The other two are bare, and go quiet rather than disappear
 * when there is no queue to step through - see PlayControls.
 */
@Composable
fun Transport(
    icon: TransportIcon,
    enabled: Boolean,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val quiet = MaterialTheme.colorScheme.surfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    val disc = if (enabled) strong else quiet
    val ink = when {
        filled -> MaterialTheme.colorScheme.surface
        enabled -> strong
        else -> quiet
    }
    val across = if (filled) FILLED_ACROSS else PLAIN_ACROSS
    Box(
        modifier = Modifier
            .size(across)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(across)) {
            if (filled) drawCircle(disc, radius = size.minDimension / 2f)
            val glyph = size.minDimension * if (filled) FILLED_GLYPH_SHARE else PLAIN_GLYPH_SHARE
            val middle = Offset(size.width / 2f, size.height / 2f)
            when (icon) {
                TransportIcon.PLAY -> play(middle, glyph, ink)
                TransportIcon.PAUSE -> pause(middle, glyph, ink)
                TransportIcon.PREVIOUS -> step(middle, glyph, ink, forwards = false)
                TransportIcon.NEXT -> step(middle, glyph, ink, forwards = true)
            }
        }
    }
}

/**
 * A triangle, nudged right by a tenth of its width.
 *
 * Its centre of area sits a third of the way along, not half, so a triangle centred by its bounding
 * box looks left of centre - which on a round button everybody can see.
 */
private fun DrawScope.play(middle: Offset, glyph: Float, ink: Color) {
    val nudge = glyph * PLAY_NUDGE_SHARE
    val path = Path().apply {
        moveTo(middle.x - glyph * 0.30f + nudge, middle.y - glyph * 0.50f)
        lineTo(middle.x - glyph * 0.30f + nudge, middle.y + glyph * 0.50f)
        lineTo(middle.x + glyph * 0.42f + nudge, middle.y)
        close()
    }
    drawPath(path, ink)
}

/** Two bars, the same weight as the triangle beside them in time. */
private fun DrawScope.pause(middle: Offset, glyph: Float, ink: Color) {
    val wide = glyph * 0.21f
    val tall = glyph * 0.86f
    val gap = glyph * 0.19f
    for (side in listOf(-1f, 1f)) {
        val left = middle.x + side * (gap / 2f) - if (side < 0f) wide else 0f
        drawRoundRect(
            ink,
            topLeft = Offset(left, middle.y - tall / 2f),
            size = Size(wide, tall),
            cornerRadius = CornerRadius(wide * 0.3f, wide * 0.3f)
        )
    }
}

/** A triangle with a bar against the wall it points at. */
private fun DrawScope.step(middle: Offset, glyph: Float, ink: Color, forwards: Boolean) {
    val way = if (forwards) 1f else -1f
    val edge = glyph * 0.46f
    val bar = glyph * 0.13f
    val tall = glyph * 0.82f
    drawRect(
        ink,
        topLeft = Offset(middle.x + way * edge - if (forwards) 0f else bar, middle.y - tall / 2f),
        size = Size(bar, tall)
    )
    val path = Path().apply {
        moveTo(middle.x - way * edge, middle.y - tall / 2f)
        lineTo(middle.x - way * edge, middle.y + tall / 2f)
        lineTo(middle.x + way * (edge - bar * 1.2f), middle.y)
        close()
    }
    drawPath(path, ink)
}

/** The disc the middle button is, sized so a thumb lands on it without aiming. */
private val FILLED_ACROSS = 46.dp

/** And the two beside it, which are a glyph with room round it rather than a button. */
private val PLAIN_ACROSS = 34.dp

private const val FILLED_GLYPH_SHARE = 0.40f
private const val PLAIN_GLYPH_SHARE = 0.62f

/** See [play]: how far right of the bounding centre the triangle sits. */
private const val PLAY_NUDGE_SHARE = 0.05f
