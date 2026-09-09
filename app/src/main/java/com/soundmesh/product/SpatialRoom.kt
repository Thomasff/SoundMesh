package com.soundmesh.product

import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialPosition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where one handset's icon sits on the drawing, as a fraction of the drawing's side.
 *
 * Screen coordinates: (0, 0) is the top left corner and y grows downward, which is the opposite of
 * the room's own front. [SpatialRoom.layoutOf] is where the two meet, and it is the only place the
 * flip happens - a second one anywhere else would mirror the room, and a mirrored room is the one
 * defect a listener cannot diagnose by ear because it sounds exactly like a working one.
 */
data class RoomIcon(val peerId: String, val x: Float, val y: Float)

/**
 * The drawing, and the two things the screen cannot work out for itself.
 *
 * The listener is fixed at the middle and is not draggable, which is not a simplification. Whether
 * a phone icon is where the phone is can be checked by looking at the room; whether the app
 * believes the listener is sitting where they are sitting cannot be checked at all.
 */
object SpatialRoom {
    /** The middle of the drawing, where the listener is. */
    const val CENTRE = 0.5f

    /** How far out the icons start. Clear of the listener, clear of the edge. */
    const val DEFAULT_RADIUS = 0.34f

    /**
     * How close to the listener an icon may be dragged.
     *
     * A handset exactly on the listener has no direction, so there is no gain to give it. Clamped
     * rather than refused, so the drag simply stops instead of the room going undrawable while a
     * finger is down.
     */
    const val MIN_RADIUS = 0.06f

    /** The arc the icons start spread across, centred on straight ahead. */
    private const val DEFAULT_ARC_RADIANS = 2.0 * PI / 3.0

    /**
     * A first arrangement to drag from: everybody in front, spread evenly, in roster order.
     *
     * In front rather than all the way round, because the phones are in front - somebody put them
     * on a table and is looking at them - and a default that starts half the room behind the
     * listener asks them to undo it before they can start.
     */
    fun defaultIcons(peerIds: List<String>): List<RoomIcon> {
        if (peerIds.isEmpty()) return emptyList()
        if (peerIds.size == 1) return listOf(iconAt(peerIds[0], 0.0))
        val step = DEFAULT_ARC_RADIANS / (peerIds.size - 1)
        return peerIds.mapIndexed { index, peerId ->
            iconAt(peerId, -DEFAULT_ARC_RADIANS / 2.0 + index * step)
        }
    }

    private fun iconAt(peerId: String, azimuth: Double) = RoomIcon(
        peerId,
        CENTRE + (DEFAULT_RADIUS * sin(azimuth)).toFloat(),
        // Minus, because azimuth zero is ahead and ahead is up the screen.
        CENTRE - (DEFAULT_RADIUS * cos(azimuth)).toFloat()
    )

    /**
     * The drawing brought up to date with who is actually in the room.
     *
     * A handset that is still here keeps exactly where it was put, which is the whole point: the
     * roster is re-read several times a second, and a drawing rebuilt from scratch each time would
     * throw away every drag the moment anything else changed. A handset that left is dropped, and
     * a new one lands where the default arrangement would have put it.
     *
     * Order follows the roster rather than the old drawing, so the phone in the listener's hand
     * stays first however many others come and go.
     */
    fun reconciled(icons: List<RoomIcon>, peerIds: List<String>): List<RoomIcon> {
        // One icon per name, whatever the roster says. The roster is built from connections rather
        // than from handsets, so a handset that dropped and came back can be in it twice - and a
        // room where one handset stands in two places is one SpatialLayout refuses to draw, by
        // construction and rightly. That refusal used to arrive as the host's process ending.
        // The roster is fixed where it is built; this is the drawing declining to be where a bad
        // one becomes a crash.
        val names = peerIds.distinct()
        val placed = icons.associateBy { it.peerId }
        val fresh = defaultIcons(names).associateBy { it.peerId }
        return names.mapNotNull { placed[it] ?: fresh[it] }
    }

    /**
     * The same icon, no closer to the listener than [MIN_RADIUS].
     *
     * An icon dropped exactly on the listener has no direction to push it back along, so it goes
     * straight ahead. Any answer is arbitrary there; what matters is that it is visible, because
     * the icon moves under the finger and says so.
     */
    fun clamped(icon: RoomIcon): RoomIcon {
        val dx = icon.x - CENTRE
        val dy = icon.y - CENTRE
        val radius = hypot(dx.toDouble(), dy.toDouble())
        if (radius >= MIN_RADIUS) return icon
        if (radius == 0.0) return RoomIcon(icon.peerId, CENTRE, CENTRE - MIN_RADIUS)
        val scale = MIN_RADIUS / radius
        return RoomIcon(icon.peerId, CENTRE + (dx * scale).toFloat(), CENTRE + (dy * scale).toFloat())
    }

    /**
     * The room these icons draw, or null when there is nothing to draw.
     *
     * Scale is dropped here and nothing downstream misses it: every gain in the system depends on
     * direction alone, so a drawing enlarged is the same room. What the drawing carries that no
     * measurement can is which side is left - three measured distances fix a triangle's shape and
     * say nothing about its orientation or its mirror image, and only a person can.
     */
    fun layoutOf(icons: List<RoomIcon>): SpatialLayout? {
        if (icons.isEmpty()) return null
        return SpatialLayout(
            icons.map { clamped(it) }.map {
                SpatialPosition(
                    it.peerId,
                    (it.x - CENTRE).toDouble(),
                    // The one flip: screen y grows downward, the room's y grows forward.
                    (CENTRE - it.y).toDouble()
                )
            }
        )
    }
}
