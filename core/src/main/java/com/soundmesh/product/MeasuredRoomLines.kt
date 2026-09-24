package com.soundmesh.product

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialMode
import com.soundmesh.probe.sync.StoredRoomField
import com.soundmesh.probe.sync.StoredSeparation
import java.io.File

/**
 * The drawing with one icon where the finger left it, and the offer open again.
 *
 * Moving an icon is the person saying where a handset is, which is exactly the thing the fit
 * is weighted against - so a drawing that has just been touched is a new opinion and deserves
 * a fresh answer, even if it was fitted a moment ago.
 */
fun withIconMoved(room: RoomState, moved: RoomIcon): RoomState = room.copy(
    icons = room.icons.map { if (it.peerId == moved.peerId) moved else it },
    fitted = false
)

/**
 * The drawing moved onto the measured shape, or null when there is nothing to offer.
 *
 * Null while [RoomCheck] is pointing at two icons, and that ordering is the point rather than
 * caution. The fit moves positions to match labels, so a drawing with two icons on the wrong
 * handsets would be moved onto each other's measured places, confidently and without a word.
 * Afterwards would be too late a second time over: once this has run, drawn and measured agree
 * by construction and the check can never fire again.
 */
fun fitOffer(state: RoomState): FittedRoom? {
    if (state.fitted) return null
    if (RoomCheck.contradiction(state.icons, state.measuredMetres) != null) return null
    return RoomFit.fitted(state.icons, state.measuredMetres, state.listenerMetres)
}

/**
 * How long each handset waits for the furthest one, in milliseconds, in the drawing's order.
 *
 * Empty until the listener has been measured and the fit taken, which is what makes this the
 * one line on the screen that says the delay is switched on. Everything else about the feature
 * is inaudible by design: it exists to make two handsets sound like one.
 */
/**
 * Whether an overhead round could place the listener in this room at all.
 *
 * Three handsets, and the reason is one that had to be computed rather than guessed. The
 * handset being held over somebody's head cannot measure its own distance to that head - it is
 * the head - so the listener comes out of the round with N-1 distances, not N. A point in a
 * plane needs three of them to be pinned: two handsets leave one, which is a circle and places
 * nothing. Three leave two, which is a fold - the listener's own reflection across the line
 * through the two measured handsets fits both distances exactly - and which side they are on
 * comes from the drawing, the same division of labour as the room's own mirror. Four are unique.
 *
 * So this is the line between "cannot" and "can, with the drawing settling one thing", and the
 * button is offered on the second. Under it there is nothing to offer and a minute of somebody
 * standing still holding a phone to lose.
 */
fun overheadRoundCanPlaceTheListener(icons: List<RoomIcon>): Boolean = icons.size >= 3

fun delayLines(icons: List<RoomIcon>, metresPerUnit: Double): List<Pair<String, Double>> {
    if (metresPerUnit <= 0.0) return emptyList()
    // Caught rather than thrown, for the reason HomeActivity.publish catches: this runs off a
    // five-a-second refresh on the main thread, and a roster that arrived wrong would take the
    // whole app down over one line of small print.
    val layout = runCatching { SpatialRoom.layoutOf(icons) }.getOrNull() ?: return emptyList()
    val field = SpatialField(SpatialMode.SPLIT, layout, metresPerUnit = metresPerUnit)
    return layout.peerIds.map { it to field.arrivalDelayNanosFor(it) / 1_000_000.0 }
}

/**
 * Which measured distances belong on the screen, in the order they are read out.
 *
 * One way round of each pair: the field holds both, so that a caller with two names need not
 * know which sorts first, and a list that took it at face value would print every distance
 * twice.
 *
 * Only pairs still in the drawing. A field outlives the room it was measured in - it is
 * replaced when the next room is measured, and not when somebody goes home - so a line about a
 * handset that is no longer here is a line about nothing the person can look at.
 */
fun measuredLines(
    icons: List<RoomIcon>,
    measured: Map<Pair<String, String>, Double>
): List<Pair<Pair<String, String>, Double>> {
    val room = icons.map { it.peerId }.toSet()
    return measured
        .filterKeys { it.first < it.second && it.first in room && it.second in room }
        .toList()
        .sortedBy { it.first.first + it.first.second }
}

/**
 * What an overhead round measured, in the order the icons are drawn.
 *
 * Only handsets still in the drawing, for the reason the pair lines are filtered: a distance
 * outlives the room it was taken in, and a line about a handset that went home is a line about
 * nothing the person can look at.
 */
fun listenerLines(
    icons: List<RoomIcon>,
    listenerMetres: Map<String, Double>
): List<Pair<String, Double>> = icons.mapNotNull { icon ->
    listenerMetres[icon.peerId]?.let { icon.peerId to it }
}

/**
 * Every distance this handset holds about a room, keyed by the two handsets it is between.
 *
 * Two files behind it, one fact each. A distance [self] is an end of comes from the per-peer
 * file, which every arm that measures a distance writes and which is therefore the freshest
 * thing there is about that pair. A distance between two other handsets can only have come from
 * a room measuring the lot in one window, and has nowhere else it could live.
 *
 * Both files can hold the same pair - a room measures the ones this handset is in no
 * differently - and neither records when it was written, so which one answers has to be
 * decided here rather than by whichever is read second. The per-peer file answers: it is the
 * one every arm that measures a distance writes, the room included, so for a pair they share
 * it cannot be the staler of the two. Which is why the room is read first and written over,
 * and not the other way round.
 *
 * File scope so it can be judged on what it produces. Inside the screen it would be reachable
 * only by standing in a room with three phones in it.
 */
fun measuredDistances(
    directory: File,
    self: String?,
    room: List<String>
): Map<Pair<String, String>, Double> {
    val distances = LinkedHashMap<Pair<String, String>, Double>()
    for (entry in StoredRoomField(directory).read()) distances[entry.key] = entry.value
    if (self == null) return distances
    for (peerId in room) {
        if (peerId == self) continue
        val metres = StoredSeparation(directory, peerId).read() ?: continue
        distances[self to peerId] = metres
        distances[peerId to self] = metres
    }
    return distances
}
