package com.soundmesh.product

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.soundmesh.core.BadgeHues
import com.soundmesh.core.PeerBadge
import com.soundmesh.probe.R

/**
 * What a handset's colour looks like, and what it is called when there is no colour to show.
 *
 * [com.soundmesh.core.RoomColours] settles which handset holds which place in the palette, and
 * knows nothing about what the places look like - the assignment is testable without a screen and
 * stays that way. This is the other half: twelve hues far enough apart to be named across a room,
 * each with a label colour that is legible on it, and each with a word for the places a colour
 * cannot go.
 *
 * The words are not a nicety. A colour is unusable in everything this app writes down - a report,
 * a counter, a line in a log read a month later - and those are exactly the places where a handset
 * has to be identified without ambiguity. So the written form carries both halves, and the number
 * alone is never the identity.
 *
 * Deliberately plain values rather than the Material scheme: the scheme has three or four colours
 * that mean things - primary, error, surface - and twelve that are merely different is not
 * something it has. These are picked for being told apart at arm's length on a phone lying on a
 * table, which no theme is aiming at.
 */
object BadgePalette {
    // The values live in core, because a computer draws the same room and must call each place by
    // the same colour.
    private val colours = BadgeHues.argb.map { Color(it) }

    /** Black or white per colour, whichever the number on top of it can be read against. */
    private val labels = BadgeHues.whiteLabel.map { if (it) Color.White else Color.Black }

    @StringRes private val names = listOf(
        R.string.badge_red, R.string.badge_orange, R.string.badge_yellow, R.string.badge_lime,
        R.string.badge_green, R.string.badge_teal, R.string.badge_cyan, R.string.badge_sky,
        R.string.badge_blue, R.string.badge_purple, R.string.badge_magenta, R.string.badge_pink
    )

    init {
        // The three lists and the palette size are one number written four times, and three of the
        // four are silent when they disagree: a short label list throws in a draw call, a short
        // name list throws in a report. Said here, where it fails on the first screen shown.
        require(colours.size == PeerBadge.COLOURS) { "palette is ${colours.size}, not ${PeerBadge.COLOURS}" }
        require(labels.size == PeerBadge.COLOURS) { "label colours are ${labels.size}" }
        require(names.size == PeerBadge.COLOURS) { "colour names are ${names.size}" }
    }

    /**
     * The colour a handset holding [place] is drawn in, or [fallback] for a handset holding none.
     *
     * A handset with no place is the ordinary case rather than a fault: the room is read from the
     * spatial channel, and everything on screen before the first sink connects has a name and no
     * colour yet.
     */
    fun colourOf(place: Int?, fallback: Color): Color =
        if (place == null || place !in colours.indices) fallback else colours[place]

    fun labelColourOf(place: Int?, fallback: Color): Color =
        if (place == null || place !in labels.indices) fallback else labels[place]

    /** What [place] is called, or null where there is nothing to call it. */
    @StringRes fun nameOf(place: Int?): Int? =
        if (place == null || place !in names.indices) null else names[place]
}

/**
 * One handset, as small as it can be and still be the whole identity: its number, on its colour.
 *
 * The number is drawn on the colour rather than beside it because the two are one name. Set side
 * by side they read as a colour and a number that happen to be near each other, and a room where
 * somebody quotes only the colour is a room with no way to write anything down.
 */
@Composable
fun BadgeChip(
    peerId: String,
    place: Int?,
    modifier: Modifier = Modifier,
    diameter: Dp = 22.dp,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    Box(
        modifier = modifier
            .size(diameter)
            .background(
                BadgePalette.colourOf(place, MaterialTheme.colorScheme.primary),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "${PeerBadge.numberOf(peerId)}",
            style = MaterialTheme.typography.labelSmall,
            fontSize = fontSize,
            color = BadgePalette.labelColourOf(place, MaterialTheme.colorScheme.onPrimary)
        )
    }
}

/**
 * The same identity for a sentence, where there is no colour to show - only a word for it.
 *
 * Falls back to the number alone rather than to the name, because a handset with no colour yet is
 * a handset nobody has drawn, and a sentence naming a colour that is not on any screen is worse
 * than a sentence naming only a number.
 */
@Composable
fun badgeWords(peerId: String, place: Int?): String {
    val number = PeerBadge.numberOf(peerId)
    val name = BadgePalette.nameOf(place)
        ?: return stringResource(R.string.badge_number_only, number)
    return stringResource(R.string.badge_in_words, number, stringResource(name))
}