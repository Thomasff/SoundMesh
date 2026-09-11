package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialFieldCodecTest {
    private val field = SpatialField(
        mode = SpatialMode.ROTATE,
        layout = SpatialLayout(
            listOf(
                SpatialPosition("a1b2c3d4e5f60718", -0.7, 0.25),
                SpatialPosition("0918273645abcdef", 0.7, 0.25)
            )
        ),
        periodNanos = 5_500_000_000L,
        pan = -0.25,
        epochHostNanos = 1_234_567_890_123L
    )

    /** Compared by the gains they produce, which is the only thing either side uses them for. */
    @Test
    fun aRuleSurvivesTheRoundTrip() {
        val back = SpatialFieldCodec.decode(SpatialFieldCodec.encode(field))

        assertEquals(field.mode, back.mode)
        assertEquals(field.periodNanos, back.periodNanos)
        assertEquals(field.pan, back.pan, 0.0)
        assertEquals(field.epochHostNanos, back.epochHostNanos)
        assertEquals(field.layout.peerIds, back.layout.peerIds)
        for (peerId in field.layout.peerIds) {
            val at = 3_300_000_000L
            assertEquals(field.gainAt(peerId, at).left, back.gainAt(peerId, at).left, 0.0)
            assertEquals(field.gainAt(peerId, at).right, back.gainAt(peerId, at).right, 0.0)
        }
    }

    /** Windows line endings reach this from a file as readily as from a socket. */
    @Test
    fun carriageReturnsDoNotChangeWhatWasSent() {
        val back = SpatialFieldCodec.decode(SpatialFieldCodec.encode(field).replace("\n", "\r\n"))

        assertEquals(field.layout.peerIds, back.layout.peerIds)
    }

    /**
     * The failure this refusal exists for: a message cut short leaves a room with fewer handsets
     * in it, which is itself a perfectly valid room and would be rendered at full confidence.
     */
    @Test(expected = IllegalArgumentException::class)
    fun aRuleMissingHandsetsItPromisedIsRefused() {
        val whole = SpatialFieldCodec.encode(field).split("\n")

        SpatialFieldCodec.decode(whole.dropLast(1).joinToString("\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRuleFromAnotherVersionIsRefused() {
        SpatialFieldCodec.decode(
            SpatialFieldCodec.encode(field).replaceFirst(
                "${SpatialFieldCodec.MAGIC} ${SpatialFieldCodec.VERSION}",
                "${SpatialFieldCodec.MAGIC} 99"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun somethingThatIsNotARuleIsRefused() {
        SpatialFieldCodec.decode("soundmesh-alignment 3 O40 a1b2c3d4e5f60718 0 0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun aModeThisBuildDoesNotHaveIsRefused() {
        SpatialFieldCodec.decode(SpatialFieldCodec.encode(field).replaceFirst("ROTATE", "SPIRAL"))
    }

    /**
     * A drawing the screen could not have produced fails the same way a garbled message does,
     * rather than one of the two quietly becoming a room.
     */
    @Test(expected = IllegalArgumentException::class)
    fun aHandsetSittingOnTheListenerIsRefusedOnArrivalToo() {
        SpatialFieldCodec.decode(
            "${SpatialFieldCodec.MAGIC} ${SpatialFieldCodec.VERSION} PAN 1000 0.0 0 0.0 1\na 0.0 0.0 MIDDLE"
        )
    }

    /** A name with a space in it would be read back as a name and one coordinate too many. */
    @Test(expected = IllegalArgumentException::class)
    fun aHandsetNameThatWouldSplitIntoTwoFieldsIsRefusedBeforeItIsSent() {
        SpatialFieldCodec.encode(
            SpatialField(
                mode = SpatialMode.SPLIT,
                layout = SpatialLayout(listOf(SpatialPosition("a b", 1.0, 0.0)))
            )
        )
    }

    private val separated = SpatialField(
        mode = SpatialMode.SPLIT,
        layout = SpatialLayout(
            listOf(
                SpatialPosition("a1b2c3d4e5f60718", -0.7, 0.25),
                SpatialPosition("0918273645abcdef", 0.7, 0.25)
            )
        ),
        separation = 0.7,
        otherHalfIds = setOf("0918273645abcdef")
    )

    /**
     * The knob and the parts travel with the drawing rather than in a message of their own, for the
     * reason the whole rule travels together: a handset holding this drawing and the previous
     * assignment would render a room nobody drew and have no way to notice.
     */
    @Test
    fun theKnobAndThePartsSurviveTheRoundTrip() {
        val back = SpatialFieldCodec.decode(SpatialFieldCodec.encode(separated))

        assertEquals(separated.separation, back.separation, 0.0)
        assertEquals(separated.otherHalfIds, back.otherHalfIds)
        for (peerId in separated.layout.peerIds) {
            assertEquals(separated.foldFor(peerId), back.foldFor(peerId), 0.0)
        }
    }

    /** A part this build cannot render is a room it cannot draw, and is refused like an unknown mode. */
    @Test(expected = IllegalArgumentException::class)
    fun aPartThisBuildDoesNotHaveIsRefused() {
        SpatialFieldCodec.decode(SpatialFieldCodec.encode(separated).replaceFirst("MIDDLE", "CEILING"))
    }

    /**
     * A handset line from the older format carries no part at all. Refused rather than defaulted to
     * the middle: the two builds would then disagree about the room while both believed they agreed,
     * which is the one failure the version number exists to prevent.
     */
    @Test(expected = IllegalArgumentException::class)
    fun aHandsetLineWithNoPartOnItIsRefused() {
        SpatialFieldCodec.decode(SpatialFieldCodec.encode(separated).replaceFirst(" MIDDLE", ""))
    }

    private val byFrequency = SpatialField(
        mode = SpatialMode.SPLIT,
        layout = SpatialLayout(
            listOf(
                SpatialPosition("a1b2c3d4e5f60718", -0.7, 0.25),
                SpatialPosition("0918273645abcdef", 0.7, 0.25)
            )
        ),
        separation = 0.7,
        splitAxis = SplitAxis.LOW_HIGH,
        crossoverHz = 1234.0,
        otherHalfIds = setOf("0918273645abcdef")
    )

    @Test
    fun theAxisAndTheCrossoverSurviveTheRoundTrip() {
        val back = SpatialFieldCodec.decode(SpatialFieldCodec.encode(byFrequency))

        assertEquals(byFrequency.splitAxis, back.splitAxis)
        assertEquals(byFrequency.crossoverHz, back.crossoverHz, 0.0)
        assertEquals(byFrequency.otherHalfIds, back.otherHalfIds)
        for (peerId in byFrequency.layout.peerIds) {
            assertEquals(byFrequency.spectrumFor(peerId), back.spectrumFor(peerId))
        }
    }

    /**
     * The instant a rule starts applying is the whole of what keeps two handsets from swapping
     * halves at different moments, so a build that dropped it in transit would leave every
     * receiver picking its own moment while believing it agreed.
     */
    @Test
    fun theInstantTheRuleStartsApplyingSurvivesTheRoundTrip() {
        val field = SpatialField(
            SpatialMode.PAN,
            SpatialLayout(listOf(SpatialPosition("a", 1.0, 0.0), SpatialPosition("b", -1.0, 0.0))),
            separation = 1.0,
            splitAxis = SplitAxis.LOW_HIGH,
            crossoverHz = 1200.0,
            otherHalfIds = setOf("b"),
            effectiveAtHostNanos = 1_234_567_890_123L
        )

        val back = SpatialFieldCodec.decode(SpatialFieldCodec.encode(field))

        assertEquals(1_234_567_890_123L, back.effectiveAtHostNanos)
        // Beside the rest of the header, because a field appended to a line is exactly the change
        // that shifts every field after it by one and reads the count as a crossover.
        assertEquals(field.crossoverHz, back.crossoverHz, 0.0)
        assertEquals(field.otherHalfIds, back.otherHalfIds)
        assertEquals(field.layout.peerIds, back.layout.peerIds)
    }

    /**
     * A part names its own axis on the wire, so a line reads as what it is out of a log and a
     * message whose header and handsets disagree is caught rather than read. Nothing on the sending
     * side can produce one; a truncated or spliced message can.
     */
    @Test
    fun aPartFromTheOtherAxisIsRefused() {
        // The handset line, pinned by the newline after it: the header carries LOW_HIGH, and a
        // replacement that hit that instead would refuse this message for an unknown axis while
        // reading as though it had refused a crossed part.
        val crossed = SpatialFieldCodec.encode(byFrequency).replaceFirst(" LOW\n", " MIDDLE\n")

        val thrown = runCatching { SpatialFieldCodec.decode(crossed) }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    /**
     * How large the room is crosses the wire, because the half-second of delay it decides is
     * applied on the handset and not here.
     */
    @Test
    fun carriesHowLargeTheRoomIs() {
        val measured = field.copy(metresPerUnit = 2.5)

        val back = SpatialFieldCodec.decode(SpatialFieldCodec.encode(measured))

        assertEquals(2.5, back.metresPerUnit, 0.0)
        for (peerId in measured.layout.peerIds) {
            assertEquals(
                measured.arrivalDelayNanosFor(peerId),
                back.arrivalDelayNanosFor(peerId)
            )
        }
    }

    /** And a room nobody measured the listener in says so rather than saying nothing. */
    @Test
    fun carriesTheAbsenceOfAScaleToo() {
        val back = SpatialFieldCodec.decode(SpatialFieldCodec.encode(field))

        assertEquals(0.0, back.metresPerUnit, 0.0)
    }

    /** And how much a handset keeps when the source faces away, which is the newest header field. */
    @Test
    fun carriesHowMuchAHandsetKeepsWhenTheSourceFacesAway() {
        val wide = field.copy(envelopment = 0.3)

        val back = SpatialFieldCodec.decode(SpatialFieldCodec.encode(wide))

        assertEquals(0.3, back.envelopment, 0.0)
        for (peerId in wide.layout.peerIds) {
            val at = 3_300_000_000L
            assertEquals(wide.gainAt(peerId, at).left, back.gainAt(peerId, at).left, 0.0)
        }
    }
}
