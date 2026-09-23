package com.soundmesh.core

/**
 * The twelve colours a place in [RoomColours] is drawn in, as ARGB, for every screen that draws one.
 *
 * Here rather than in the handset's palette because a computer draws the same room: a handset and a
 * computer calling the same place by two different reds is two names for one device, which is the
 * thing a colour exists to prevent. Each screen turns these into its own colour type.
 *
 * Picked for being told apart at arm's length on a phone lying on a table - see the handset's
 * BadgePalette, which also holds the words for them.
 */
object BadgeHues {
    val argb: List<Long> = listOf(
        0xFFE53935, // red
        0xFFF57C00, // orange
        0xFFFDD835, // yellow
        0xFFAEEA00, // lime
        0xFF43A047, // green
        0xFF00897B, // teal
        0xFF00ACC1, // cyan
        0xFF1E88E5, // sky
        0xFF3949AB, // blue
        0xFF8E24AA, // purple
        0xFFD81B60, // magenta
        0xFFF48FB1  // pink
    )

    /** Per colour, whether the number drawn on top of it is white rather than black. */
    val whiteLabel: List<Boolean> = listOf(
        true, false, false, false,
        true, true, false, true,
        true, true, true, false
    )
}
