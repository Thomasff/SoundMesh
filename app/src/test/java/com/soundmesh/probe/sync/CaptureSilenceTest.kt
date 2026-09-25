package com.soundmesh.probe.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Telling a room that is playing nothing apart from a room that is playing something quiet.
 *
 * The failure it exists for is invisible from every other direction: the session says PLAYING, the
 * counters are healthy, the drift is fine, and every handset in the room is silent.
 */
class CaptureSilenceTest {
    private val chunk = SyncRenderer.FRAMES_PER_CHUNK * SyncRenderer.CHANNELS * 2

    private fun silence() = ByteArray(chunk)

    private fun music() = ByteArray(chunk) { index -> if (index % 7 == 0) 3 else 0 }

    @Before
    fun open() = CaptureSilence.watch()

    @After
    fun close() = CaptureSilence.forget()

    @Test
    fun `a capture that is handing over audio is not silent`() {
        repeat(50) { CaptureSilence.sawChunk(music()) }

        assertEquals(0L, CaptureSilence.silentNanos())
    }

    /**
     * One sample away from silence is not silence.
     *
     * Real audio nobody can hear still has the bottom bit moving. What this is looking for is a
     * path that has stopped producing, and that produces zeros exactly.
     */
    @Test
    fun `a chunk with one sample in it counts as audio`() {
        val nearly = silence()
        nearly[nearly.size - 1] = 1

        CaptureSilence.sawChunk(silence())
        Thread.sleep(20)
        CaptureSilence.sawChunk(nearly)

        assertEquals(0L, CaptureSilence.silentNanos())
    }

    @Test
    fun `silence is measured from the last audio rather than from the first zero`() {
        CaptureSilence.sawChunk(music())
        Thread.sleep(60)
        CaptureSilence.sawChunk(silence())
        Thread.sleep(60)
        CaptureSilence.sawChunk(silence())

        val silent = CaptureSilence.silentNanos()

        assertTrue("$silent", silent >= 100_000_000L)
    }

    /** Audio returning clears it, because what a screen shows has to be about now. */
    @Test
    fun `audio coming back ends the silence`() {
        CaptureSilence.sawChunk(music())
        Thread.sleep(40)
        CaptureSilence.sawChunk(silence())
        Thread.sleep(40)
        CaptureSilence.sawChunk(silence())
        assertTrue(CaptureSilence.silentNanos() > 0L)

        CaptureSilence.sawChunk(music())

        assertEquals(0L, CaptureSilence.silentNanos())
    }

    /** A session that is not capturing reads zero rather than however long ago the last one was. */
    @Test
    fun `a capture that is not open reads nothing`() {
        CaptureSilence.sawChunk(silence())
        Thread.sleep(40)
        CaptureSilence.sawChunk(silence())

        CaptureSilence.forget()

        CaptureSilence.sawChunk(silence())
        assertEquals(0L, CaptureSilence.silentNanos())
    }

    /**
     * A gap between two songs is not a fault, and what reads this record is looking for a fault.
     * The threshold is the whole of what tells them apart, so nothing under it is filed at all.
     */
    @Test
    fun `a gap shorter than a spell is not filed`() {
        var now = 1_000L
        val filed = ArrayList<Pair<Long, Boolean>>()
        CaptureSilence.watch({ now }) { nanos, recovered -> filed += nanos to recovered }

        CaptureSilence.sawChunk(music())
        now += CaptureSilence.SPELL_NANOS - 1
        CaptureSilence.sawChunk(silence())
        CaptureSilence.sawChunk(music())

        assertEquals(emptyList<Pair<Long, Boolean>>(), filed)
    }

    /**
     * The record outlives the screen, which is the point of it.
     *
     * A listener who is playing music is not looking at this app, and the one on 09-12 found the
     * room silent, nudged the volume and carried on - the red line was on a screen in a pocket.
     */
    @Test
    fun `a spell is filed once, when the audio comes back`() {
        var now = 1_000L
        val filed = ArrayList<Pair<Long, Boolean>>()
        CaptureSilence.watch({ now }) { nanos, recovered -> filed += nanos to recovered }

        CaptureSilence.sawChunk(music())
        repeat(6) {
            now += CaptureSilence.SPELL_NANOS / 2
            CaptureSilence.sawChunk(silence())
        }
        CaptureSilence.sawChunk(music())

        assertEquals(1, filed.size)
        assertEquals(3 * CaptureSilence.SPELL_NANOS, filed[0].first)
        assertTrue("the audio came back", filed[0].second)
    }

