package com.soundmesh.desktop

import com.soundmesh.core.HostId
import com.soundmesh.core.PeerAdvertisement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Against the real responder, because nothing else can judge this.
 *
 * Every line of the native half is a struct offset copied out of windns.h, and a wrong one does
 * not fail loudly: it reads a neighbouring field, which is a plausible-looking port or an empty
 * attribute map. The only judge that knows the right answer is the one Windows itself gives back
 * for a record this process put there, so that is what these ask.
 *
 * The records are this run's own, found by an identity generated here, and never "the one host".
 * The network this runs on can have a handset advertising on it, and a test that expected to be
 * alone would fail on the day it is not.
 */
class PeerDiscoveryTest {

    @Test
    fun aRecordThisMachineAdvertisesIsFoundWithEverythingASinkReadsFromIt() {
        val id = HostId.generate()
        PeerDiscovery.register("SoundMesh-$id", PORT, id).use {
            val outcome = PeerDiscovery.discover(WINDOW_MILLIS)
            val mine = outcome.hosts.singleOrNull { PeerAdvertisement.hostIdOf(it) == id }
            assertNotNull("not among ${outcome.hosts.map { it.name }}, ${outcome.seen} seen", mine)
            assertEquals(PORT, mine!!.port)
            assertEquals(PeerAdvertisement.PROTOCOL_VERSION, mine.attributes[PeerAdvertisement.VERSION_KEY])
            // An address a socket can be opened on, not merely a string: a record resolved to no
            // address is exactly as useless to a sink as one that was never found.
            assertTrue(mine.hostAddress, mine.hostAddress.matches(IPV4))
        }
    }

    @Test
    fun aRecordIsGoneOnceItsRegistrationIsClosed() {
        val id = HostId.generate()
        PeerDiscovery.register("SoundMesh-$id", PORT, id).close()
        val outcome = PeerDiscovery.discover(WINDOW_MILLIS)
        // The failure this guards against is the one the handsets had: a host that had stood down
        // went on answering for over a minute, and a sink dialled it.
        assertNull(outcome.hosts.firstOrNull { PeerAdvertisement.hostIdOf(it) == id })
    }

    private companion object {
        const val PORT = 45999
        const val WINDOW_MILLIS = 3_000
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
