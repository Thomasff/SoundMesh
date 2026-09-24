package com.soundmesh.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose song the playing page names.
 *
 * A handset keeps the song it chose while it was last a host, and a sink's page used to fall back
 * on it when its host had said nothing: a sink that had not reached anybody showed the title of a
 * song nobody was playing.
 */
class NowPlayingTest {
    @Test
    fun `a sink does not name the song it chose as a host`() {
        assertFalse(namesOwnSource(HomeState(role = Role.SINK, songName = "夜曲")))
    }

    @Test
    fun `a host names the song it chose`() {
        assertTrue(namesOwnSource(HomeState(role = Role.HOST, songName = "夜曲")))
    }
}
