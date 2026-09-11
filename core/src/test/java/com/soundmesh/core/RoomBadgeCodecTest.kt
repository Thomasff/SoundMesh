package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBadgeCodecTest {
    private val one = "a1b2c3d4e5f60718"
    private val two = "0918273645abcdef"

    @Test
    fun carriesTheWholeTableThere() {
        val table = mapOf(one to 5, two to 0)

        assertEquals(table, RoomBadgeCodec.decode(RoomBadgeCodec.encode(table)))
    }

    /**
     * Three kinds of message share this channel and a reader tells them apart by the first word.
     * A reader that mistook one for another would not fail - it would read a rule out of a colour
     * table and render a room nobody drew.
     */
    @Test
    fun isTellableApartFromTheOtherMessagesOnTheChannel() {
        val mine = RoomBadgeCodec.encode(mapOf(one to 5))

        assertTrue(RoomBadgeCodec.looksLikeOne(mine))
        assertFalse(RoomBadgeCodec.looksLikeOne(NowPlayingCodec.encode("a song")))
        assertFalse(NowPlayingCodec.looksLikeOne(mine))
    }

    @Test
    fun refusesAVersionItDoesNotSpeak() {
        val text = RoomBadgeCodec.encode(mapOf(one to 5))
            .replaceFirst("${RoomBadgeCodec.MAGIC} ${RoomBadgeCodec.VERSION}", "${RoomBadgeCodec.MAGIC} 99")

        assertThrows { RoomBadgeCodec.decode(text) }
    }

    /**
     * A name arrives here from another handset and goes on to index a palette and to be matched
     * against this handset's own name. Both of those are quiet when the value is wrong: a bad place
     * throws inside a draw, and a bad name simply never matches, which reads as a handset that was
     * given no colour rather than as a message that was wrong.
     */
    @Test
    fun refusesANameThatIsNotOne() {
        assertThrows { RoomBadgeCodec.decode("${RoomBadgeCodec.MAGIC} ${RoomBadgeCodec.VERSION} ../etc=5") }
        assertThrows { RoomBadgeCodec.decode("${RoomBadgeCodec.MAGIC} ${RoomBadgeCodec.VERSION} $one") }
    }

    @Test
    fun refusesAPlaceThatIsNotInThePalette() {
        assertThrows { RoomBadgeCodec.decode("${RoomBadgeCodec.MAGIC} ${RoomBadgeCodec.VERSION} $one=${PeerBadge.COLOURS}") }
        assertThrows { RoomBadgeCodec.decode("${RoomBadgeCodec.MAGIC} ${RoomBadgeCodec.VERSION} $one=-1") }
        assertThrows { RoomBadgeCodec.decode("${RoomBadgeCodec.MAGIC} ${RoomBadgeCodec.VERSION} $one=blue") }
    }

    /**
     * An empty table would be indistinguishable from a room that has emptied, and a room never
     * empties: the host is in its own roster from the moment it has a name to be in it under.
     */
    @Test
    fun refusesToSayARoomHasNobodyInIt() {
        assertThrows { RoomBadgeCodec.encode(emptyMap()) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected to be refused")
        } catch (expected: IllegalArgumentException) {
            // what refusal looks like here
        }
    }
}
