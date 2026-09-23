package com.soundmesh.product

import kotlin.math.hypot

/**
 * Which icon a finger landing here meant, or null when it landed on nothing.
 *
 * Internal rather than private so it can be tested: what it decides is which phone a drag moves,
 * and getting it wrong swaps two handsets - the one defect in this screen that a listener cannot
 * diagnose by ear, because a swapped pair sounds exactly like a working room.
 */
fun nearestPeerId(icons: List<RoomIcon>, x: Float, y: Float): String? = icons
    .minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
    ?.takeIf { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) <= GRAB_RADIUS }
    ?.peerId

/** What a finger landing on the drawing meant. */
sealed interface Grabbed {
    /** The source, which is the one thing here that is not a handset. */
    data object Source : Grabbed

    data class Handset(val peerId: String) : Grabbed
}

/**
 * Which of the things on the drawing a finger landing here meant, or null for none of them.
 *
 * The source wins a tie, and that is the only interesting line in it. The dot is drawn over the
 * handsets and a person reaches for what they can see; handing a drag to the icon underneath
 * instead would move a phone across the room in answer to a touch aimed at the sound. That failure
 * has been reported on this screen once already, from the other cause - see the rememberUpdatedState
 * note in RoomDrawing - and it reads to a listener as the drawing ignoring them.
 *
 * [source] is null wherever there is no source to grab, which makes this exactly [nearestPeerId].
 */
fun grabbedAt(
    icons: List<RoomIcon>,
    source: SourceSpot?,
    x: Float,
    y: Float
): Grabbed? {
    val handset = nearestPeerId(icons, x, y)
    val toSource = source
        ?.let { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
        ?.takeIf { it <= GRAB_RADIUS }
        ?: return handset?.let { Grabbed.Handset(it) }
    val nearest = handset ?: return Grabbed.Source
    val toHandset = icons.first { it.peerId == nearest }
        .let { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
    return if (toSource <= toHandset) Grabbed.Source else Grabbed.Handset(nearest)
}

/** How far from an icon a finger may land and still mean it. A fingertip on a phone screen. */
const val GRAB_RADIUS = 0.12
