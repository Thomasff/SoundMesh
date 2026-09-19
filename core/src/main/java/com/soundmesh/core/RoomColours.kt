package com.soundmesh.core

/**
 * Which colour each handset in the room is shown in, held for as long as that handset is there.
 *
 * [PeerBadge] gives a handset a number that never changes and a colour it would like, and leaves
 * the colour unsettled on purpose: a number may collide because the colour will separate the two,
 * and a colour that collided as readily would leave nothing separating anything. So a colour is
 * taken rather than derived. A handset arriving starts at the one it prefers and moves along until
 * it finds one nobody in the room is using.
 *
 * **A colour is decided once, when the handset joins, and then left alone.** Not re-derived per
 * roster, which is the version that looks identical in every test that asks what a full room reads
 * and is wrong in the one moment that matters: a handset drops, the room re-probes, and every
 * handset that had been pushed aside slides back - recolouring the room at the exact instant
 * somebody is looking at it to find out which one dropped. The event this identity exists to
 * report would be the event that invalidates it.
 *
 * The cost is that this holds state and so has to be told who is here, rather than being a
 * function of who is here. Departures are noticed by absence from a roster rather than by an
 * event, which is what lets the one caller keep polling the roster it already polls.
 */
class RoomColours {
    private val held = LinkedHashMap<String, Int>()

    /**
     * The room's colours, given who is in it now.
     *
     * Anybody here already keeps what they hold; anybody gone releases it; anybody new probes.
     * New handsets are settled in sorted order rather than roster order, so two of them arriving
     * between one call and the next land the same way whichever order the roster happened to list
     * them in - the roster is connection order, and connection order is a race.
     */
    fun reconcile(peerIds: List<String>): Map<String, Int> {
        val present = peerIds.toSet()
        held.keys.retainAll(present)
        for (peerId in present.filter { it !in held }.sorted()) {
            held[peerId] = free(PeerBadge.preferredColour(peerId))
        }
        return LinkedHashMap(held)
    }

    /**
     * Takes up a table somebody else settled, so this one continues a room instead of deciding it.
     *
     * For the case where two channels in one process both have to answer the same question over
     * different spans of time: the standing channel settles a room's colours the moment handsets
     * connect, and a session opened later would otherwise settle the same room again from scratch.
     * It would usually land on the same answer - same handsets, same preferences - and the once it
     * would not is a room that lost and regained a handset, which is exactly the minute somebody
     * is watching the colours to see what happened.
     *
     * Adopted entries are held on the same terms as any other, so the next [reconcile] releases
     * whoever is no longer present and settles only handsets neither channel has seen. [places] is
     * expected to be another instance's output and so already free of duplicates; a table with two
     * handsets on one colour is taken as given, because the alternative is silently moving a
     * handset that the room this came from has already shown somebody.
     */
    fun adopt(places: Map<String, Int>) {
        held.putAll(places)
    }

    /**
     * The first colour from [preferred] onwards that nobody holds, or [preferred] itself once the
     * palette is full.
     *
     * A duplicate rather than a refusal past twelve handsets: a handset with no colour is a handset
     * with no icon, and a drawing one short is a fault that looks like a room working normally.
     */
    private fun free(preferred: Int): Int {
        val taken = held.values.toSet()
        for (step in 0 until PeerBadge.COLOURS) {
            val colour = (preferred + step) % PeerBadge.COLOURS
            if (colour !in taken) return colour
        }
        return preferred
    }
}
