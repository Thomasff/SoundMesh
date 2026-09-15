package com.soundmesh.product

import com.soundmesh.core.RoomExcuse
import com.soundmesh.probe.sync.Carried

/**
 * One standing handset, the way the status screen draws it.
 *
 * A row each rather than the three counts the screen used to carry. The counts said "两台没校准"
 * and the thing a person does about it is walk over to one particular phone, which a count cannot
 * name. Same for the microphone permission and for a handset that has stopped saying it is there:
 * every one of them is fixed at one handset, so every one of them belongs on that handset's line.
 */
data class StandingRow(
    val peerId: String,
    val name: String,
    /** What it says about the correction it carries for this host. See [Carried]. */
    val carrying: Carried,
    /**
     * Whether it has stopped saying it is there.
     *
     * Not the same as gone, and deliberately not drawn as gone: an Android handset suspends about
     * a minute after its screen goes off and stops running its own heartbeat, while remaining
     * perfectly reachable. See `RoomCommandServer.quietPeerIds`.
     */
    val quiet: Boolean,
    /** The last reason it gave for not joining a round, or null if it has never given one. */
    val excuse: RoomExcuse?
)

/**
 * The standing handsets as rows, in the order they joined.
 *
 * [excuses] is looked up per handset rather than iterated, which is what drops an excuse left
 * behind by a handset that has since walked out: those are kept past the socket on purpose, so
 * that the sentence outlives the disconnection long enough to be read, and this list - which is
 * the handsets standing by and only those - is where that keeping stops.
 */
internal fun rosterOf(
    peerIds: List<String>,
    name: (String) -> String?,
    carrying: Map<String, Carried>,
    quiet: Set<String>,
    excuses: Map<String, RoomExcuse>
): List<StandingRow> = peerIds.map { peerId ->
    StandingRow(
        peerId = peerId,
        name = name(peerId) ?: peerId.takeLast(SHORT_NAME_CHARACTERS),
        // Absent means a build from before the message, which is exactly what UNSAID is for -
        // defaulting to NOTHING would send somebody to recalibrate a handset that is already fine.
        carrying = carrying[peerId] ?: Carried.UNSAID,
        quiet = peerId in quiet,
        excuse = excuses[peerId]
    )
}

/**
 * How much of an identity to show when a handset has never said what it is called.
 *
 * The last four rather than the whole thing, because the point of a name is that somebody can say
 * it into a phone call - see `RoomCommandServer.nameOf`, which falls back the same way.
 */
private const val SHORT_NAME_CHARACTERS = 4
