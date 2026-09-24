package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class ResolveClientTest {
    private class Client

    @Test
    fun theSameClientServesUntilOneOfItsResolvesGetsStuck() {
        var opened = 0
        val clients = ResolveClient { opened++; Client() }
        val first = clients.take()
        assertSame(first, clients.take())
        assertEquals(1, opened)
        // What X10 did on 2026-09-24: one resolve never answered, and the platform refused every
        // one after it on that client until the process was killed.
        clients.giveUp(first)
        val second = clients.take()
        assertNotSame(first, second)
        assertEquals(2, opened)
    }

    /**
     * Two looks can overlap - a standing handset and a playing one both search - so the one giving
     * up late must not throw away the client the other has just opened.
     */
    @Test
    fun givingUpAClientAlreadyReplacedLeavesItsReplacement() {
        val clients = ResolveClient { Client() }
        val first = clients.take()
        clients.giveUp(first)
        val second = clients.take()
        clients.giveUp(first)
        assertSame(second, clients.take())
    }

    /** Which answers leave a client that can no longer resolve anything. */
    @Test
    fun noAnswerAndAlreadyActiveAreStuckOthersAreNot() {
        assertEquals(true, resolveWasStuck(null))
        assertEquals(true, resolveWasStuck(FAILURE_ALREADY_ACTIVE))
        // A resolve the platform answered with a failure of its own is over; the client is free.
        assertEquals(false, resolveWasStuck(0))
        assertEquals(false, resolveWasStuck("resolved"))
    }

    /**
     * The wiring the two tests above cannot reach, since it needs the platform: every resolve takes
     * its client from here and gives it up when stuck, and both searches say what was heard.
     */
    @Test
    fun resolvesGoThroughTheClientAndBothSearchesSayWhatWasHeard() {
        val discovery = source("src/main/java/com/soundmesh/probe/sync/PeerDiscovery.kt")
            .substringAfter("private fun resolve(")
        assertEquals(true, discovery.contains("val nsd = clients.take()"))
        assertEquals(true, discovery.contains("if (resolveWasStuck(answer)) clients.giveUp(nsd)"))
        val search = source("src/main/java/com/soundmesh/probe/sync/HostSearch.kt")
        assertEquals(2, search.split("+ unresolvedNote(outcome.unresolved)").size - 1)
    }

    private fun source(path: String) = File(path).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private companion object {
        // NsdManager.FAILURE_ALREADY_ACTIVE, written out: the test runs without the platform.
        const val FAILURE_ALREADY_ACTIVE = 3
    }
}
