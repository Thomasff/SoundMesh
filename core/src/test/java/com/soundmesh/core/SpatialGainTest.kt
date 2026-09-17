package com.soundmesh.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
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

    /**
     * Dragging the split down tilts the balance toward the low half, by trimming the high one.
     *
     * Heard on 2026-09-10, immediately after the skirt was steepened: at 100 Hz the low handset
     * went from "the melody is still followable" to very nearly nothing. That is the steepening
     * working, and it is also the feature disappearing exactly where a listener asked for it -
     * somebody who drags the split to the bottom wants the low part, not silence.
     *
     * Six decibels an octave below the default, which is exactly "halve the split, double the
     * difference" - a ratio of frequencies, no logarithm needed. Neutral at and above the default,
     * so every test that asserts the two halves add back up goes on holding where it is written.
     */
    @Test
    fun halvingTheSplitHalvesTheHighHalf() {
        val atDefault =
        SpatialField(
            mode = SpatialMode.SPLIT, layout = pair, splitAxis = SplitAxis.LOW_HIGH,
            separation = 1.0, otherHalfIds = setOf("right"), crossoverHz = SpatialField.DEFAULT_CROSSOVER_HZ
        )
        val anOctaveDown =
        SpatialField(
            mode = SpatialMode.SPLIT, layout = pair, splitAxis = SplitAxis.LOW_HIGH,
            separation = 1.0, otherHalfIds = setOf("right"), crossoverHz = SpatialField.DEFAULT_CROSSOVER_HZ / 2
        )

        assertEquals(1.0, atDefault.spectrumFor("right").whole, 1e-12)
        assertEquals(0.5, anOctaveDown.spectrumFor("right").whole, 1e-12)
        // And the low handset is left exactly where it was.
        assertEquals(1.0, anOctaveDown.spectrumFor("left").low, 1e-12)
    }

    /**
     * Trimmed rather than lifted, and this is the whole reason the tilt is arranged this way.
     *
     * It was built the other way round first - lift the low half - and a listener had it crackling
     * within minutes. At a split of 500 Hz the lift is 1.6x, four decibels, and that already broke
     * up on bass notes; a modern master leaves no headroom for four decibels, so no cap on a lift
     * would have saved it. They then found it themselves: they suspected the quieter handset had a
     * worse speaker, remembered the two were at different distances and therefore at different
     * gains, moved them level, and heard the other one break up too. The defect followed the gain,
     * not the handset - and gains here multiply: distance compensation, this tilt, and the room
     * power normalisation are each bounded on their own and their product is not.
     *
     * A trim cannot do that to anybody. Every sample it produces is smaller than the one the same
     * arrangement produced before this existed, and that arrangement had already been listened to.
     * Clipping is shut off structurally rather than tuned away, which is why the cap below is now
     * about taste and not about damage.
     */
    @Test
    fun theTiltIsATrimSoNoSampleGrowsAndTheLowHalfNeverClips() {
        val field =
        SpatialField(
            mode = SpatialMode.SPLIT, layout = pair, splitAxis = SplitAxis.LOW_HIGH,
            separation = 1.0, otherHalfIds = setOf("right"), crossoverHz = SpatialField.LOWEST_CROSSOVER_HZ
        )
        val low = field.spectrumFor("left")
        val high = field.spectrumFor("right")

        assertTrue("the low half grew: $low", low.whole <= 1.0 && low.low <= 1.0)
        assertTrue("the high half grew: $high", high.whole <= 1.0 && abs(high.low) <= 1.0)
    }

    /**
     * And it stops, at a quarter. Past that the room is being turned down rather than tilted, and
     * what is on the other side of the split is a band the handset's own speaker cannot carry -
     * so the trim would be buying silence on one side and nothing on the other.
     */
    @Test
    fun theTiltStopsRatherThanFollowingTheSliderToTheBottom() {
        val bottom =
        SpatialField(
            mode = SpatialMode.SPLIT, layout = pair, splitAxis = SplitAxis.LOW_HIGH,
            separation = 1.0, otherHalfIds = setOf("right"), crossoverHz = SpatialField.LOWEST_CROSSOVER_HZ
        )
        val twoOctavesDown =
        SpatialField(
            mode = SpatialMode.SPLIT, layout = pair, splitAxis = SplitAxis.LOW_HIGH,
            separation = 1.0, otherHalfIds = setOf("right"), crossoverHz = SpatialField.DEFAULT_CROSSOVER_HZ / 4
        )

        assertEquals(0.25, twoOctavesDown.spectrumFor("right").whole, 1e-12)
        assertEquals(0.25, bottom.spectrumFor("right").whole, 1e-12)
    }

    /**
     * The knob at zero is the mix the room was already playing, at every split there is.
     *
     * The tilt rides on the separation knob rather than on the crossover alone. Without that, a
     * listener who had dragged the split low and then wound the separation back to nothing would
     * be left with one handset quietly twelve decibels down and no control on screen still saying
     * so - the crossover slider means nothing at all when nothing is being split by it.
     */
    @Test
    fun aKnobAtZeroLeavesBothHandsetsAloneHoweverLowTheSplitWasDragged() {
        val field = SpatialField(
            mode = SpatialMode.SPLIT, layout = pair, splitAxis = SplitAxis.LOW_HIGH,
            separation = 0.0, otherHalfIds = setOf("right"),
            crossoverHz = SpatialField.LOWEST_CROSSOVER_HZ
        )

        assertEquals(1.0, field.spectrumFor("right").whole, 1e-12)
        assertEquals(0.0, field.spectrumFor("right").low, 1e-12)
        assertEquals(1.0, field.spectrumFor("left").whole, 1e-12)
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

    /**
     * Dragging an icon further from the listener used to do nothing at all: every rule read the
     * direction and threw the radius away, so a room drawn with one handset across the table and one
     * at arm's length rendered as though both were the same distance off. It does something now.
     *
     * Read as a ratio between the two handsets rather than as an absolute, because that is the part
     * the drawing can actually say. Moving one of them twice as far doubles what it plays relative to
     * the other; how loud the room is overall is still the power normalisation's business.
     */
    @Test
    fun aHandsetDraggedFurtherAwayPlaysLouderThanTheOneThatDidNotMove() {
        val near = SpatialLayout(
            listOf(SpatialPosition("moved", 0.0, 1.0), SpatialPosition("still", 1.0, 0.0))
        )
        val far = SpatialLayout(
            listOf(SpatialPosition("moved", 0.0, 2.0), SpatialPosition("still", 1.0, 0.0))
        )

        val before = ratioOf(SpatialField(mode = SpatialMode.PAN, layout = near))
        val after = ratioOf(SpatialField(mode = SpatialMode.PAN, layout = far))
        assertEquals(2.0, after / before, 1e-9)
    }

    private fun ratioOf(field: SpatialField): Double =
        field.gainAt("moved", 0L).left / field.gainAt("still", 0L).left

    /**
     * The same room drawn larger is the same room. This is the whole reason the drawing is allowed
     * to carry no units: what every rule reads is a ratio, and a ratio does not know the scale.
     *
     * One handset is drawn close enough to the listener that the correction is capped, and that is
     * the point of these particular numbers. Without it the check is very nearly an identity: the
     * whole-room power normalisation divides out any factor common to every handset, so a distance
     * term measured in absolute units would satisfy it too. The cap is the one part of this that has
     * to be told the scale, so it is the part worth pointing a test at.
     */
    @Test
    fun drawingTheSameRoomLargerLeavesEveryHandsetPlayingWhatItWas() {
        val small = SpatialField(
            mode = SpatialMode.SPLIT,
            layout = SpatialLayout(
                listOf(SpatialPosition("a", -0.03, 0.04), SpatialPosition("b", 0.6, 0.8))
            )
        )
        val large = SpatialField(
            mode = SpatialMode.SPLIT,
            layout = SpatialLayout(
                listOf(SpatialPosition("a", -0.3, 0.4), SpatialPosition("b", 6.0, 8.0))
            )
        )

        for (peerId in listOf("a", "b")) {
            assertEquals(small.gainAt(peerId, 0L).left, large.gainAt(peerId, 0L).left, 1e-12)
            assertEquals(small.gainAt(peerId, 0L).right, large.gainAt(peerId, 0L).right, 1e-12)
        }
    }

    /**
     * The one thing the room moving away could be silently wrong about, and the whole reason it is
     * applied where it is: [SpatialField.gainAt] divides the room by its own power so that a room
     * is equally loud whatever the placement asks of it, and an attenuation applied before that
     * division comes straight back out of it. The wrong version is not quieter-by-less. It is a
     * knob that moves a slider and changes nothing at all.
     */
    @Test
    fun aRoomThatHasRetreatedIsQuieterThanTheRoomItRetreatedFrom() {
        val here = SpatialField(mode = SpatialMode.PAN, layout = pair, pan = 0.0)
        val away = here.copy(retreat = 1.0)

        for (peerId in listOf("left", "right")) {
            assertTrue(away.gainAt(peerId, 0L).left < here.gainAt(peerId, 0L).left)
            assertTrue(away.gainAt(peerId, 0L).right < here.gainAt(peerId, 0L).right)
        }
    }

    /**
     * Together, and that is the entire claim being made. The instant one handset is turned down
     * further than another this is a pan by loudness - the cue this room already had, and the one
     * whose presence makes it impossible to say afterwards what a gap in time did.
     */
    @Test
    fun retreatingLeavesEveryHandsetAsLoudAgainstTheOthersAsItWas() {
        val here = SpatialField(
            mode = SpatialMode.ROTATE, layout = triangle, periodNanos = 4_000_000_000L
        )
        val away = here.copy(retreat = 0.37)

        // A ratio rather than a difference: a listener places a source by how loud each handset is
        // against the others, and that is the quantity that must not have moved.
        val was = here.gainAt("left", 1_000_000_000L).left / here.gainAt("front", 1_000_000_000L).left
        val now = away.gainAt("left", 1_000_000_000L).left / away.gainAt("front", 1_000_000_000L).left
        assertEquals(was, now, 1e-12)
    }

    /** Off is off, to the last bit. A knob at rest that changes the sound is a knob nobody trusts. */
    @Test
    fun aRoomThatHasNotRetreatedPlaysExactlyWhatItAlwaysDid() {
        val field = SpatialField(mode = SpatialMode.PAN, layout = pair, pan = 0.4)

        assertEquals(0.0, field.retreat, 0.0)
        assertEquals(
            field.gainAt("left", 0L).left,
            field.copy(retreat = 0.0).gainAt("left", 0L).left,
            0.0
        )
    }

    /**
     * The far end is the two doublings of distance it is sold as, and not some other number.
     *
     * Twelve written out rather than read from [SpatialField.RETREAT_DECIBELS]: an expectation
     * taken from the module under test agrees with whatever that module does, including with a
     * decibel formula that halves or doubles it - which is the arithmetic here most likely to be
     * written for power when the quantity is an amplitude.
     */
    @Test
    fun theFarEndOfTheKnobIsTwelveDecibelsDown() {
        val here = SpatialField(mode = SpatialMode.PAN, layout = pair, pan = 0.0)
        val away = here.copy(retreat = 1.0)

        val quieterBy = 20.0 * log10(here.gainAt("left", 0L).left / away.gainAt("left", 0L).left)
        assertEquals(12.0, quieterBy, 1e-9)
    }

    /**
     * A source pointing away from every handset in the room falls into its own branch, which
     * shares the room out evenly by arithmetic of its own rather than by the line above it. A
     * retreat that missed that branch would put the room back to full loudness for as long as the
     * source pointed that way - heard as the music jumping forward, in the one arrangement that
     * is already a compromise.
     *
     * One handset at azimuth zero and the source half a turn round is that case exactly, and the
     * first assertion is what holds it there: the even branch is the only one that answers a
     * positive gain when nothing in the room faces the source.
     */
    @Test
    fun aSourceNoHandsetFacesRetreatsAlongWithTheRestOfTheRoom() {
        val alone = SpatialLayout(listOf(SpatialPosition("one", 0.0, 1.0)))
        val facingAway = SpatialField(
            mode = SpatialMode.ROTATE,
            layout = alone,
            periodNanos = 4_000_000_000L,
            epochHostNanos = 0L
        )
        val halfWayRound = 2_000_000_000L
        assertEquals(0.0, cos(facingAway.sourceAzimuthAt(halfWayRound)) + 1.0, 1e-12)

        val here = facingAway.gainAt("one", halfWayRound).left
        val away = facingAway.copy(retreat = 1.0).gainAt("one", halfWayRound).left
        assertTrue(here > 0.0)
        assertEquals(12.0, 20.0 * log10(here / away), 1e-9)
    }

    /**
     * **Why a reverberation reads as distance and a level does not.** Dragging the source to the
     * far end of its travel takes the direct sound down by the whole of the retreat and leaves the
     * room's own share exactly where it was - the walls did not move, and what comes off them does
     * not care that the source is further from the listener.
     *
     * Scale both by the same gain and the ratio between them never changes, which is the failure
     * the plain retreat has on its own: everything gets quieter together and a listener hears
     * somebody turning a volume knob.
     */
    @Test
    fun theRoomStaysWhereItIsWhenTheSourceMovesAway() {
        val here = SpatialField(mode = SpatialMode.PAN, layout = pair, pan = 0.0, reverb = 1.0)
        val away = here.copy(retreat = 1.0)

        assertEquals(here.roomGainAt("left", 0L).left, away.roomGainAt("left", 0L).left, 1e-12)
        val direct = away.gainAt("left", 0L).left / here.gainAt("left", 0L).left
        assertEquals(
            Math.pow(10.0, -SpatialField.RETREAT_DECIBELS / 20.0), direct, 1e-12
        )
    }

    /** The placement is still the placement: a handset the rule silenced plays no room either. */
    @Test
    fun aHandsetTheSourceHasLeftBehindCarriesNoneOfTheRoom() {
        val hardRight = SpatialField(mode = SpatialMode.PAN, layout = pair, pan = 1.0, reverb = 1.0)

        assertEquals(0.0, hardRight.roomGainAt("left", 0L).left, 1e-12)
        assertTrue(hardRight.roomGainAt("right", 0L).right > 0.5)
    }

    private fun roomPower(field: SpatialField): Double = field.layout.peerIds.sumOf {
        val gain = field.gainAt(it, 0L)
        (gain.left * gain.left + gain.right * gain.right) / 2.0
    }
}
