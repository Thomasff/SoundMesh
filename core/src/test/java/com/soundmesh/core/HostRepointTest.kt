package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a sink does when the host it was pointed at stops answering at the address it was given.
 *
 * A scanned code carries three things and only one of them is an identity. The host id is the
 * handset; the address and the port are where it happened to be standing when somebody held a
 * phone up to its screen. Until this existed the address was treated as though it were the
 * identity: a sink stored it once and dialled it for ever, and a host that moved to a hotspot, or
 * onto another network, or handed the role to a different phone, left every sink in the room
 * dialling somewhere nobody is - with no way out of it but somebody finding 忘记主机 by hand.
 * Reported 2026-09-19 as "有时候没有自动连上".
 *
 * The rule these encode: a person's own choice of handset outranks anything the network says, and
 * the network is allowed to say where that handset is now.
 */
class HostRepointTest {
    private fun peer(hostId: String, address: String, port: Int = 45124) = DiscoveredPeer(
        name = "SoundMesh-$hostId",
        hostAddress = address,
        port = port,
        attributes = mapOf(
            PeerAdvertisement.VERSION_KEY to PeerAdvertisement.PROTOCOL_VERSION,
            PeerAdvertisement.ID_KEY to hostId
        )
    )

    private val ours = "0123456789abcdef"
    private val stranger = "fedcba9876543210"
    private val stored = PairingCode(hostId = ours, address = "192.168.1.4", chunkPort = 45124)

    @Test
    fun saysNothingHasMovedWhenTheHostAnswersFromItsOwnAddress() {
        val what = HostRepoint.of(stored, listOf(peer(ours, "192.168.1.4")))

        assertEquals(Repointing.STILL_THERE, what.verdict)
        assertNull("nothing to write when nothing moved", what.host)
    }

    /** The hotspot case, and the one this whole file is for. */
    @Test
    fun followsTheSameHandsetToItsNewAddress() {
        val what = HostRepoint.of(stored, listOf(peer(ours, "192.168.43.1")))

        assertEquals(Repointing.MOVED, what.verdict)
        assertEquals(PairingCode(ours, "192.168.43.1", 45124), what.host)
    }

    /** The port travels in the record, so a host that moved ports has moved as much as one that moved address. */
    @Test
    fun followsItToANewPortAsWell() {
        val what = HostRepoint.of(stored, listOf(peer(ours, "192.168.1.4", port = 45999)))

        assertEquals(Repointing.MOVED, what.verdict)
        assertEquals(PairingCode(ours, "192.168.1.4", 45999), what.host)
    }

    /**
     * The one other host on a network that is only allowed one.
     *
     * This is the half that is a judgement rather than a lookup: it points the handset at a host
     * nobody scanned. It is gated on the stored host being absent from the answer - not merely
     * unreachable - and on there being exactly one candidate, which is the only shape in which
     * "the host of this network" names one handset.
     */
    @Test
    fun takesTheOneOtherHostWhenTheStoredOneIsNotOnThisNetwork() {
        val what = HostRepoint.of(stored, listOf(peer(stranger, "192.168.1.7")))

        assertEquals(Repointing.REPLACED, what.verdict)
        assertEquals(PairingCode(stranger, "192.168.1.7", 45124), what.host)
    }

    /**
     * The stored host wins even where the one-host rule is already broken.
     *
     * Two hosts answering is a room in a state nothing downstream can notice, and it is still not
     * a reason to leave the handset somebody chose. Refusing here would put the sink on the
     * stranger's timeline in exactly the moment the room is hardest to reason about.
     */
    @Test
    fun prefersTheStoredHostOverAStrangerStandingBesideIt() {
        val what = HostRepoint.of(stored, listOf(peer(stranger, "192.168.1.7"), peer(ours, "192.168.1.9")))

        assertEquals(Repointing.MOVED, what.verdict)
        assertEquals(PairingCode(ours, "192.168.1.9", 45124), what.host)
    }

    /**
     * Nothing answered is not the same as the host having gone.
     *
     * mDNS on a network that filters multicast answers exactly as an empty network does, so this
     * is the common case on a guest network and it must change nothing. See [HostSearch].
     */
    @Test
    fun keepsTheStoredHostWhenNothingAnswered() {
        val what = HostRepoint.of(stored, emptyList())

        assertEquals(Repointing.NOBODY, what.verdict)
        assertNull(what.host)
    }

