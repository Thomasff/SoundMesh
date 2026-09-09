package com.soundmesh.core

import org.junit.Assert.assertEquals
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
            "${SpatialFieldCodec.MAGIC} ${SpatialFieldCodec.VERSION} PAN 1000 0.0 0 1\na 0.0 0.0"
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
}
