package com.soundmesh.product

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialMode

/**
 * The rule the room is played under, worked out from what is on the screen.
 *
 * A pure function of [RoomState], and out here rather than inside HomeActivity because of what it
 * decides: **three of the settings on that screen do not reach the rule in every mode**, and each
 * of those is a place where the screen and the sound can quietly disagree. Inside an Activity none
 * of that could be checked; out here it is four tests - see RoomRuleTest.
 *
 * Null while there is nothing to draw. Throws for a room that could not exist, which is caught by
 * whoever publishes rather than softened here: the next bad roster should still be findable.
 */
internal fun ruleOf(room: RoomState): SpatialField? {
    val layout = SpatialRoom.layoutOf(room.icons) ?: return null
    return SpatialField(
        room.mode,
        layout,
        periodNanos = room.periodSeconds.toLong().coerceAtLeast(1L) * 1_000_000_000L,
        pan = room.pan.toDouble().coerceIn(-1.0, 1.0),
        // Dropped from the rule while a source is being moved around, and only from the rule: the
        // screen goes on remembering which handset carries which half, so trying the rotation for
        // a minute does not cost an assignment somebody made by hand. A source going somewhere is
        // one thing going one place, and there is nothing to hand out.
        separation =
            if (room.mode.movesASource) 0.0
            else room.separation.toDouble().coerceIn(0.0, 1.0),
        splitAxis = room.splitAxis,
        crossoverHz = room.crossoverHz.toDouble()
            .coerceIn(SpatialField.LOWEST_CROSSOVER_HZ, SpatialField.HIGHEST_CROSSOVER_HZ),
        otherHalfIds = room.otherHalfIds,
        // Taken through the same resolution the drawing uses, so that a room carrying both a
        // retreat and an envelopment - which is every room somebody reached by choosing 旋转 and
        // then 自定义声音位置 - sounds like the one dot it is showing. See SpatialRoom.
        envelopment =
            if (room.mode == SpatialMode.PAN) {
                SpatialRoom.envelopmentFor(room.retreat, room.envelopment).toDouble()
                    .coerceIn(0.0, SpatialField.MAX_ENVELOPMENT)
            } else {
                room.envelopment.toDouble().coerceIn(0.0, SpatialField.MAX_ENVELOPMENT)
            },
        // Zeroed rather than carried with a flag beside it: no scale is exactly what a room that
        // never measured its listener sends, so the switch off and the feature absent are the same
        // message on the wire and the same code on every handset.
        metresPerUnit = if (room.delayCompensation) room.metresPerUnit else 0.0,
        // The dot's own number, and it leaves when the dot does. Every other mode draws no dot, so
        // a room left quiet by one would be a room nothing on screen could put back.
        retreat =
            if (room.mode == SpatialMode.PAN) room.retreat.toDouble().coerceIn(0.0, 1.0)
            else 0.0,
        // In every mode, unlike the retreat above it: a room is a room whatever the handsets are
        // being asked to play. Which effects turn it on is decided on the screen - see RoomEffect -
        // rather than here, because it is a taste and the three above are arithmetic.
        reverb = room.reverb.toDouble().coerceIn(0.0, 1.0)
    )
}

/**
 * Whether this mode puts a single source somewhere, as against handing the mix out.
 *
 * Asked in two places that have to agree - whether the content split reaches the rule, and whether
 * the envelopment counts towards which effect a room is on - so it is written once.
 */
internal val SpatialMode.movesASource: Boolean
    get() = this == SpatialMode.ROTATE || this == SpatialMode.PAN
