package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who has stopped being heard from, which is the only question the room's own channels cannot
 * answer.
 *
 * Reported on 2026-09-11 from a session on hardware: a sink's WiFi was switched off and the host
 * said nothing at all - solid icon, no name in the dropped row - because everything that decides
 * it waits for a write to fail, and a write to a handset that walked out of the network does not
 * fail. `/proc/net/tcp` on the host had half a megabyte sitting in one send queue, ESTABLISHED,
 * while the phone it was addressed to had been off the network for minutes.
 *
 * What this reads instead is the clock channel, which is UDP and has no connection to be wrong
 * about: a sink asks the host for the time every two seconds for the whole of a session, and a
 * sink that is gone stops asking. Nothing was added to any protocol for this - the requests were
 * already arriving, and nobody was writing down when.
 */
class PeerSilenceTest {
    private val one = "a1b2c3d4e5f60718"
    private val other = "1122334455667788"

    @Test
    fun namesTheHandsetThatStoppedAskingForTheTime() {
        val silence = PeerSilence(quietAfterMillis = 6_000L)
        val room = mapOf(one to "192.168.1.20")

        silence.quiet(room, mapOf("192.168.1.20" to 1_000L), now = 1_000L)

        assertEquals(setOf(one), silence.quiet(room, mapOf("192.168.1.20" to 1_000L), now = 7_000L))
    }

    /** And says nothing about the one that is still asking, which is every handset in a good room. */
    @Test
    fun saysNothingAboutAHandsetStillAsking() {
        val silence = PeerSilence(quietAfterMillis = 6_000L)
        val room = mapOf(one to "192.168.1.20", other to "192.168.1.21")
        silence.quiet(room, mapOf("192.168.1.20" to 1_000L, "192.168.1.21" to 1_000L), now = 1_000L)

        val quiet = silence.quiet(
            room,
            mapOf("192.168.1.20" to 6_800L, "192.168.1.21" to 1_000L),
            now = 7_000L
        )

        assertEquals(setOf(other), quiet)
    }

    /**
     * A handset that has only just joined has not been heard from yet, and that is not the same
     * thing as having gone quiet.
     *
     * Without this the drawing would call every arriving handset dropped for the first few
     * seconds of its life in the room - which is exactly the moment a listener is looking at it.
     */
    @Test
    fun givesAHandsetThatJustJoinedTimeToSayAnything() {
        val silence = PeerSilence(quietAfterMillis = 6_000L)
        val room = mapOf(one to "192.168.1.20")

        assertEquals(emptySet<String>(), silence.quiet(room, emptyMap(), now = 4_000L))
        assertEquals(emptySet<String>(), silence.quiet(room, emptyMap(), now = 9_500L))
        // Nine and a half seconds in the room and never a word: at that point the grace is spent
        // and this is a handset nothing has ever been heard from, which is what it says.
        assertEquals(setOf(one), silence.quiet(room, emptyMap(), now = 10_001L))
    }

    /** The network comes back on its own, and so does the icon. */
    @Test
    fun stopsNamingAHandsetThatStartedAskingAgain() {
        val silence = PeerSilence(quietAfterMillis = 6_000L)
        val room = mapOf(one to "192.168.1.20")
        silence.quiet(room, mapOf("192.168.1.20" to 1_000L), now = 1_000L)
        assertEquals(setOf(one), silence.quiet(room, mapOf("192.168.1.20" to 1_000L), now = 8_000L))

        val quiet = silence.quiet(room, mapOf("192.168.1.20" to 8_500L), now = 9_000L)

        assertEquals(emptySet<String>(), quiet)
    }

    /**
     * A handset that left and came back is given its grace again, rather than being called dropped
     * the instant it reappears.
     *
     * It comes back on a fresh connection, and the last time anything was heard from its address
     * is from before it went - which is old enough to be past the window on its own.
     */
    @Test
    fun letsAReturningHandsetSettleRatherThanCallingItDroppedOnArrival() {
        val silence = PeerSilence(quietAfterMillis = 6_000L)
        val room = mapOf(one to "192.168.1.20")
        silence.quiet(room, mapOf("192.168.1.20" to 1_000L), now = 1_000L)
        // Out of the room entirely: its socket closed, so it is in no roster at all.
        silence.quiet(emptyMap(), mapOf("192.168.1.20" to 1_000L), now = 30_000L)

        val quiet = silence.quiet(room, mapOf("192.168.1.20" to 1_000L), now = 60_000L)

        assertEquals(emptySet<String>(), quiet)
    }

    /** An empty room is not a room full of dropped handsets. */
    @Test
    fun saysNothingAboutARoomWithNobodyInIt() {
        assertEquals(
            emptySet<String>(),
            PeerSilence(quietAfterMillis = 6_000L).quiet(emptyMap(), emptyMap(), now = 50_000L)
        )
    }
}