    /**
     * A capture that is still silent when it closes is the more interesting half of the two.
     *
     * It says the path never came back on its own, and a spell only filed on recovery would file
     * nothing at all in exactly that case.
     */
    @Test
    fun `a capture that closes while still silent files the spell anyway`() {
        var now = 1_000L
        val filed = ArrayList<Pair<Long, Boolean>>()
        CaptureSilence.watch({ now }) { nanos, recovered -> filed += nanos to recovered }

        CaptureSilence.sawChunk(music())
        now += 9 * CaptureSilence.SPELL_NANOS
        CaptureSilence.sawChunk(silence())
        CaptureSilence.forget()

        assertEquals(1, filed.size)
        assertEquals(9 * CaptureSilence.SPELL_NANOS, filed[0].first)
        assertFalse("it never came back", filed[0].second)
    }

    /** Closing twice is closing once: the second one has nothing left to say. */
    @Test
    fun `a spell is not filed twice by closing twice`() {
        var now = 1_000L
        val filed = ArrayList<Pair<Long, Boolean>>()
        CaptureSilence.watch({ now }) { nanos, recovered -> filed += nanos to recovered }

        CaptureSilence.sawChunk(music())
        now += 9 * CaptureSilence.SPELL_NANOS
        CaptureSilence.sawChunk(silence())
        CaptureSilence.forget()
        CaptureSilence.forget()

        assertEquals(1, filed.size)
    }

    /**
     * A capture that has never made a sound is not a capture that stopped making one.
     *
     * 09-13: the capture was opened to look at the room drawing and no music was ever started.
     * Eighty-six seconds of nothing were filed as a fault, on a handset behaving perfectly.
     */
    @Test
    fun `a capture that has never produced audio is not silent`() {
        var now = 1_000L
        val filed = ArrayList<Pair<Long, Boolean>>()
        CaptureSilence.watch({ now }) { nanos, recovered -> filed += nanos to recovered }

        repeat(20) {
            now += CaptureSilence.SPELL_NANOS
            CaptureSilence.sawChunk(silence())
        }

        assertEquals(0L, CaptureSilence.silentNanos())
        assertEquals(emptyList<Pair<Long, Boolean>>(), filed)
    }

    /** And once it has made one, it counts from then on, which is the case this exists for. */
    @Test
    fun `the first sound is what starts it watching`() {
        var now = 1_000L
        val filed = ArrayList<Pair<Long, Boolean>>()
        CaptureSilence.watch({ now }) { nanos, recovered -> filed += nanos to recovered }

        now += 30 * CaptureSilence.SPELL_NANOS
        CaptureSilence.sawChunk(silence())
        CaptureSilence.sawChunk(music())
        now += 2 * CaptureSilence.SPELL_NANOS
        CaptureSilence.sawChunk(silence())
        CaptureSilence.sawChunk(music())

        assertEquals(1, filed.size)
        assertEquals(2 * CaptureSilence.SPELL_NANOS, filed[0].first)
    }

    /**
     * The half that 2026-09-14 showed was missing.
     *
     * Twice that evening the room went silent with the host on battery, and both times the only
     * instrument that spoke was this one - after the fact, on the way back, saying how long it had
     * been. That is the wrong end. What the fault needed recorded was the handset's state **while
     * it was failing**, and a spell that never recovers would have said nothing at all until the
     * session was stopped.
     */
    @Test
    fun `a spell says so when it starts, not only when it is over`() {
        var clock = 0L
        val said = mutableListOf<String>()
        CaptureSilence.watch(
            now = { clock },
            onSpell = { _, recovered -> said += if (recovered) "back" else "gone" },
            onBegan = { said += "begins" }
        )

        CaptureSilence.sawChunk(music())
        clock += CaptureSilence.SPELL_NANOS + 1
        CaptureSilence.sawChunk(silence())
        CaptureSilence.sawChunk(music())

        assertEquals(listOf("begins", "back"), said)
    }

