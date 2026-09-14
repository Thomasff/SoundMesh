package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The third split as a room sees it: the rule, the wire, and a chunk going through the shaper.
 *
 * [HarmonicPercussiveTest] judges whether the separation separates. What is judged here is whether
 * a room asking for it gets what it asked for - which is a different question and breaks in
 * different places, most of them at a boundary rather than in the arithmetic.
 */
class HeldStruckAxisTest {
    private val sampleRate = 48_000
    private val window = 256

    /**
     * Late enough that the run of frames the medians look back over has filled.
     *
     * Before this the separation is answering from a history that is mostly the silence it
     * started from, which is not a fault - it is what a thing that has to listen first does.
     */
    private val settled = window * 8
    private val framesPerChunk = 256

    /** One handset on its own, so the placement gain is one and what is left is the split. */
    private fun solo(separation: Double, struck: Set<String> = emptySet()) = SpatialField(
        mode = SpatialMode.SPLIT,
        layout = SpatialLayout(listOf(SpatialPosition("solo", 0.0, 1.0))),
        separation = separation,
        splitAxis = SplitAxis.HELD_STRUCK,
        otherHalfIds = struck
    )

    private fun sampleAt(pcm: ByteArray, at: Int): Int =
        ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()

    private fun write(pcm: ByteArray, at: Int, value: Int) {
        pcm[at] = (value and 0xFF).toByte()
        pcm[at + 1] = (value shr 8).toByte()
    }

    /** A held tone with a click on top of it, so both halves have something to be. */
    private fun mixed(frames: Int): ByteArray {
        val pcm = ByteArray(frames * 4)
        for (frame in 0 until frames) {
            // High enough that a window of this length holds several cycles of it. A tone the
            // window cannot fit is not a held sound as far as any of this is concerned, which
            // is the same statement as the bin spacing being the thing a longer window buys.
            val tone = (6000.0 * sin(2.0 * PI * 3000.0 * frame / sampleRate)).toInt()
            val click = if (frame % 600 == 0) 8000 else 0
            write(pcm, frame * 4, tone + click)
            write(pcm, frame * 4 + 2, tone - click)
        }
        return pcm
    }

    /** Runs [sound] through the shaper a chunk at a time, the way the renderer does. */
    private fun through(sound: ByteArray, field: SpatialField, halves: Separation): ByteArray {
        val out = ByteArray(sound.size)
        val chunkBytes = framesPerChunk * 4
        var at = 0
        while (at < sound.size) {
            val chunk = sound.copyOfRange(at, minOf(at + chunkBytes, sound.size))
            val shaped = SpatialShaper.shape(
                chunk, field, "solo", at.toLong(), sampleRate, halves = halves
            )
            shaped.copyInto(out, at)
            at += chunkBytes
        }
        return out
    }

    @Test
    fun everyOtherAxisHandsBackTheWholeMix() {
        for (axis in listOf(SplitAxis.MIDDLE_SIDES, SplitAxis.LOW_HIGH)) {
            val field = solo(separation = 1.0).copy(splitAxis = axis)

            assertEquals(HalvesMix(1.0, 1.0), field.halvesFor("solo"))
        }
    }

    @Test
    fun withTheKnobAtNothingEveryHandsetKeepsBothHalves() {
        val field = solo(separation = 0.0, struck = setOf("solo"))

        assertEquals(HalvesMix(1.0, 1.0), field.halvesFor("solo"))
    }

    @Test
    fun allTheWayOverEachHandsetKeepsOneHalfAndLetsTheOtherGo() {
        val field = SpatialField(
            mode = SpatialMode.SPLIT,
            layout = SpatialLayout(
                listOf(SpatialPosition("held", -1.0, 0.0), SpatialPosition("struck", 1.0, 0.0))
            ),
            separation = 1.0,
            splitAxis = SplitAxis.HELD_STRUCK,
            otherHalfIds = setOf("struck")
        )

        assertEquals(HalvesMix(1.0, 0.0), field.halvesFor("held"))
        assertEquals(HalvesMix(0.0, 1.0), field.halvesFor("struck"))
    }

    @Test
    fun aRoomSplitThisWaySurvivesTheWire() {
        val field = solo(separation = 0.6, struck = setOf("solo"))

        val back = SpatialFieldCodec.decode(SpatialFieldCodec.encode(field))

        assertEquals(SplitAxis.HELD_STRUCK, back.splitAxis)
        assertEquals(setOf("solo"), back.otherHalfIds)
        assertEquals(HalvesMix(0.4, 1.0), back.halvesFor("solo"))
    }

