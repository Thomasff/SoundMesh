package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialMode
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

    // ---- the round as it is actually run -----------------------------------------------------
    //
    // Somebody holds one handset over their head, so that handset measures the listener to every
    // other one - and never to itself. It is about to be put back somewhere else entirely, so the
    // one distance nothing in the room can produce is the listener to the handset being held.
    //
    // That leaves the listener with N-1 measured distances, and a point in a plane needs three to
    // be pinned. With three handsets it has two, which is a fold: reflecting the listener across
    // the line through the two it did measure satisfies both of them exactly. The measurement
    // cannot choose, and the drawing can - the same division of labour as the room's own mirror.

    // A room drawn the way people draw one: everybody in front, the person in the middle of the
    // screen. Metres: the held handset 2.5 ahead, the other two 1.2 aside and 1.0 ahead.
    private val held = a
    private val realistic = listOf(
        RoomIcon(a, 0.50f, 0.22f),
        RoomIcon(b, 0.26f, 0.42f),
        RoomIcon(c, 0.74f, 0.42f)
    )
    private val realisticMetres = mapOf(
        a to Pair(0.0, 2.5),
        b to Pair(-1.2, 1.0),
        c to Pair(1.2, 1.0)
    )

    private fun apartIn(at: Map<String, Pair<Double, Double>>, one: String, two: String): Double {
        val first = at.getValue(one)
        val second = at.getValue(two)
        return hypot(first.first - second.first, first.second - second.second)
    }

    private fun bearingIn(at: Map<String, Pair<Double, Double>>, peerId: String): Double {
        val where = at.getValue(peerId)
        return atan2(where.first, where.second) * 180.0 / PI
    }

    /**
     * The handset being held is placed by the pairs it is in, not by a distance to the listener,
     * and that is enough: with three handsets the shape is rigid and only the listener is loose.
     */
    @Test
    fun `the handset being held has no distance of its own and is placed by the others`() {
        val measured = bothWays(
            mapOf(
                (a to b) to apartIn(realisticMetres, a, b),
                (a to c) to apartIn(realisticMetres, a, c),
                (b to c) to apartIn(realisticMetres, b, c)
            )
        )
        val fromListener = realisticMetres
            .filterKeys { it != held }
            .mapValues { hypot(it.value.first, it.value.second) }

        val fitted = RoomFit.corrected(realistic, measured, fromListener)!!

        val worst = realisticMetres.keys.maxOf {
            abs(bearing(fitted, it) - bearingIn(realisticMetres, it))
        }
        assertTrue("worst bearing was $worst degrees out", worst < 10.0)
        // Drawn, the three look about equally far off. They are not: the held one is 1.6 times
        // further, and that ratio is the whole of what the distance gain reads. It does not land on
        // the truth and is not meant to - the prior holds the answer still, and with two measured
        // distances against a weight of 0.3 the listener stops a little short of where it was
        // measured. What it has to do is take most of the error out: 3.2 dB becomes 0.9 dB here.
        val want = 2.5 / hypot(1.2, 1.0)
        val drawnOut = abs(radius(realistic, a) / radius(realistic, b) - want)
        val fittedOut = abs(radius(fitted, a) / radius(fitted, b) - want)
        assertTrue("drawn was $drawnOut out, fitted is $fittedOut out", fittedOut < drawnOut / 2)
    }

    /**
     * The fold, stated rather than hidden: which side of its two measured handsets the listener is
     * on comes from the drawing, and a drawing that has it wrong keeps it wrong.
     *
     * The room below is drawn with two handsets *behind* the listener while they are really in
     * front, which puts the drawn listener on the wrong side of the line through them - and the
     * reflected answer fits both measured distances exactly, so nothing in the measurement objects.
     * A fourth handset would end it: three distances pin a point in a plane.
     */
    @Test
    fun `with three handsets the side the listener sits on comes from the drawing`() {
        val twoOfThem = listenerMetres.filterKeys { it != a }

        val fitted = RoomFit.corrected(sketch, measured, twoOfThem)!!

        assertTrue(
            "worst bearing was ${worstBearingError(fitted)} degrees out",
            worstBearingError(fitted) > 25.0
        )
    }

    // ---- the scale, which is what the delay is computed from ---------------------------------

    /** Metres between two handsets, as the fit ended up believing them to be. */
    private fun fittedApart(fit: FittedRoom, one: String, two: String): Double {
        val first = fit.icons.first { it.peerId == one }
        val second = fit.icons.first { it.peerId == two }
        return hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()) *
            fit.metresPerUnit
    }

    /**
     * The drawing has never carried a size and has never needed to: every gain is a ratio. The
     * arrival delay is the first reader that is not, so the fit now says what it found.
     */
    @Test
    fun `the fit says how large the room is, in metres it was told`() {
        val fit = RoomFit.fitted(sketch, measured, listenerMetres)!!

        assertEquals(apartInMetres(a, b), fittedApart(fit, a, b), 0.3)
        assertEquals(apartInMetres(b, c), fittedApart(fit, b, c), 0.3)
    }

    /**
     * And says nothing when the listener was never measured, which is a decision rather than a
     * gap: such a room knows its own size perfectly well and still cannot say where anybody is
     * sitting. A delay taken from an assumed listener holds the wrong handset back.
     */
    @Test
    fun `a room measured handset to handset alone reports no scale`() {
        assertEquals(0.0, RoomFit.fitted(sketch, measured)!!.metresPerUnit, 0.0)
    }

    /**
     * What the scale is for, end to end: how much later the near handsets have to play.
     *
     * Truth here is 2.5 m against 1.56 m, which is 2.7 ms - nearly three times the millisecond
     * past which the earlier handset takes the image outright. The fit does not land on it and is
     * not meant to: with two measured listener edges against a prior of 0.3 the listener stops a
     * little short, exactly as it does for the gain. What it has to do is take most of it out.
     */
    @Test
    fun `the delay the room ends up asking for is most of the delay it should`() {
        val measured = bothWays(
            mapOf(
                (a to b) to apartIn(realisticMetres, a, b),
                (a to c) to apartIn(realisticMetres, a, c),
                (b to c) to apartIn(realisticMetres, b, c)
            )
        )
        val fromListener = realisticMetres
            .filterKeys { it != held }
            .mapValues { hypot(it.value.first, it.value.second) }

        val fit = RoomFit.fitted(realistic, measured, fromListener)!!
        val field = SpatialField(
            SpatialMode.SPLIT,
            SpatialRoom.layoutOf(fit.icons)!!,
            metresPerUnit = fit.metresPerUnit
        )

        val want = (2.5 - hypot(1.2, 1.0)) / 343.0 * 1000
        val got = field.arrivalDelayNanosFor(b) / 1_000_000.0
        assertEquals(0L, field.arrivalDelayNanosFor(a))
        assertTrue("wanted $want ms, waits $got ms", abs(got - want) < 1.0)
        assertTrue("waits $got ms, which is not most of $want ms", got > want / 2)
    }
}
