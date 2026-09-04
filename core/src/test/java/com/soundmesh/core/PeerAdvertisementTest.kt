package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerAdvertisementTest {
    private fun peer(
        name: String,
        version: String? = PeerAdvertisement.PROTOCOL_VERSION,
        address: String = "192.168.43.1"
    ) = DiscoveredPeer(
        name = name,
        hostAddress = address,
        port = 45124,
        attributes = mapOf(PeerAdvertisement.VERSION_KEY to version)
    )

    @Test
    fun advertisesTheVersionASinkChecksFor() {
        assertEquals(
            PeerAdvertisement.PROTOCOL_VERSION,
            PeerAdvertisement.attributes()[PeerAdvertisement.VERSION_KEY]
        )
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
        val outcome = PeerAdvertisement.choose(listOf(peer("X10", version = "0")))

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
     * Two hosts is not a tie to be broken quietly. In a dorm or a cafe the arbitrary pick joins a
     * stranger's session, and nothing in the run would say so - which is the case the scanned code
     * exists for, because a code read off the screen in your hand names one host and only one.
     */
    @Test
    fun refusesToGuessBetweenTwoCompatibleHosts() {
        val outcome = PeerAdvertisement.choose(listOf(peer("X10"), peer("Pixel", address = "192.168.43.9")))

        assertEquals(DiscoveryFailure.AMBIGUOUS, outcome.failure)
        assertNull(outcome.peer)
        assertEquals(2, outcome.compatible)
    }

    /** An incompatible neighbour must not make the one usable host ambiguous. */
    @Test
    fun ignoresAnIncompatibleNeighbourWhenChoosing() {
        val outcome = PeerAdvertisement.choose(
            listOf(peer("Old", version = "0", address = "192.168.43.9"), peer("X10"))
        )

        assertNull(outcome.failure)
        assertEquals("X10", outcome.peer!!.name)
        assertEquals(2, outcome.seen)
        assertEquals(1, outcome.compatible)
    }
}
