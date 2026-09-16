package com.soundmesh.product

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The pieces every stage is drawn out of, since 2026-09-15: labels, hairline rows, small pills.
 *
 * What they replace is a column of Material cards, each with sixteen points of padding and a
 * heading in title type. That look is fine for three cards and wrong for this app: a room of four
 * phones has a network line, a code, four handsets, two measurements and a way out, and as cards
 * that is four screens of scrolling to read nine facts. So the furniture went thin - a small grey
 * label instead of a heading, a hairline instead of a card edge - and what stays solid is the two
 * or three things on a screen that are actually the point.
 *
 * Colour is rationed for the reason [SoundMeshTheme] gives: [BadgePalette] hands out twelve hues to
 * say which handset is which, so nothing here may compete. [Tone] carries three deliberately
 * desaturated readings - about half the saturation of any badge - and everything else is a shade
 * of the surface.
 */

/** A small grey heading over a list, with an optional count or state at its right-hand end. */
@Composable
fun Label(title: Int, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        trailing?.let {
            // With an action it is the button it already looked like; without one it stays a
            // reading. The two were the same drawing, so the room's volume label carried a pill
            // nobody could press and a second copy of the same command below the rows, and the
            // one people reached for first was the one that did nothing. Reported 2026-09-15.
            if (onTrailing != null) {
                Chip(it, onTrailing)
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

/**
 * One line of a list: a hairline above it, and whatever the caller puts in it.
 *
 * The hairline is on top rather than underneath, so a list ends where its last row does instead of
 * with a rule pointing at the next label.
 */
@Composable
fun Line(first: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!first) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            content = content
        )
    }
}

/**
 * The handset's name in a [Line]: takes the width, and gives it up rather than pushing anything off.
 *
 * Ellipsised on purpose. A long system name used to take the whole row and push the button at its
 * end off the screen, and that button is sometimes the only way to fix what the row is about.
 */
@Composable
fun RowScope.LineName(text: String, quiet: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    )
}

/**
 * The coloured dot that says which handset a line is about.
 *
 * Hollow where the handset is not answering, which is the one thing about a phone that reads from
 * across a room without anybody reading a word: the colour is still there to know it by, and the
 * middle is gone.
 */
@Composable
fun Dot(place: Int?, hollow: Boolean = false, size: Dp = 9.dp) {
    val colour = if (hollow) MaterialTheme.colorScheme.onSurfaceVariant
    else BadgePalette.colourOf(place, MaterialTheme.colorScheme.onSurfaceVariant)
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (hollow) Modifier.border(1.5.dp, colour, CircleShape)
                else Modifier.background(colour, CircleShape)
            )
    )
}

/**
 * How a [Tag] reads.
 *
 * Three readings and not five, because what a person does about them differs: [GOOD] needs
 * nothing, [WATCH] will sound worse than it should, [WRONG] means a phone in this room is silent.
 * [QUIET] is not a reading at all, it is a fact with no verdict attached.
 */
enum class Tone { QUIET, GOOD, WATCH, WRONG }

/**
 * The three readings, at about half the saturation of any badge hue.
 *
 * Desaturated on purpose rather than for taste: a saturated green here would be a thirteenth
 * colour on a screen whose other twelve each mean "this particular phone". At this saturation they
 * read as ink with a temperature, which is what they are.
 */
@Composable
private fun toneColour(tone: Tone): Color {
    // Read off the scheme rather than off the system, because somebody can force either theme
    // from settings - asking the system would give the wrong pair of greens to exactly the person
    // who went and said which theme they wanted.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (tone) {
        Tone.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.GOOD -> if (dark) Color(0xFF7FB08F) else Color(0xFF3D7A52)
        Tone.WATCH -> if (dark) Color(0xFFC9A459) else Color(0xFF9A6B16)
        Tone.WRONG -> MaterialTheme.colorScheme.error
    }
}

/** A small piece of state at the end of a line. Reads, never taps. */
@Composable
fun Tag(text: String, tone: Tone = Tone.QUIET) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = toneColour(tone))
}

/**
 * The small tap at the end of a line.
 *
 * Shorter than a Material button on purpose - a row is about thirty-six points and a button is
 * fifty - and the row it sits in carries the rest of the target.
 */
@Composable
fun Chip(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

/**
 * A box with an edge round it, for the things on a screen that are not a list.
 *
 * [strong] is the one this project is for. On the status screen exactly two things are drawn
 * strongly - the calibration box and the way onto the playing stage - because a screen where
 * everything is emphasised has nothing emphasised, and the thing people were skipping past is the
 * calibration.
 */
@Composable
fun Framed(strong: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (strong) 1.5.dp else 1.dp,
            if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

/** The line of small grey text under a control that says what it is for. */
@Composable
fun Note(text: String, tone: Tone = Tone.QUIET) {
    Text(text, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp, color = toneColour(tone))
}

/** A name inside a [Framed] box. A name, not a heading - nothing here is a section. */
@Composable
fun BoxTitle(text: String, strong: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
        fontSize = if (strong) 16.sp else 15.sp
    )
}

/**
 * The full-width solid button. One or two on a screen, never more - see [Framed].
 *
 * [modifier] is here so two of these can share a row by weight. Everything on this screen is a
 * column of full-width controls but "replace it / leave it" is one question with two answers, and
 * stacking those reads as two separate things to do in order.
 */
@Composable
fun Solid(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** The full-width outlined one, for what sits beside or under a [Solid]. */
@Composable
fun Ghost(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The bar at the top of a screen that is not one of the three stages: a way back, and a name.
 *
 * The stages have their own in [HomeScreen]; this is for the two calibration screens, which are
 * activities of their own and until now opened with nothing at the top of them at all. A screen
 * with no name and no arrow is one somebody has to guess their way out of, and both of these are
 * screens people are sent to from somewhere else.
 */
@Composable
fun PageBar(title: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "←",
            modifier = Modifier.clickable(onClick = onBack),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/** One choice in a [Segmented]: what it says, whether it is the one in force, what it does. */
data class Segment(val text: String, val chosen: Boolean, val onClick: () -> Unit)

/**
 * One of N, drawn as a single control rather than as N loose pills.
 *
 * The pills were the first try and they were wrong twice over: three of them huddled against the
 * left edge of a screen they were meant to divide, and each was sized to its own word, so "歌曲"
 * was half the size of "本机的声音" and the row read as three unrelated buttons. Cells of equal
 * width inside one border read as one question with N answers, which is what this is.
 *
 * The filled cell is the state, not a memory of the last tap - see PlayingScreen's SourcePicker.
 */
@Composable
fun Segmented(parts: List<Segment>, modifier: Modifier = Modifier) {
    if (parts.isEmpty()) return
    val line = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(SEGMENT_CORNER))
            .border(1.dp, line, RoundedCornerShape(SEGMENT_CORNER))
    ) {
        for ((index, part) in parts.withIndex()) {
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(line))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (part.chosen) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable(onClick = part.onClick)
                    .padding(vertical = 10.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    part.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (part.chosen) FontWeight.Medium else FontWeight.Normal,
                    color = if (part.chosen) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val SEGMENT_CORNER = 9.dp
