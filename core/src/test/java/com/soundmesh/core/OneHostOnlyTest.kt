package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that a network carries one host, asked from the side of the handset enforcing it.
 *
 * Two hosts is not an untidiness. It is two timelines, two spatial fields and two sets of volumes
 * over one set of phones, with every sink silently belonging to whichever one it happened to find
 * first - and nothing downstream of that has any way to notice. So a handset told to be the host
 * looks first, and stands down if somebody already is.
 *
 * Every one of these is about [PeerAdvertisement.otherThan], because that is where the question
 * gets its only interesting answer wrong: the asking handset is already advertising when it looks.
 */
class OneHostOnlyTest {
    private fun peer(hostId: String, address: String) = DiscoveredPeer(
        name = "SoundMesh-$hostId",
        hostAddress = address,
        port = 45124,
        attributes = mapOf(
            PeerAdvertisement.VERSION_KEY to PeerAdvertisement.PROTOCOL_VERSION,
            PeerAdvertisement.ID_KEY to hostId
        )
    )

    private val mine = "0123456789abcdef"
    private val theirs = "fedcba9876543210"

    /**
     * The one that would break the whole feature rather than half of it.
     *
     * A handset has to advertise before it looks, or two people pressing the button at the same
     * moment could not see one another. So its own record answers its own search, and a search
     * that took the first answer would have every host stand down the second it became one - on a
     * network where discovery works perfectly, which is the network this is for.
     */
    @Test
    fun aHostDoesNotStandDownForItself() {
        val answered = listOf(peer(mine, "192.168.1.9"))

        assertNull(PeerAdvertisement.otherThan(answered, mine))
    }

    @Test
    fun aHostStandsDownForOneThatIsAlreadyThere() {
        val answered = listOf(peer(theirs, "192.168.1.4"))

        assertEquals("192.168.1.4", PeerAdvertisement.otherThan(answered, mine)?.hostAddress)
    }

    /** Its own record among the others changes nothing about the others. */
    @Test
    fun aHostFindsTheOtherOneFromAmongBothRecords() {
        val answered = listOf(peer(mine, "192.168.1.9"), peer(theirs, "192.168.1.4"))

        assertEquals("192.168.1.4", PeerAdvertisement.otherThan(answered, mine)?.hostAddress)
    }

    /**
     * The case the sink's own question refuses to answer, and this one must.
     *
     * [PeerAdvertisement.choose] hands back a null peer for two hosts, which is right for joining
     * a room - a quiet pick there joins a stranger's session. It is exactly wrong for counting
     * one, and a third handset arriving into an already-broken room is when the count matters
     * most.
     */
    @Test
    fun twoOtherHostsAreTheLoudestPossibleYes() {
        val outcome = PeerAdvertisement.choose(
            listOf(peer(theirs, "192.168.1.4"), peer("abcdefabcdefabcd", "192.168.1.7"))
        )

        assertNull("choose must still refuse to join one of two", outcome.peer)
        assertEquals(DiscoveryFailure.AMBIGUOUS, outcome.failure)
        assertEquals(2, outcome.compatible)
        assertNotNull(PeerAdvertisement.otherThan(outcome.hosts, mine))
    }

    /**
     * A handset on an older build is not a host this one has to stand down for.
     *
     * It cannot be talked to, so it cannot be the host of a room containing this handset, and
     * standing down for it would leave a network where nobody can be the host at all.
     */
    @Test
    fun anUnreachableNeighbourIsNotAHostToStandDownFor() {
        val old = DiscoveredPeer(
            name = "SoundMesh-old",
            hostAddress = "192.168.1.5",
            port = 45124,
            attributes = mapOf(PeerAdvertisement.VERSION_KEY to "1", PeerAdvertisement.ID_KEY to theirs)
        )
        val outcome = PeerAdvertisement.choose(listOf(old))

        assertEquals(1, outcome.seen)
        assertEquals(0, outcome.compatible)
        assertNull(PeerAdvertisement.otherThan(outcome.hosts, mine))
    }
}
