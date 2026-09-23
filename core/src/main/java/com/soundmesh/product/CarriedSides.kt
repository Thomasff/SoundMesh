package com.soundmesh.product

/**
 * Which devices were carrying the other half of the split, kept for the ones not in the room
 * just now - the handset host's and the desktop host's one copy of it.
 *
 * Not the same shape as the memory of where icons were drawn: a position is only ever replaced,
 * but a half can be taken away, so what is remembered about a device that is present has to come
 * from what it carries now and nowhere else. Otherwise turning a half off would be undone a
 * moment later by the memory of it having been on.
 */
class CarriedSides {
    private val carried = HashSet<String>()

    /** What a saved drawing says was carried, handed out as those devices arrive. */
    fun remember(otherHalfIds: Set<String>) {
        carried.addAll(otherHalfIds)
    }

    /**
     * Who carries the other half once the room goes from [before] to [after], [otherHalfIds]
     * being what the devices in [before] carry.
     *
     * Cleared against [before] and not [after]: a device that has just come back is in [after],
     * and clearing against that struck it from the memory the moment it arrived - which is what
     * the handset did until 09-23, handing a device that left and came back nothing.
     */
    fun reconciled(otherHalfIds: Set<String>, before: List<String>, after: List<String>): Set<String> {
        carried.removeAll(before.toSet())
        carried.addAll(otherHalfIds)
        return SpatialRoom.reconciledOtherHalf(otherHalfIds, after, carried)
    }

    /**
     * What to write down: what the devices [present] carry, and what the ones not here carried.
     * Only the first used to be written, so a restart forgot every device that was away.
     */
    fun toKeep(otherHalfIds: Set<String>, present: List<String>): Set<String> =
        otherHalfIds + (carried - present.toSet())
}
