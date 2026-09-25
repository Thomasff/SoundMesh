package com.soundmesh.probe.sync

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listener's own repair, and the two ways it must refuse to act.
 *
 * What is being rehearsed is a sequence rather than an end state: the whole of this is that the
 * stream is briefly not zero and then is zero again, so a test that only checked where it ended up
 * would pass against an implementation that did nothing at all.
 */
class SilenceNudgeTest {
    private val media = AudioManager.STREAM_MUSIC
    private val second = 1_000_000_000L

    @Test
    fun `off zero, held, and back - in that order`() {
        val streams = FakeStreams(mutableMapOf(media to 0))
        val nudge = SilenceNudge(streams)
        val held = ArrayList<List<Pair<Int, Int>>>()

        val outcome = nudge.push { held += streams.written.toList() }

        assertEquals(NudgeOutcome.PUSHED, outcome)
        assertEquals(listOf(media to 1, media to 0), streams.written)
        // The hold has to happen while the stream is off zero. A hold after both writes is a
        // quarter of a second of nothing, and the handset never sees a stream asking for audio.
        assertEquals(listOf(listOf(media to 1)), held)
    }

    /** Asked on every chunk of silence: yes once, then no until five seconds have gone by. */
    @Test
    fun `an attempt inside the cooldown is not claimed`() {
        val nudge = SilenceNudge(FakeStreams(mutableMapOf(media to 0)))

        assertTrue(nudge.claim(100 * second))
        assertFalse(nudge.claim(100 * second + 20_000_000L))
        assertFalse(nudge.claim(105 * second - 1))
        assertTrue(nudge.claim(105 * second))
    }

    /** A volume somebody chose is theirs. This only ever acts on the zero the app itself wrote. */
    @Test
    fun `a stream that is not at zero is not touched`() {
        val streams = FakeStreams(mutableMapOf(media to 3))
        val nudge = SilenceNudge(streams)

        assertEquals(NudgeOutcome.NOT_SILENCED, nudge.push {})
        assertEquals(emptyList<Pair<Int, Int>>(), streams.written)
    }

    /**
     * A set that is accepted and moves nothing has been seen on this project's own handsets.
     *
     * It is reported as its own answer rather than as a push, and it still costs the cooldown: a
     * handset that will not move its media volume will not move it in five seconds either, and
     * retrying on every chunk would be a repair that never works and never stops.
     */
    @Test
    fun `a set that changes nothing says so, and the stream is still put back`() {
        val streams = FakeStreams(mutableMapOf(media to 0), deaf = setOf(media))
        val nudge = SilenceNudge(streams)

        assertTrue(nudge.claim(100 * second))
        assertEquals(NudgeOutcome.WOULD_NOT_MOVE, nudge.push {})
        assertEquals(listOf(media to 1, media to 0), streams.written)
        assertFalse(nudge.claim(102 * second))
    }

    /** Levels that move, writes kept in order, and a stream that accepts a value and ignores it. */
    private class FakeStreams(
        val levels: MutableMap<Int, Int>,
        val deaf: Set<Int> = emptySet()
    ) : StreamVolumes {
        val written = ArrayList<Pair<Int, Int>>()

        override fun level(stream: Int): Int = levels[stream] ?: 0
        override fun max(stream: Int): Int = 15
        override fun set(stream: Int, index: Int) {
            written += stream to index
            if (stream !in deaf) levels[stream] = index
        }
    }
}
