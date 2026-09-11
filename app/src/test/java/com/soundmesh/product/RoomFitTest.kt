package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Moving the icons onto what the room measured, and the four times it must refuse to.
 *
 * The drawing is listener-centred and has no scale, so every assertion here is about ratios of
 * lengths and about who is further than whom. An answer that got the shape right and the size
 * wrong is the same answer.
 */
class RoomFitTest {
    private val a = "a1b2c3d4e5f60718"
    private val b = "0918273645abcdef"
    private val c = "1122334455667788"

    // One room, drawn at 5 metres to the drawing's side, listener at the middle.
    private val drawnA = RoomIcon(a, 0.50f, 0.20f)
    private val drawnB = RoomIcon(b, 0.26f, 0.68f)
    private val drawnC = RoomIcon(c, 0.82f, 0.60f)
    private val sketch = listOf(drawnA, drawnB, drawnC)

    private val measured = bothWays(
        mapOf(
            (a to b) to 2.6833,
            (a to c) to 2.5613,
            (b to c) to 2.8284
        )
    )

    private fun bothWays(pairs: Map<Pair<String, String>, Double>) =
        pairs.entries.flatMap { listOf(it.key to it.value, (it.key.second to it.key.first) to it.value) }
            .toMap()

    private fun apart(icons: List<RoomIcon>, one: String, two: String): Double {
        val first = icons.first { it.peerId == one }
        val second = icons.first { it.peerId == two }
        return hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble())
    }

    /** Which way an icon lies from the listener, in radians. */
    private fun bearing(icon: RoomIcon): Double = atan2(
        (icon.x - SpatialRoom.CENTRE).toDouble(),
        (SpatialRoom.CENTRE - icon.y).toDouble()
    )

    /** How wrong the drawn ratio of two edges is against the measured ratio of the same two. */
    private fun ratioError(icons: List<RoomIcon>): Double {
        val drawn = apart(icons, a, c) / apart(icons, a, b)
        return abs(drawn - measured[a to c]!! / measured[a to b]!!)
    }

    /**
     * A drawing that already agrees is left where the finger put it.
     *
     * The point of the prior is that everything the measurement does not determine - where the
     * room sits, which way it faces, which side is left - keeps the answer the person gave.
     */
    @Test
    fun aSketchThatAlreadyMatchesIsLeftWhereItIs() {
        val fitted = RoomFit.corrected(sketch, measured, priorWeight = 0.3)

        assertNotNull(fitted)
        for (icon in fitted!!) {
            val was = sketch.first { it.peerId == icon.peerId }
            assertEquals(was.x.toDouble(), icon.x.toDouble(), 0.01)
            assertEquals(was.y.toDouble(), icon.y.toDouble(), 0.01)
        }
    }

    /**
     * And a drawing with one handset put at the wrong distance has it moved, which is the whole
     * of what this is for: a person judges "over there on the left" well and "that one is 1.7
     * times further than this one" badly.
     */
    @Test
    fun aHandsetDrawnAtTheWrongDistanceIsMovedOntoTheMeasuredRatio() {
        val pulledIn = listOf(drawnA, drawnB, RoomIcon(c, 0.66f, 0.56f))

        val fitted = RoomFit.corrected(pulledIn, measured, priorWeight = 0.3)

        assertNotNull(fitted)
        assertTrue("the sketch should start out plainly wrong", ratioError(pulledIn) > 0.20)
        // Two thirds of the way and no further: the drawing is still holding some of it back,
        // which is the prior doing exactly what it is for.
        assertTrue(
            "was ${ratioError(pulledIn)}, now ${ratioError(fitted!!)}",
            ratioError(fitted) < ratioError(pulledIn) / 3.0
        )
    }

    /**
     * It fixes the lengths without turning the room round.
     *
     * Which way a handset lies is the other half of what the drawing feeds - panning reads it
     * where the gain reads the radius - and it is a half no distance can speak to, because the
     * distances between handsets are the same whichever way the room is facing. So the answer
     * has to leave the bearings roughly where the finger put them, and RoomFitWeightTest is
     * where that is held against rooms whose true bearings are known.
     */
    @Test
    fun theBearingsFromTheListenerAreLeftRoughlyAsDrawn() {
        val pulledIn = listOf(drawnA, drawnB, RoomIcon(c, 0.66f, 0.56f))

        val fitted = RoomFit.corrected(pulledIn, measured, priorWeight = 0.3)!!

        for (icon in fitted) {
            val was = pulledIn.first { it.peerId == icon.peerId }
            val turned = Math.toDegrees(abs(bearing(icon) - bearing(was)))
            assertTrue("${icon.peerId} turned $turned degrees", turned < 15.0)
        }
    }

    /**
     * The whole room changes size on the way, which is why nothing here measures how far one
     * icon travelled. Three drawn edges can only be brought onto three measured ones by moving
     * everybody, and the size they settle at is free - the drawing has no units, and nothing
     * downstream reads any.
     */
    @Test
    fun theRoomIsResizedRatherThanOneIconBeingDraggedOut() {
        val pulledIn = listOf(drawnA, drawnB, RoomIcon(c, 0.66f, 0.56f))

        val fitted = RoomFit.corrected(pulledIn, measured, priorWeight = 0.3)!!

        assertTrue(
            "a-b was ${apart(pulledIn, a, b)}, now ${apart(fitted, a, b)}",
            apart(fitted, a, b) < apart(pulledIn, a, b)
        )
    }

    /** The same drawing always gets the same answer, however many times it is asked. */
    @Test
    fun theSameDrawingAlwaysGetsTheSameAnswer() {
        val pulledIn = listOf(drawnA, drawnB, RoomIcon(c, 0.66f, 0.56f))

        val once = RoomFit.corrected(pulledIn, measured, priorWeight = 0.3)!!
        val again = RoomFit.corrected(pulledIn, measured, priorWeight = 0.3)!!

        assertEquals(once, again)
    }

    /**
     * But fitting its own answer walks further off the drawing every time, which is the one
     * way a caller can get this wrong.
     *
     * Each pass closes most of the gap between drawn and measured and leaves the rest. Feed the
     * answer back in and the prior is anchored to the last fit rather than to a person, so the
     * next pass closes most of what was left. Pressed enough times the prior has been annealed
     * away, which is the state the weight exists to prevent. The caller must always fit from
     * what the finger last drew, never from what this last answered.
     */
    @Test
    fun fittingItsOwnAnswerAgainWalksFurtherOffTheDrawing() {
        val once = RoomFit.corrected(listOf(drawnA, drawnB, RoomIcon(c, 0.66f, 0.56f)), measured, priorWeight = 0.3)!!

        val twice = RoomFit.corrected(once, measured, priorWeight = 0.3)!!

        assertTrue(
            "once ${ratioError(once)}, twice ${ratioError(twice)}",
            ratioError(twice) < ratioError(once)
        )
    }

    /** A handset nothing has measured stays exactly where it was dragged. */
    @Test
    fun aHandsetWithNoMeasuredDistanceKeepsItsPlace() {
        val fourth = RoomIcon("99aabbccddeeff00", 0.15f, 0.30f)

        val fitted = RoomFit.corrected(sketch + fourth, measured, priorWeight = 0.3)!!

        val now = fitted.first { it.peerId == fourth.peerId }
        assertEquals(fourth.x.toDouble(), now.x.toDouble(), 1e-6)
        assertEquals(fourth.y.toDouble(), now.y.toDouble(), 1e-6)
    }

    /**
     * Two handsets have one distance between them, and one distance is a scale. Scale is the one
     * thing the drawing deliberately does not carry, so there is nothing here to correct.
     */
    @Test
    fun aRoomOfTwoIsNotCorrected() {
        assertNull(RoomFit.corrected(listOf(drawnA, drawnB), measured, priorWeight = 0.3))
    }

    /** Same for three handsets with only one pair read: one length still fixes only the size. */
    @Test
    fun oneMeasuredEdgeIsNotEnoughToCorrectAnything() {
        val only = bothWays(mapOf((a to b) to 2.6833))

        assertNull(RoomFit.corrected(sketch, only, priorWeight = 0.3))
    }

    /**
     * Distances that describe no room at all are refused rather than fitted.
     *
     * Least squares is happy to answer here - it squashes the room flat and returns it - and the
     * answer looks exactly like a real room drawn badly. The residual is what tells them apart,
     * and it is the only self-check three handsets have: with all three pairs read the fit is
     * exact whenever a triangle exists, so a residual at all means one does not.
     */
    @Test
    fun distancesThatDescribeNoRoomAreRefused() {
        val impossible = bothWays(mapOf((a to b) to 5.0, (a to c) to 1.0, (b to c) to 1.0))

        assertNull(RoomFit.corrected(sketch, impossible, priorWeight = 0.3))
    }

    /**
     * A badly drawn room whose measurements agree perfectly is fitted, not refused.
     *
     * This is the whole reason the refusal asks its question with the prior turned nearly off.
     * The question is whether the distances describe a room, which is a fact about the distances
     * alone; ask it of the answer that ships and the prior is in the way, holding the icons off
     * the shape they were measured into and leaving a residual that has nothing to do with the
     * measurement. The worse the drawing, the more certainly a perfectly good room is thrown out
     * - which is precisely backwards, because a bad drawing is what this exists to fix.
     */
    @Test
    fun aRoomDrawnNothingLikeItsMeasurementsIsStillFitted() {
        val strungOut = listOf(
            RoomIcon(a, 0.50f, 0.30f),
            RoomIcon(b, 0.47f, 0.50f),
            RoomIcon(c, 0.53f, 0.70f)
        )

        assertNotNull(RoomFit.corrected(strungOut, measured, priorWeight = 0.3))
    }

    /** A room measured a little inconsistently is still fitted - that is what the fit is for. */
    @Test
    fun aRoomMeasuredSlightlyInconsistentlyIsStillFitted() {
        val noisy = bothWays(mapOf((a to b) to 2.80, (a to c) to 2.45, (b to c) to 2.90))

        assertNotNull(RoomFit.corrected(sketch, noisy, priorWeight = 0.3))
    }

    /**
     * A mirrored answer is refused outright.
     *
     * Three measured distances fix a triangle's shape and say nothing about its mirror image, so
     * nothing in the data prefers one. The drawing does, and it is the only thing that can: a
     * mirrored room is the one defect a listener cannot diagnose by ear, because it sounds
     * exactly like a working one.
     */
    @Test
    fun anAnswerThatTurnedTheRoomInsideOutIsRefused() {
        val mirroredC = RoomIcon(c, 1.0f - 0.82f, 0.60f)

        assertTrue(RoomFit.turnedInsideOut(sketch, listOf(drawnA, drawnB, mirroredC)))
        assertFalse(RoomFit.turnedInsideOut(sketch, sketch))
    }

    /**
     * A prior of zero is refused rather than treated as "trust the measurement completely".
     *
     * With no prior the distance term is all there is, and it is blind to where the room sits,
     * which way it faces and which way round it is - the three things every gain in the system
     * reads, because every gain is measured from the listener and no measurement here has ever
     * touched the listener.
     */
    @Test(expected = IllegalArgumentException::class)
    fun aPriorOfZeroIsRefused() {
        RoomFit.corrected(sketch, measured, priorWeight = 0.0)
    }

    /** Whatever it answers stays on the drawing, because an icon off the canvas cannot be dragged. */
    @Test
    fun theAnswerStaysInsideTheDrawing() {
        val squashed = listOf(
            RoomIcon(a, 0.50f, 0.46f),
            RoomIcon(b, 0.47f, 0.53f),
            RoomIcon(c, 0.53f, 0.54f)
        )

        val fitted = RoomFit.corrected(squashed, measured, priorWeight = 0.3)!!

        for (icon in fitted) {
            assertTrue("${icon.peerId} at ${icon.x},${icon.y}", icon.x in 0f..1f && icon.y in 0f..1f)
        }
    }
}
