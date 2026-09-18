package com.soundmesh.product

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialPosition
import kotlin.math.PI
import kotlin.math.atan2
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
 * Where the sound is coming from, on the same drawing and in the same fractions as [RoomIcon].
 *
 * Not state. Nothing stores one of these: a spot is worked out from the two numbers the rule
 * already holds - which side the source is on and how far off it is - and a finger that moves it
 * writes those same two numbers back. See [SpatialRoom.spotOf], [SpatialRoom.panOf] and
 * [SpatialRoom.retreatOf].
 *
 * That is the whole of why the dot exists. Where a source is in a room is two numbers, and a
 * slider is one, so a slider can only ever travel one path that somebody chose in advance. Two
 * sliders can reach every position and ask a listener to operate both at once to do it. A place
 * on a drawing is two numbers in one gesture, which is what the question was.
 */
data class SourceSpot(val x: Float, val y: Float)

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

    /**
     * The listener's own circle: how near the middle a source counts as being on top of them.
     *
     * The middle used to be a single point, and a single point is not a place a finger can reach.
     * Dragging the source onto the listener is the one position on this map with no direction at
     * all - see [envelopmentOf] - and it was the hardest position on the map to hit, which is
     * backwards. Everything inside here is that position, and the dot is redrawn in the exact
     * middle when it lands there, so the snap is visible rather than something to be guessed at.
     *
     * Slightly inside a handset icon's own disc, which is drawn at 0.06 of the map and is the
     * target size a person is already used to aiming at on this screen. Not the halo around it:
     * the halo is three times the disc, and a grab zone that big would swallow a real position.
     */
    const val LISTENER_RADIUS = 0.05f

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

    /**
     * How far out the source is drawn when nothing has pushed it back: exactly where the handsets
     * start.
     *
     * The handsets are as close as a source gets, and that is not a simplification to be undone
     * later - the sound is coming out of them. Nearer than that would be a gain above one, and the
     * cost of those is written down: see the gains-multiply note on SpatialGain.highTrim, where a
     * lift of four decibels crackled on real music within minutes of reaching a listener.
     */
    const val SOURCE_HOME_RADIUS = DEFAULT_RADIUS

    /**
     * How far out the source can be dragged. Inside the edge, so the mark is drawn whole.
     *
     * Between here and [SOURCE_HOME_RADIUS] the drawing is not to scale, and it cannot be: the far
     * end of this travel is the source at four times the distance, and a picture that showed that
     * to scale would have the handsets in a huddle one pixel across. The radius is even in
     * decibels instead, so half way out is twice as far and the whole way is four times - which is
     * what the readout under the map says, and what SpatialRoomTest.halfWayOutIsTwiceAsFarAway
     * checks against the rule rather than against this comment.
     */
    const val SOURCE_MAX_RADIUS = 0.44f

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
     * throw away every drag the moment anything else changed. A handset that left is dropped from
     * the drawing, and a handset nobody has placed lands where [emptiestSlot] puts it.
     *
     * [remembered] is where handsets were last drawn, including ones not in [peerIds] just now.
     * A sink reconnects seconds after its host, so without it every restart hands a returning
     * handset a default position - which is the listener's drag thrown away by a slower route
     * than rebuilding the drawing, and just as complete.
     *
     * Order follows the roster rather than the old drawing, so the phone in the listener's hand
     * stays first however many others come and go.
     */
    fun reconciled(
        icons: List<RoomIcon>,
        peerIds: List<String>,
        remembered: Map<String, RoomIcon> = emptyMap()
    ): List<RoomIcon> {
        // One icon per name, whatever the roster says. The roster is built from connections rather
        // than from handsets, so a handset that dropped and came back can be in it twice - and a
        // room where one handset stands in two places is one SpatialLayout refuses to draw, by
        // construction and rightly. That refusal used to arrive as the host's process ending.
        // The roster is fixed where it is built; this is the drawing declining to be where a bad
        // one becomes a crash.
        val names = peerIds.distinct()
        val here = icons.associateBy { it.peerId }
        val slots = defaultIcons(names)
        val placed = LinkedHashMap<String, RoomIcon>()
        // Everybody whose position is already known goes down first, so that the arrivals below
        // are choosing against the whole room and not against half of it.
        for (name in names) (here[name] ?: remembered[name])?.let { placed[name] = it }
        for (name in names) {
            if (name in placed) continue
            placed[name] = emptiestSlot(slots, placed.values).let { RoomIcon(name, it.x, it.y) }
        }
        return names.mapNotNull { placed[it] }
    }

    /**
     * Where to put a handset nobody has placed yet: the default position furthest from everybody
     * already on the drawing.
     *
     * It used to be "the slot the default arrangement gives this one", which put two icons on the
     * same spot whenever handsets arrived one at a time. Two phones join: the arrangement spreads
     * them to -60 and +60 degrees. A third joins: the arrangement is now -60, 0, +60, the first
     * two keep where they were, and the newcomer takes slot three - which is +60, exactly where
     * the second one is standing. The drawing then shows two handsets and the room has three,
     * and the only way to find that out is to drag the top one off the one underneath it.
     *
     * Furthest-from-everybody rather than a search for an unused slot, because with more handsets
     * than the arrangement has room for there is no unused slot and there is still a best answer.
     */
    private fun emptiestSlot(slots: List<RoomIcon>, taken: Collection<RoomIcon>): RoomIcon {
        if (taken.isEmpty()) return slots.first()
        return slots.maxByOrNull { slot ->
            taken.minOf { hypot((slot.x - it.x).toDouble(), (slot.y - it.y).toDouble()) }
        } ?: slots.first()
    }

    /**
     * Which handsets carry the sides, kept to the ones still in the room.
     *
     * A second place holding the roster, and so a second place for a departed handset to linger.
     * The consequence is not symmetric with the drawing: a rule naming a handset its own drawing
     * does not show is one SpatialField refuses to build, and the screen builds one five times a
     * second. Left to itself the room would stop being published rather than say anything.
     *
     * A handset that is still here keeps what it was given, for the reason its icon keeps where it
     * was dragged: the roster is re-read constantly and a choice rebuilt each time is not a choice.
     *
     * [remembered] is what handsets not in the room just now were last carrying, the same way
     * [reconciled] takes where they were last drawn - and for the same case, which is a restart:
     * the drawing is read back before any sink has dialled in, so the first roster is the host on
     * its own and everybody else's part would be pruned a fifth of a second after being restored.
     * The caller owns it, and owes it one thing the drawing does not: a part can be **taken away**,
     * so what is remembered about a handset that is in the room has to be dropped, or turning a
     * part off would be undone by the memory of it being on.
     */
    fun reconciledOtherHalf(
        otherHalfIds: Set<String>,
        peerIds: List<String>,
        remembered: Set<String> = emptySet()
    ): Set<String> = (otherHalfIds + remembered).intersect(peerIds.toSet())

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
     * Where to draw the source, given the two numbers that decide where it is.
     *
     * [pan] is which way round, in the same -1..1 SpatialField reads, and it spans the whole
     * circle: a half turn each way, so +-1 is directly behind the listener.
     *
     * The radius is one thing read off two numbers, because the two halves of the travel are
     * rendered by different arithmetic. Outside the handsets it is [retreat]: 0 on the ring and
     * 1 at the furthest the rule holds, heard as quieter and duller. Inside the handsets it is
     * [envelopment]: 0 on the ring and all of it at the listener, heard as the direction going
     * out of the sound.
     *
     * Inside is not louder, and cannot be. The sound comes out of the handsets, so a source
     * nearer than they are can only be asked for as a gain above one - the one thing this
     * project may never hand downstream, see the gains-multiply note on SpatialGain.highTrim.
     * What a source arriving from everywhere at once actually sounds like is a source with no
     * direction, and that is a thing the room can do exactly: every handset playing all of it.
     * A listener put it better than the code did - "相当于在他脑子里放歌".
     */
    fun spotOf(pan: Float, retreat: Float, envelopment: Float): SourceSpot {
        val azimuth = pan.coerceIn(-1f, 1f) * PI
        val inside = envelopmentFor(retreat, envelopment)
        val radius =
            if (inside > 0f) {
                val share = (inside / SpatialField.MAX_ENVELOPMENT.toFloat()).coerceIn(0f, 1f)
                // All of it is drawn in the exact middle rather than on the edge of the
                // listener's circle. The two have to stay inverses of each other - this and
                // [envelopmentOf] are the only two things that know where a dot is - and the
                // snap is only worth having if the dot is seen to land.
                if (share >= 1f) 0f
                else LISTENER_RADIUS + (SOURCE_HOME_RADIUS - LISTENER_RADIUS) * (1f - share)
            } else {
                SOURCE_HOME_RADIUS +
                    retreat.coerceIn(0f, 1f) * (SOURCE_MAX_RADIUS - SOURCE_HOME_RADIUS)
            }
        return SourceSpot(
            CENTRE + (radius * sin(azimuth)).toFloat(),
            // The same flip as layoutOf, and for the same reason: ahead is up the screen.
            CENTRE - (radius * cos(azimuth)).toFloat()
        )
    }

    /**
     * Which way round a source dropped at [spot] lies, as the -1..1 the rule reads.
     *
     * Every direction on the drawing is one the room can render, since 2026-09-18. A finger taken
     * round the back used to stop at the side, because the rule spanned the frontal half only;
     * with three phones in a room the handsets themselves stand back there, and a source that
     * could not go where a phone is standing was the drawing refusing a place it had drawn.
     *
     * The clamp that is left is arithmetic rather than policy: atan2 already answers within half
     * a turn either way, and this keeps the division landing exactly on the ends.
     */
    fun panOf(spot: SourceSpot): Float {
        val across = (spot.x - CENTRE).toDouble()
        val ahead = (CENTRE - spot.y).toDouble()
        if (across == 0.0 && ahead == 0.0) return 0f
        return (atan2(across, ahead) / PI).toFloat().coerceIn(-1f, 1f)
    }

    /**
     * How far off a source dropped at [spot] is, as the 0..1 the rule reads.
     *
     * Both ends clamp. Inside the ring of handsets this is zero and [envelopmentOf] is what the
     * radius means instead - the source is not retreating in there, it is arriving from more and
     * more directions at once.
     */
    fun retreatOf(spot: SourceSpot): Float {
        val radius = hypot((spot.x - CENTRE).toDouble(), (CENTRE - spot.y).toDouble()).toFloat()
        return ((radius - SOURCE_HOME_RADIUS) / (SOURCE_MAX_RADIUS - SOURCE_HOME_RADIUS))
            .coerceIn(0f, 1f)
    }

    /**
     * How little direction a source dropped at [spot] has, as the 0..MAX_ENVELOPMENT the rule
     * reads: nothing on the ring of handsets, all of it at the listener.
     *
     * Zero everywhere outside the ring, so this and [retreatOf] are never both answering at once -
     * see [envelopmentFor], which is the same statement said where the rule can read it.
     */
    fun envelopmentOf(spot: SourceSpot): Float {
        val radius = hypot((spot.x - CENTRE).toDouble(), (CENTRE - spot.y).toDouble()).toFloat()
        if (radius >= SOURCE_HOME_RADIUS) return 0f
        // Anywhere inside the listener's own circle is the listener - see [LISTENER_RADIUS].
        if (radius <= LISTENER_RADIUS) return SpatialField.MAX_ENVELOPMENT.toFloat()
        // Measured from the edge of that circle rather than from the middle, so the travel the
        // snap ate is not also charged to the ramp: a finger between the ring of handsets and
        // the listener moves the dot exactly as far as it moved, and nothing jumps at the seam.
        val inwards = 1f - (radius - LISTENER_RADIUS) / (SOURCE_HOME_RADIUS - LISTENER_RADIUS)
        return (inwards * SpatialField.MAX_ENVELOPMENT.toFloat())
            .coerceIn(0f, SpatialField.MAX_ENVELOPMENT.toFloat())
    }

    /**
     * The envelopment a dot at [retreat] and [envelopment] actually has, which is none of it once
     * the dot is outside the handsets.
     *
     * A dot is one place, and one place is one radius - but the radius is carried as two numbers
     * because the rule needs them separately, and nothing stops both being set at once. Choosing
     * an effect is where that happens: 旋转 sets an envelopment, and switching from it to 自定义
     * 声音位置 lands in a room that has both. Whichever way that is resolved, the picture and the
     * sound have to resolve it the same way or the drawing is describing a room nobody is hearing.
     * So it is written once, here, and read by [spotOf] and by ruleOf.
     *
     * Retreat wins because retreat is the half a finger can see it has asked for: it is the only
     * one of the two with a readout under the map.
     */
    fun envelopmentFor(retreat: Float, envelopment: Float): Float =
        if (retreat > 0f) 0f else envelopment

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