    /** A spliced message naming a half from another axis is refused rather than read as a room. */
    @Test
    fun aHalfFromAnotherAxisIsNotARoomOfThisOne() {
        val spliced = SpatialFieldCodec.encode(solo(separation = 0.6)).replace(" HELD", " LOW")

        assertThrows(IllegalArgumentException::class.java) { SpatialFieldCodec.decode(spliced) }
    }

    /** Missing and switched off sound exactly alike, so the missing one is refused. */
    @Test
    fun theSplitNeedsSomewhereToKeepWhatItHasHeard() {
        assertThrows(IllegalArgumentException::class.java) {
            SpatialShaper.shape(mixed(framesPerChunk), solo(1.0), "solo", 0L, sampleRate)
        }
    }

    /**
     * With the knob at nothing the room plays what it was sent - one window late, and nothing else.
     *
     * The end-to-end form of the property both halves are built around. A separation that leaks
     * anywhere, a window that does not add back up, a share that does not reach one: all of them
     * show up here as a sound that is not the song.
     */
    @Test
    fun withTheKnobAtNothingTheSoundIsTheMixItArrivedAs() {
        val sound = mixed(framesPerChunk * 24)
        val halves = Separation(window)

        val out = through(sound, solo(separation = 0.0), halves)

        for (frame in halves.held until sound.size / 4) {
            val was = sampleAt(sound, (frame - halves.held) * 4)
            assertTrue(
                "left of frame $frame came back as ${sampleAt(out, frame * 4)}, not $was",
                abs(sampleAt(out, frame * 4) - was) <= 1
            )
        }
    }

    /** The two handsets between them play the mix, which is what lets a listener wind it back. */
    @Test
    fun theTwoHalvesAddBackUpToWhatWasSent() {
        val sound = mixed(framesPerChunk * 24)

        val held = through(sound, solo(separation = 1.0), Separation(window))
        val struck = through(sound, solo(separation = 1.0, struck = setOf("solo")), Separation(window))

        val lag = Separation(window).held
        for (frame in lag until sound.size / 4) {
            val was = sampleAt(sound, (frame - lag) * 4)
            val both = sampleAt(held, frame * 4) + sampleAt(struck, frame * 4)
            assertTrue("frame $frame came back as $both, not $was", abs(both - was) <= 2)
        }
        // Which handset got which half. Adding back up is true however the two are handed out, so
        // on its own it would pass with the names swapped over - and a room whose two buttons mean
        // the opposite of what they say is the fault a listener would report as "it does nothing".
        // Mostly a held tone went in, so most of it has to have come out of the held one.
        assertTrue(
            "the struck half carried ${loudnessOf(struck, settled)} against ${loudnessOf(held, settled)}",
            loudnessOf(struck, settled) * 10.0 < loudnessOf(held, settled)
        )
        assertTrue("the struck half carried nothing at all", loudnessOf(struck, settled) > 0.0)
    }

    /** How much sound is in [pcm] from frame [after] on, left channel, as a sum of squares. */
    private fun loudnessOf(pcm: ByteArray, after: Int): Double =
        (after until pcm.size / 4).sumOf {
            val sample = sampleAt(pcm, it * 4).toDouble()
            sample * sample
        }

    /**
     * A room that stopped asking for this split leaves nothing behind to be heard on the way back.
     *
     * This is the one thing in the audio path that is not kept warm while unused, so what it holds
     * when it is switched off would otherwise be played back whenever it is switched on again -
     * a fragment of a different part of the song, minutes later.
     */
    @Test
    fun switchingAwayLeavesNothingToBeHeardOnTheWayBack() {
        val halves = Separation(window)
        through(mixed(framesPerChunk * 24), solo(separation = 0.0), halves)

        halves.forget()
        val after = through(ByteArray(framesPerChunk * 4), solo(separation = 0.0), halves)

        for (at in after.indices) {
            assertEquals("byte $at", 0, after[at].toInt())
        }
    }

    @Test
    fun saysHowLongTheRoomIsWaiting() {
        assertEquals(window - 1, Separation(window).held)
        assertEquals(Separation.WINDOW - 1, Separation().held)
    }
}