    /** Two strangers and no stored host is the one case with no answer: picking takes whoever answered first. */
    @Test
    fun refusesToPickBetweenTwoStrangers() {
        val what = HostRepoint.of(
            stored,
            listOf(peer(stranger, "192.168.1.7"), peer("abcdefabcdefabcd", "192.168.1.8"))
        )

        assertEquals(Repointing.TOO_MANY, what.verdict)
        assertNull(what.host)
    }

    /**
     * Measured 2026-09-21: a host that had handed the role over went on being answered for
     * between 52 and 73 seconds, and the one sink that stayed a sink across the handover sat
     * through five consecutive [Repointing.STILL_THERE] looks, ten seconds apart, before the
     * record finally expired and the ordinary [Repointing.REPLACED] fired. Nothing else was
     * wrong; the room was simply silent for a minute.
     *
     * This changes no outcome, only when it happens: the handset was always going to end up on
     * the other host. Giving a registration back is a request to a platform daemon rather than
     * an act, so the record answering says nothing about whether anybody is serving - and the
     * one caller of this asks it precisely because it cannot get through to that address.
     */
    @Test
    fun doesNotWaitOutARecordThatOutlivedItsHost() {
        val what = HostRepoint.ofUnreachable(
            stored,
            listOf(peer(ours, "192.168.1.4"), peer(stranger, "192.168.1.7")),
            stillServing = { false }
        )

        assertEquals(Repointing.REPLACED_A_GHOST, what.verdict)
        assertEquals(PairingCode(stranger, "192.168.1.7", 45124), what.host)
    }

    /**
     * And it strikes the record out for one purpose only, which is taking the one host that is
     * here. With nowhere to go it says what it saw: something did answer from that address, and
     * reporting "nothing answered" would send whoever reads the timeline to the wrong place.
     */
    @Test
    fun saysWhatItSawWhenStrikingTheRecordOutBuysNothing() {
        val alone = HostRepoint.ofUnreachable(
            stored,
            listOf(peer(ours, "192.168.1.4")),
            stillServing = { false }
        )
        assertEquals(Repointing.STILL_THERE, alone.verdict)
        assertNull(alone.host)

        val crowded = HostRepoint.ofUnreachable(
            stored,
            listOf(peer(ours, "192.168.1.4"), peer(stranger, "1.1.1.1"), peer("abcdefabcdefabcd", "1.1.1.2")),
            stillServing = { false }
        )
        assertEquals(Repointing.STILL_THERE, crowded.verdict)
        assertNull(crowded.host)
    }

    /**
     * A host that is serving keeps the room, however long this handset has failed to reach it.
     *
     * The line going down says something about the pair, not about the host: this handset's own
     * radio hiccuping looks identical from in here. Without this the fix above would hand a room
     * to a stranger every time a sink lost its network for five seconds beside one - which is a
     * room already in trouble, and the worst moment to move anybody.
     */
    @Test
    fun leavesAHostThatIsStillServingAloneBesideAStranger() {
        val what = HostRepoint.ofUnreachable(
            stored,
            listOf(peer(ours, "192.168.1.4"), peer(stranger, "192.168.1.7")),
            stillServing = { true }
        )

        assertEquals(Repointing.STILL_THERE, what.verdict)
        assertNull(what.host)
    }

    /**
     * A host that moved is not a ghost. The line being down is exactly what a moved host looks
     * like from here, and following it is the whole reason [HostRepoint] exists - so only a
     * record answering from the address this handset has already failed to reach is struck out.
     */
    @Test
    fun aHostThatMovedIsFollowedRatherThanStruckOut() {
        val what = HostRepoint.ofUnreachable(
            stored,
            listOf(peer(ours, "192.168.43.1"), peer(stranger, "192.168.1.7")),
            // Dialling the stored address would be asking about a handset that has told the
            // network where it is now. Throwing says so: this path must not wait on a network.
            stillServing = { error("a record that moved is not a ghost and must not be dialled") }
        )

        assertEquals(Repointing.MOVED, what.verdict)
        assertEquals(PairingCode(ours, "192.168.43.1", 45124), what.host)
    }
}
