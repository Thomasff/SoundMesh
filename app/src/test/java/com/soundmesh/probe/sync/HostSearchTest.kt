package com.soundmesh.probe.sync

import com.soundmesh.core.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one part of asking the gateway that can be asked a question without a network under it.
 *
 * Everything else on that path needs a Context, a route table and a socket, and is held by the
 * source assertions in FindingEachOtherTest instead. This is the part that decides something, and
 * on 2026-09-21 it was decided by a line no test touched: deleting it left the whole app suite
 * green.
 */
class HostSearchTest {

    @Test
    fun `a handset that is the network does not follow its own answer`() {
        // What makes this reachable at all: a handset serving an access point is the default
        // gateway seen from inside the network it is providing, so it can ask itself and be
        // answered. The address it hands back is its own, and the id in it is its own.
        assertNull(
            HostSearch.notThisHandset(
                PairingCode(hostId = mine, address = "192.168.43.1", chunkPort = 45124),
                mine
            )
        )
    }

    @Test
    fun `somebody else at the gateway is somebody else`() {
        val theirs = PairingCode(hostId = "1f0c5b7a2d9e4630", address = "192.168.43.1", chunkPort = 45124)

        assertEquals(theirs, HostSearch.notThisHandset(theirs, mine))
    }

    /**
     * A look that heard a host and could not resolve it is not a look where nothing answered.
     *
     * On 2026-09-24 X10 heard Magic6 every ten seconds for as long as it looked, and its timeline
     * said "nothing answered" every time - which sends whoever reads it to the network, while the
     * fault was one resolve stuck inside this process.
     */
    @Test
    fun `hosts heard but not resolved are said`() {
        assertEquals("", HostSearch.unresolvedNote(0))
        assertEquals(" (1 heard but could not be resolved)", HostSearch.unresolvedNote(1))
    }

    @Test
    fun `nobody at the gateway is nobody`() {
        assertNull(HostSearch.notThisHandset(null, mine))
    }

    private val mine = "da3fe1c00de55dc6"
}
