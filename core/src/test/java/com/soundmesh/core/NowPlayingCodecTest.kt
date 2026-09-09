package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingCodecTest {
    @Test
    fun aNameSurvivesTheRoundTrip() {
        assertEquals("倔强.mp3", NowPlayingCodec.decode(NowPlayingCodec.encode("倔强.mp3")))
    }

    /** Song names have spaces in them far more often than not. */
    @Test
    fun aNameWithSpacesIsNotCutShort() {
        val name = "Better When I'm Dancin' - Meghan Trainor.mp3"
        assertEquals(name, NowPlayingCodec.decode(NowPlayingCodec.encode(name)))
    }

    /**
     * The reason this shares a channel with the rules at all: a reader tells the two apart by the
     * first word, and a build that has never heard of this one still recognises it as not a rule.
     */
    @Test
    fun aRuleIsNotMistakenForASongAndTheOtherWayRound() {
        val rule = SpatialFieldCodec.encode(
            SpatialField(SpatialMode.ROTATE, SpatialLayout(listOf(SpatialPosition("a", 1.0, 0.0))))
        )
        assertFalse(NowPlayingCodec.looksLikeOne(rule))
        assertTrue(NowPlayingCodec.looksLikeOne(NowPlayingCodec.encode("倔强.mp3")))
        assertTrue(runCatching { SpatialFieldCodec.decode(NowPlayingCodec.encode("倔强.mp3")) }.isFailure)
    }

    /**
     * A newline would be indistinguishable from a truncated message on a channel whose other
     * format is line based, and no file name this is built from can contain one.
     */
    @Test
    fun aNameSpanningLinesIsRefusedRatherThanSent() {
        assertTrue(runCatching { NowPlayingCodec.encode("two\nlines.mp3") }.isFailure)
    }

    @Test
    fun anUnknownVersionIsRefusedRatherThanGuessedAt() {
        assertTrue(runCatching { NowPlayingCodec.decode("soundmesh-playing 2 倔强.mp3") }.isFailure)
    }
}
