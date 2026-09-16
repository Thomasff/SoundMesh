package com.soundmesh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a host tells the room it is playing.
 *
 * Every case here was silence before 2026-09-16 except the first, and the silence was invisible:
 * a sink keeps the last name it was handed, so a handset that had once been in a folder session
 * went on showing a song from it - correctly rendered, plausibly worded, and from a session that
 * had ended hours earlier.
 */
class NowPlayingNameTest {
    private val folder = listOf("夜曲.flac", "晴天.flac")

    @Test
    fun `a song in a list is named by the list`() {
        assertEquals("晴天.flac", nowPlayingName(1, folder, "本机的声音"))
    }

    /**
     * The capture, which is the case that has no list at all. It is also the case a room most
     * needs told: the other handsets have nothing else to go on, because there is no file here
     * and there is never going to be one.
     */
    @Test
    fun `a source with no list at all still says what it is`() {
        assertEquals("正在播放主机上的声音", nowPlayingName(null, emptyList(), "正在播放主机上的声音"))
    }

    /**
     * One song picked on the handset: it has a place in a list of one, and if whoever opened the
     * session did not list it, the source's own name is still an answer. Both halves are the same
     * rule - the list first, the source's name after - rather than two branches somewhere else.
     */
    @Test
    fun `a place the list does not reach falls back to what the source is called`() {
        assertEquals("本机的声音", nowPlayingName(0, emptyList(), "本机的声音"))
        assertEquals("本机的声音", nowPlayingName(7, folder, "本机的声音"))
    }

    /**
     * The one case that is genuinely nothing to say, and it is worth a line because the wire
     * refuses an empty name: a message that cannot be encoded would be counted as unreadable at
     * the far end, which is a fault report for a room that simply has no name for its audio.
     */
    @Test
    fun `nothing to say is said as nothing rather than as an empty name`() {
        assertNull(nowPlayingName(null, emptyList(), ""))
        assertNull(nowPlayingName(0, emptyList(), ""))
    }
}
