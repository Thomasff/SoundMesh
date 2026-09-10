package com.soundmesh.core

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialGainTest {
    private val pair = SpatialLayout(
        listOf(SpatialPosition("left", -1.0, 0.0), SpatialPosition("right", 1.0, 0.0))
    )
    private val triangle = SpatialLayout(
        listOf(
            SpatialPosition("left", -1.0, 0.0),
            SpatialPosition("front", 0.0, 1.0),
            SpatialPosition("right", 1.0, 0.0)
        )
    )

    /** Half a period per side: a quarter in is hard right, three quarters in is hard left. */
    private val circling = SpatialField(
        mode = SpatialMode.ROTATE, layout = pair, periodNanos = 4_000_000_000L, epochHostNanos = 0L
    )

    /**
     * Two handsets facing each other across the listener are the case the raised cosine is exactly
     * right for: it reduces to constant-power panning, so the centre sits at 1/sqrt(2) on both.
     */
    @Test
    fun aSourceBetweenTwoHandsetsSitsAtEqualPowerOnBoth()  {
        val centred = SpatialField(mode = SpatialMode.PAN, layout = pair, pan = 0.0)

        assertEquals(0.70710678, centred.gainAt("left", 0L).left, 1e-7)
        assertEquals(0.70710678, centred.gainAt("right", 0L).right, 1e-7)
    }

    /** Dragged all the way over, the far handset is silent rather than merely quieter. */
    @Test
    fun aSourceDraggedHardOverLeavesTheFarHandsetSilent() {
        val hardRight = SpatialField(mode = SpatialMode.PAN, layout = pair, pan = 1.0)

        assertEquals(0.0, hardRight.gainAt("left", 0L).left, 1e-12)
        assertEquals(0.0, hardRight.gainAt("left", 0L).right, 1e-12)
        assertEquals(1.0, hardRight.gainAt("right", 0L).left, 1e-12)
        assertEquals(1.0, hardRight.gainAt("right", 0L).right, 1e-12)
    }

    /**
     * The sweep turns clockwise: a quarter of the way round it is on the right, three quarters
     * round it is on the left. A rotation running the other way is the symptom a swapped pair of
     * icons produces, so which way it goes has to be pinned down here rather than noticed by ear.
     */
    @Test
    fun theSweepReachesTheRightHandsetFirst() {
        assertEquals(1.0, circling.gainAt("right", 1_000_000_000L).left, 1e-12)
        assertEquals(0.0, circling.gainAt("left", 1_000_000_000L).left, 1e-12)

        assertEquals(1.0, circling.gainAt("left", 3_000_000_000L).left, 1e-12)
        assertEquals(0.0, circling.gainAt("right", 3_000_000_000L).left, 1e-12)
    }

    /**
     * The sweep repeats, and repeats on the period it was given. Written down because the period
     * is the one number a listener sets and the only one a wrong scaling would hide inside: a
     * sweep running at half or twice the asked-for rate still looks like a sweep.
     */
    @Test
    fun theSweepRepeatsOnceEachPeriod() {
        for (step in 0 until 8) {
            val at = step * 4_000_000_000L / 8
            assertEquals(
                "step $step",
                circling.gainAt("left", at).left,
                circling.gainAt("left", at + 4_000_000_000L).left,
                1e-12
            )
        }
        // A quarter period in is not the same place, or the check above would hold for a sweep
        // that never moved. Half a period is no good here: with a facing pair, straight ahead and
        // straight behind share the same split.
        assertTrue(
            circling.gainAt("left", 0L).left != circling.gainAt("left", 1_000_000_000L).left
        )
    }

    /**
     * The whole point of normalising: a source crossing the gap between handsets must not be
     * heard as the volume dipping. Sampled right through a circuit rather than at the corners,
     * because the corners are where every panning law is already correct.
     */
    @Test
    fun theRoomEmitsTheSamePowerThroughoutACircuit() {
        val field = SpatialField(
            mode = SpatialMode.ROTATE, layout = triangle, periodNanos = 4_000_000_000L
        )

        for (step in 0 until 64) {
            val at = step * 4_000_000_000L / 64
            val power = triangle.peerIds.sumOf {
                val gain = field.gainAt(it, at)
                (gain.left * gain.left + gain.right * gain.right) / 2.0
            }
            assertEquals("power at step $step", 1.0, power, 1e-9)
        }
    }

    /** Splitting across a facing pair is the whole stereo image, one side per handset. */
    @Test
    fun splittingGivesEachHandsetTheSideItStandsOn() {
        val split = SpatialField(mode = SpatialMode.SPLIT, layout = pair)

        assertEquals(1.0, split.gainAt("left", 0L).left, 1e-12)
        assertEquals(0.0, split.gainAt("left", 0L).right, 1e-12)
        assertEquals(0.0, split.gainAt("right", 0L).left, 1e-12)
        assertEquals(1.0, split.gainAt("right", 0L).right, 1e-12)
    }

    /**
     * A handset straight ahead stands on neither side, so it carries both halves equally rather
     * than being left out. Scaled with the others so three handsets are no louder than two.
     */
    @Test
    fun aHandsetStraightAheadCarriesBothSidesOfTheSplit() {
        val split = SpatialField(mode = SpatialMode.SPLIT, layout = triangle)
        val scale = 1.0 / sqrt(1.5)

        assertEquals(0.70710678 * scale, split.gainAt("front", 0L).left, 1e-7)
        assertEquals(0.70710678 * scale, split.gainAt("front", 0L).right, 1e-7)
        assertEquals(scale, split.gainAt("left", 0L).left, 1e-7)
        assertEquals(0.0, split.gainAt("left", 0L).right, 1e-12)
    }

    /** Ignoring where a handset sits is exactly the defect this test exists to catch. */
    @Test
    fun splittingIsWeakWhenEveryHandsetIsStraightAhead() {
        val ahead = SpatialLayout(
            listOf(SpatialPosition("a", -0.05, 1.0), SpatialPosition("b", 0.05, 1.0))
        )
        val split = SpatialField(mode = SpatialMode.SPLIT, layout = ahead)

        val a = split.gainAt("a", 0L)
        assertTrue("a barely leans left: $a", a.left - a.right in 0.01..0.1)
    }

    /**
     * A direction no handset can render is a wrong position at worst; silence would be a dropout,
     * and a dropout is blamed on the network rather than on the drawing that caused it.
     */
    @Test
    fun aDirectionTheRoomCannotRenderIsSharedOutRatherThanSilent() {
        val alone = SpatialLayout(listOf(SpatialPosition("a", 0.0, 1.0)))
        val field = SpatialField(mode = SpatialMode.ROTATE, layout = alone, periodNanos = 4_000_000_000L)

        // Half a circuit in, the source is directly behind the only handset in the room.
        assertEquals(1.0, field.gainAt("a", 2_000_000_000L).left, 1e-12)
        assertEquals(1.0, field.gainAt("a", 2_000_000_000L).right, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCircuitThatTakesNoTimeIsRefused() {
        SpatialField(mode = SpatialMode.ROTATE, layout = pair, periodNanos = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPanBeyondTheEndsOfTheControlIsRefused() {
        SpatialField(mode = SpatialMode.PAN, layout = pair, pan = 1.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun askingForAHandsetTheLayoutDoesNotHoldThrows() {
        SpatialField(mode = SpatialMode.PAN, layout = pair).gainAt("elsewhere", 0L)
    }

    /**
     * Full separation on a handset carrying the middle is exactly the mid signal: half of each
     * channel, which is what the two channels agree about. Expressed as one signed number rather
     * than a matrix because both rows of that matrix hold the same two values swapped - a handset
     * keeps [1 - abs(fold)] of its own channel and folds [fold] of the other one in.
     */
    @Test
    fun aHandsetCarryingTheMiddleFoldsInHalfOfTheOtherChannel() {
        val field = SpatialField(mode = SpatialMode.SPLIT, layout = pair, separation = 1.0)

        assertEquals(0.5, field.foldFor("left"), 1e-12)
        assertEquals(0.5, field.foldFor("right"), 1e-12)
    }

    /** The sides are the same fold with the sign turned round: what the two channels disagree about. */
    @Test
    fun aHandsetCarryingTheSidesFoldsTheOtherChannelInNegated() {
        val field = SpatialField(
            mode = SpatialMode.SPLIT, layout = pair, separation = 1.0, otherHalfIds = setOf("right")
        )

        assertEquals(0.5, field.foldFor("left"), 1e-12)
        assertEquals(-0.5, field.foldFor("right"), 1e-12)
    }

    /**
     * The knob at zero is the whole point of it being a knob: every handset plays the mix it was
     * sent, which is what the room did before this existed. It is also where the room lands when
     * the sync is too poor to carry a separation, so this is the degraded state as much as the
     * default one.
     */
    @Test
    fun aKnobAtZeroLeavesEveryHandsetPlayingTheWholeMix() {
        val field = SpatialField(mode = SpatialMode.SPLIT, layout = pair, otherHalfIds = setOf("right"))

        assertEquals(0.0, field.foldFor("left"), 1e-12)
        assertEquals(0.0, field.foldFor("right"), 1e-12)
    }

    /** Halfway along the knob is halfway to the fold, so the control has no dead travel. */
    @Test
    fun theKnobMovesProportionallyRatherThanSnapping() {
        val field = SpatialField(
            mode = SpatialMode.SPLIT, layout = pair, separation = 0.5, otherHalfIds = setOf("right")
        )

        assertEquals(0.25, field.foldFor("left"), 1e-12)
        assertEquals(-0.25, field.foldFor("right"), 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSeparationBeyondTheEndsOfTheKnobIsRefused() {
        SpatialField(mode = SpatialMode.SPLIT, layout = pair, separation = 1.5)
    }

    /** A name the drawing does not show cannot be given a part in it. */
    @Test(expected = IllegalArgumentException::class)
    fun givingTheSidesToAHandsetTheDrawingDoesNotShowIsRefused() {
        SpatialField(mode = SpatialMode.SPLIT, layout = pair, otherHalfIds = setOf("elsewhere"))
    }

    /**
     * The other axis. Which part of the spectrum a handset carries, rather than which part of the
     * stereo image - and it is written as a crossfade from the whole mix toward this handsets own
     * half, so the knob means the same thing on both axes and lands in the same place at zero.
     *
     * At full separation the low handset plays none of the mix as sent and all of what the filter
     * kept, which is why the whole coefficient is zero rather than one.
     */
    @Test
    fun aHandsetCarryingTheLowHalfPlaysWhatTheFilterKept() {
        val field = SpatialField(
            mode = SpatialMode.SPLIT, layout = pair,
            splitAxis = SplitAxis.LOW_HIGH, separation = 1.0, otherHalfIds = setOf("right")
        )

        assertEquals(0.0, field.spectrumFor("left").whole, 1e-12)
        assertEquals(1.0, field.spectrumFor("left").low, 1e-12)
    }

    /**
     * The high half is never filtered for. It is whatever is left of the mix once the low half is
     * taken out of it, which is what makes the two add back up exactly whatever the filter does.
     */
    @Test
    fun aHandsetCarryingTheHighHalfPlaysTheMixWithTheLowHalfTakenOut() {
        val field = SpatialField(
            mode = SpatialMode.SPLIT, layout = pair,
            splitAxis = SplitAxis.LOW_HIGH, separation = 1.0, otherHalfIds = setOf("right")
        )

        assertEquals(1.0, field.spectrumFor("right").whole, 1e-12)
        assertEquals(-1.0, field.spectrumFor("right").low, 1e-12)
    }

    /** Full separation, the two handsets summed: the mix, once, with nothing of the filter left over. */
    @Test
    fun theTwoSpectrumHalvesAddBackUpToTheMix() {
        val field = SpatialField(
            mode = SpatialMode.SPLIT, layout = pair,
            splitAxis = SplitAxis.LOW_HIGH, separation = 1.0, otherHalfIds = setOf("right")
        )
        val low = field.spectrumFor("left")
        val high = field.spectrumFor("right")

        assertEquals(1.0, low.whole + high.whole, 1e-12)
        assertEquals(0.0, low.low + high.low, 1e-12)
    }

    /** The same landing place as the other axis: the knob at zero is the mix the room was already playing. */
    @Test
    fun aKnobAtZeroLeavesTheSpectrumAlone() {
        val field = SpatialField(
            mode = SpatialMode.SPLIT, layout = pair,
            splitAxis = SplitAxis.LOW_HIGH, otherHalfIds = setOf("right")
        )

        assertEquals(1.0, field.spectrumFor("right").whole, 1e-12)
        assertEquals(0.0, field.spectrumFor("right").low, 1e-12)
    }

    /**
     * A room separates along one axis at a time, and the set of handsets on the far side of it is
     * shared between the two. Without this a rule would carry two separations that a listener set
     * one at a time, and the handset that was given the sides would silently also be given the
     * high half - a room nobody drew.
     */
    @Test
    fun onlyOneAxisSeparatesAtATime() {
        val spectrum = SpatialField(
            mode = SpatialMode.SPLIT, layout = pair,
            splitAxis = SplitAxis.LOW_HIGH, separation = 1.0, otherHalfIds = setOf("right")
        )
        val image = SpatialField(
            mode = SpatialMode.SPLIT, layout = pair,
            splitAxis = SplitAxis.MIDDLE_SIDES, separation = 1.0, otherHalfIds = setOf("right")
        )

        assertEquals(0.0, spectrum.foldFor("right"), 1e-12)
        assertEquals(1.0, image.spectrumFor("right").whole, 1e-12)
        assertEquals(0.0, image.spectrumFor("right").low, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCrossoverBelowAnythingAudibleIsRefused() {
        SpatialField(mode = SpatialMode.SPLIT, layout = pair, crossoverHz = 5.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCrossoverAboveAnythingAudibleIsRefused() {
        SpatialField(mode = SpatialMode.SPLIT, layout = pair, crossoverHz = 30_000.0)
    }
}
