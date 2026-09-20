package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerAdvertisementTest {
    private fun peer(
        name: String,
        version: String? = PeerAdvertisement.PROTOCOL_VERSION,
        address: String = "192.168.43.1",
        hostId: String? = "0123456789abcdef"
    ) = DiscoveredPeer(
        name = name,
        hostAddress = address,
        port = 45124,
        attributes = mapOf(
            PeerAdvertisement.VERSION_KEY to version,
            PeerAdvertisement.ID_KEY to hostId
        )
    )

    @Test
    fun advertisesTheVersionASinkChecksFor() {
        assertEquals(
            PeerAdvertisement.PROTOCOL_VERSION,
            PeerAdvertisement.attributes("0123456789abcdef")[PeerAdvertisement.VERSION_KEY]
        )
    }

    /** A run that found its host over mDNS has to file it under the name a scan would give it. */
    @Test
    fun advertisesTheNameAPeersCalibrationIsKeptUnder() {
        val advertised = PeerAdvertisement.attributes("0123456789abcdef")

        assertEquals("0123456789abcdef", advertised[PeerAdvertisement.ID_KEY])
        assertEquals("0123456789abcdef", PeerAdvertisement.hostIdOf(peer("X10")))
    }

    @Test
    fun choosesTheOneCompatibleHostOnTheNetwork() {
        val chosen = PeerAdvertisement.choose(listOf(peer("X10")))

        assertNull(chosen.failure)
        assertEquals("X10", chosen.peer!!.name)
        assertEquals(1, chosen.seen)
        assertEquals(1, chosen.compatible)
    }

    @Test
    fun findsNothingOnAnEmptyNetwork() {
        val outcome = PeerAdvertisement.choose(emptyList())

        assertEquals(DiscoveryFailure.NOTHING_FOUND, outcome.failure)
        assertNull(outcome.peer)
    }

    /**
     * An older build advertising the same service type is worse than no host at all: the sink
     * would connect, the chunk stream would decode, and the run would fail somewhere further in
     * where nothing points back at the mismatch.
     */
    @Test
    fun refusesAHostSpeakingAnotherProtocolVersion() {
        val outcome = PeerAdvertisement.choose(listOf(peer("X10", version = "1")))

        assertEquals(DiscoveryFailure.NO_COMPATIBLE_VERSION, outcome.failure)
        assertEquals(1, outcome.seen)
        assertEquals(0, outcome.compatible)
    }

    /** A service with no version attribute at all is not a SoundMesh host this build knows. */
    @Test
    fun refusesAHostThatAdvertisesNoVersion() {
        val outcome = PeerAdvertisement.choose(listOf(peer("X10", version = null)))

        assertEquals(DiscoveryFailure.NO_COMPATIBLE_VERSION, outcome.failure)
    }

    /**
     * The identity travels with the version, so this combination is a malformed record rather than
     * an old build. Accepting it would file the run's calibration under whatever the record held.
     */
    @Test
    fun refusesAHostWhoseIdIsMissingOrMalformed() {
        assertEquals(
            DiscoveryFailure.NO_COMPATIBLE_VERSION,
            PeerAdvertisement.choose(listOf(peer("X10", hostId = null))).failure
        )
        assertEquals(
            DiscoveryFailure.NO_COMPATIBLE_VERSION,
            PeerAdvertisement.choose(listOf(peer("X10", hostId = "../secret"))).failure
        )
    }

    /**
     * Two hosts is not a tie to be broken quietly. In a dorm or a cafe the arbitrary pick joins a
     * stranger's session, and nothing in the run would say so - which is the case the scanned code
     * exists for, because a code read off the screen in your hand names one host and only one.
     */
    @Test
    fun refusesToGuessBetweenTwoCompatibleHosts() {
        val outcome = PeerAdvertisement.choose(
            listOf(peer("X10"), peer("Pixel", address = "192.168.43.9", hostId = "fedcba9876543210"))
        )

        assertEquals(DiscoveryFailure.AMBIGUOUS, outcome.failure)
        assertNull(outcome.peer)
        assertEquals(2, outcome.compatible)
    }

    /**
     * One host under two service names is one host.
     *
     * mDNS names have to be unique on a network, so a handset that registers again while its own
     * previous registration is still being answered - which is what taking the host role twice in
     * an evening does - is renamed by the platform rather than refused. Both records then answer,
     * both carry the same identity, and reading that as two hosts refuses the room its only host
     * until one of the names ages out. Seen on 09-21: seventy seconds of "more than one host
     * answered" on a handset with exactly one other handset in the flat.
     */
    @Test
    fun treatsTwoNamesForOneIdentityAsOneHost() {
        val outcome = PeerAdvertisement.choose(
            listOf(peer("X10"), peer("X10 (2)", address = "192.168.43.9"))
        )

        assertNull(outcome.failure)
        assertEquals("X10", outcome.peer!!.name)
        assertEquals(1, outcome.compatible)
    }

    /** An incompatible neighbour must not make the one usable host ambiguous. */
    @Test
    fun ignoresAnIncompatibleNeighbourWhenChoosing() {
        val outcome = PeerAdvertisement.choose(
            listOf(peer("Old", version = "1", address = "192.168.43.9"), peer("X10"))
        )

        assertNull(outcome.failure)
        assertEquals("X10", outcome.peer!!.name)
        assertEquals(2, outcome.seen)
        assertEquals(1, outcome.compatible)
    }
}
