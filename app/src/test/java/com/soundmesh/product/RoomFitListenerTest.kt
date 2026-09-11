package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The listener measured rather than assumed, which is the one piece of the room nobody could check.
 *
 * The room below is drawn the way a person actually draws one: the handsets in the right shape,
 * and themselves in the middle of them. They are not in the middle - they are a metre behind the
 * middle - and the drawing has no way to say so. Every number playback reads is listener-relative,
 * so that one metre is the whole error, and here it is large enough to put the handsets in the
 * wrong order of loudness and a third of a right angle off in direction.
 */
class RoomFitListenerTest {
    private val a = "a1b2c3d4e5f60718"
    private val b = "0918273645abcdef"
    private val c = "1122334455667788"

    // The room as it really is, in metres, listener at the origin, y forward.
    private val trueMetres = mapOf(
        a to Pair(0.0, 2.0),
        b to Pair(-1.5, 0.5),
        c to Pair(1.5, 0.5)
    )

    /** What the overhead round would report: the listener to each handset. */
    private val listenerMetres = trueMetres.mapValues { hypot(it.value.first, it.value.second) }

    private val measured = bothWays(
        mapOf(
            (a to b) to apartInMetres(a, b),
            (a to c) to apartInMetres(a, c),
            (b to c) to apartInMetres(b, c)
        )
    )

    // The same room drawn: the shape exactly right, the listener put in the middle of the
    // handsets rather than where they are sitting. 0.2 of the drawing's side per metre.
    private val sketch = listOf(
        RoomIcon(a, 0.50f, 0.30f),
        RoomIcon(b, 0.20f, 0.60f),
        RoomIcon(c, 0.80f, 0.60f)
    )

    private fun apartInMetres(one: String, two: String): Double {
        val first = trueMetres.getValue(one)
        val second = trueMetres.getValue(two)
        return hypot(first.first - second.first, first.second - second.second)
    }

    private fun bothWays(pairs: Map<Pair<String, String>, Double>) =
        pairs.entries.flatMap { listOf(it.key to it.value, (it.key.second to it.key.first) to it.value) }
            .toMap()

    /** Which way an icon lies from the listener, in degrees, which is what the pan reads. */
    private fun bearing(icons: List<RoomIcon>, peerId: String): Double {
        val icon = icons.first { it.peerId == peerId }
        return atan2(
            (icon.x - SpatialRoom.CENTRE).toDouble(),
            (SpatialRoom.CENTRE - icon.y).toDouble()
        ) * 180.0 / PI
    }

    private fun trueBearing(peerId: String): Double {
        val at = trueMetres.getValue(peerId)
        return atan2(at.first, at.second) * 180.0 / PI
    }

    /** How far an icon is from the listener, which is what the distance gain reads. */
    private fun radius(icons: List<RoomIcon>, peerId: String): Double {
        val icon = icons.first { it.peerId == peerId }
        return hypot(
            (icon.x - SpatialRoom.CENTRE).toDouble(),
            (icon.y - SpatialRoom.CENTRE).toDouble()
        )
    }

    private fun worstBearingError(icons: List<RoomIcon>): Double =
        trueMetres.keys.maxOf { abs(bearing(icons, it) - trueBearing(it)) }

    @Test
    fun `the listener is put where it was measured rather than in the middle of the handsets`() {
        val fitted = RoomFit.corrected(sketch, measured, listenerMetres)
        assertNotNull(fitted)
        assertTrue(
            "worst bearing was ${worstBearingError(fitted!!)} degrees out",
            worstBearingError(fitted) < 10.0
        )
    }

    @Test
    fun `drawing yourself in the middle gets the order of loudness backwards`() {
        // Drawn, the handset straight ahead looks nearest of the three. It is the furthest.
        assertTrue(radius(sketch, a) < radius(sketch, b))

        val fitted = RoomFit.corrected(sketch, measured, listenerMetres)!!
        assertTrue(radius(fitted, a) > radius(fitted, b))

        val want = listenerMetres.getValue(a) / listenerMetres.getValue(b)
        assertEquals(want, radius(fitted, a) / radius(fitted, b), 0.1)
    }

    @Test
    fun `without the overhead round the drawing keeps the listener in the middle and stays wrong`() {
        val fitted = RoomFit.corrected(sketch, measured)
        assertNotNull(fitted)
        // The handset shape was already right, so the fit has nothing to correct and says so.
        assertTrue(
            "worst bearing was ${worstBearingError(fitted!!)} degrees out",
            worstBearingError(fitted) > 25.0
        )
    }

    @Test
    fun `one distance to the listener is a circle and is not enough to place them`() {
        val alone = mapOf(a to listenerMetres.getValue(a))
        assertEquals(RoomFit.corrected(sketch, measured), RoomFit.corrected(sketch, measured, alone))
    }

    @Test
    fun `distances to handsets that are not in the room are ignored`() {
        val strays = listenerMetres + mapOf("ffffffffffffffff" to 1.0)
        assertEquals(
            RoomFit.corrected(sketch, measured, listenerMetres),
            RoomFit.corrected(sketch, measured, strays)
        )
    }

    @Test
    fun `a listener no arrangement can reach is refused rather than placed somewhere`() {
        // Five metres from the handset straight ahead, and a metre and a half from the two that are
        // themselves two metres from it. No point in any room is all three.
        val impossible = listenerMetres + mapOf(a to 5.0)
        assertNull(RoomFit.corrected(sketch, measured, impossible))
    }
}
