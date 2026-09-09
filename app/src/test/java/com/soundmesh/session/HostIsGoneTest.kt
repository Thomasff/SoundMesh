package com.soundmesh.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a sink stops dialling a host that is not coming back.
 *
 * The loop it governs cannot be run here - it opens sockets and looks for a handset on the network
 * - so what is tested is the rule, and the rule is mostly one gate: a session that has never had a
 * host is in a different situation from one that had a host and lost it, and only the second is
 * allowed to give up.
 */
class HostIsGoneTest {
    private val budget = 60_000_000_000L
    private val now = 900_000_000_000L

    /**
     * Somebody who pressed play here and is still walking to the other phone has not lost
     * anything, and a sink that gave up on them would have to be started again for a reason
     * nothing on either screen could explain. SinkSession.connect's own comment says the same
     * thing about the first connection: a host opens its file before it binds anything, and
     * decoding takes as long as it takes.
     */
    @Test
    fun aSinkWhoseHostHasNotArrivedYetKeepsWaiting() {
        assertFalse(hostIsGone(everConnected = false, nowNanos = now, lastContactNanos = 0L, budgetNanos = budget))
    }

    /** The defect this exists for: a host that stopped used to leave a sink writing silence for ever. */
    @Test
    fun aHostThatHasBeenGoneLongerThanTheBudgetIsGone() {
        assertTrue(
            hostIsGone(
                everConnected = true,
                nowNanos = now,
                lastContactNanos = now - budget - 1,
                budgetNanos = budget
            )
        )
    }

    /**
     * An outage shorter than the budget is what the reconnection loop is for, and ending the
     * session there would turn a hiccup into something a person has to restart by hand.
     */
    @Test
    fun anOutageInsideTheBudgetIsSomethingToSurviveRatherThanToEnd() {
        assertFalse(
            hostIsGone(
                everConnected = true,
                nowNanos = now,
                lastContactNanos = now - budget + 1,
                budgetNanos = budget
            )
        )
    }
}