    /** Once per spell, however many chunks of silence go by inside it. */
    @Test
    fun `the start is announced once, not on every silent chunk`() {
        var clock = 0L
        var begins = 0
        CaptureSilence.watch(now = { clock }, onBegan = { begins++ })

        CaptureSilence.sawChunk(music())
        clock += CaptureSilence.SPELL_NANOS + 1
        repeat(20) { CaptureSilence.sawChunk(silence()) }

        assertEquals(1, begins)
    }

    /** Not before the spell is long enough, or a gap between two tracks files a fault. */
    @Test
    fun `a short gap never announces anything`() {
        var clock = 0L
        var begins = 0
        CaptureSilence.watch(now = { clock }, onBegan = { begins++ })

        CaptureSilence.sawChunk(music())
        clock += CaptureSilence.SPELL_NANOS - 1
        CaptureSilence.sawChunk(silence())

        assertEquals(0, begins)
    }

    /**
     * The 09-13 case, on this end too: a capture opened to look at the room drawing with no music
     * ever played is not a fault, and must not file one.
     */
    @Test
    fun `a capture that has never heard anything never says it began`() {
        var clock = 0L
        var begins = 0
        CaptureSilence.watch(now = { clock }, onBegan = { begins++ })

        clock += CaptureSilence.SPELL_NANOS * 10
        repeat(20) { CaptureSilence.sawChunk(silence()) }

        assertEquals(0, begins)
    }

    /**
     * The repair does not wait for a spell. From 0.7 s of silence it is asked on every silent
     * chunk - the cooldown is its own to keep - and told which chunk is the first of this silence.
     */
    @Test
    fun `from the quiet mark every silent chunk asks for the repair`() {
        var clock = 0L
        val asked = mutableListOf<Pair<Long, Boolean>>()
        CaptureSilence.watch(now = { clock }, onQuiet = { silent, first -> asked += silent to first })

        CaptureSilence.sawChunk(music())
        clock += CaptureSilence.QUIET_NANOS - 1
        CaptureSilence.sawChunk(silence())
        assertEquals(emptyList<Pair<Long, Boolean>>(), asked)

        clock += 1
        CaptureSilence.sawChunk(silence())
        clock += 20_000_000L
        CaptureSilence.sawChunk(silence())

        assertEquals(
            listOf(CaptureSilence.QUIET_NANOS to true, CaptureSilence.QUIET_NANOS + 20_000_000L to false),
            asked
        )
    }

    /** Sound coming back ends it, and the next silence is a first again. */
    @Test
    fun `a new silence after sound starts again from its first chunk`() {
        var clock = 0L
        val firsts = mutableListOf<Boolean>()
        CaptureSilence.watch(now = { clock }, onQuiet = { _, first -> firsts += first })

        CaptureSilence.sawChunk(music())
        clock += CaptureSilence.QUIET_NANOS
        CaptureSilence.sawChunk(silence())
        CaptureSilence.sawChunk(music())
        clock += CaptureSilence.QUIET_NANOS
        CaptureSilence.sawChunk(silence())

        assertEquals(listOf(true, true), firsts)
    }

    /** The 09-13 case once more: nothing is repaired on a capture that never made a sound. */
    @Test
    fun `a capture that has never heard anything never asks for the repair`() {
        var clock = 0L
        var asked = 0
        CaptureSilence.watch(now = { clock }, onQuiet = { _, _ -> asked++ })

        clock += CaptureSilence.SPELL_NANOS * 10
        repeat(20) { CaptureSilence.sawChunk(silence()) }

        assertEquals(0, asked)
    }

    /** The one that matters most: it said so at the time even though it never came back. */
    @Test
    fun `a spell that never recovers still announced its start`() {
        var clock = 0L
        val said = mutableListOf<String>()
        CaptureSilence.watch(
            now = { clock },
            onSpell = { _, recovered -> said += if (recovered) "back" else "gone" },
            onBegan = { said += "begins" }
        )

        CaptureSilence.sawChunk(music())
        clock += CaptureSilence.SPELL_NANOS + 1
        CaptureSilence.sawChunk(silence())
        CaptureSilence.forget()

        assertEquals(listOf("begins", "gone"), said)
    }
}
