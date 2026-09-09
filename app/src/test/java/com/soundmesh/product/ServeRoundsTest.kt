package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The loop that serves one sink after another, apart from everything it serves them with.
 *
 * A round is fifty seconds of chirps against a real handset and cannot be run here at all, so what
 * is tested is the only decision the loop makes: given how a round came out, does the next handset
 * still get its turn. That decision is the whole of what this change adds - the rounds themselves
 * are what the host already did once per press.
 */
class ServeRoundsTest {
    private fun never() = false

    /**
     * The requirement this exists for. One handset's round breaking - a garbled ask, a sink that
     * died between the plan and the delivery - used to be the end of the session because the
     * session was one round; the whole point of one press serving several is that it is not.
     */
    @Test
    fun aRoundThatThrowsLeavesTheNextHandsetItsTurn() {
        // Keyed off the call rather than off the count handed in: a round that failed does not
        // advance that count, so a lambda deciding by it would throw for ever.
        var calls = 0
        val handedIn = mutableListOf<Int>()
        val served = serveRounds(::never) { alreadyServed ->
            handedIn += alreadyServed
            calls++
            when (calls) {
                1 -> throw IllegalStateException("this sink's round broke")
                4 -> RoundResult.NOBODY_ASKED
                else -> RoundResult.SERVED
            }
        }

        assertEquals(2, served)
        // The count handed to each round is how many have been served, which is what the screen
        // counts up while somebody walks across the room - and a round that broke did not serve
        // anybody, so the handset after it is still told nought.
        assertEquals(listOf(0, 0, 1, 2), handedIn)
    }

    /** Nobody asked inside the wait, so nobody else is coming and the session is over. */
    @Test
    fun theSessionEndsWhenNobodyAsks() {
        var calls = 0
        val served = serveRounds(::never) {
            calls++
            RoundResult.NOBODY_ASKED
        }

        assertEquals(0, served)
        assertEquals(1, calls)
    }

    /** The stop button, which is checked between rounds rather than inside one. */
    @Test
    fun theSessionEndsWhenItIsStopped() {
        var calls = 0
        val served = serveRounds({ calls >= 2 }) {
            calls++
            RoundResult.SERVED
        }

        assertEquals(2, served)
    }

    /** Stopped before it began measures nobody rather than measuring one and then noticing. */
    @Test
    fun stoppedBeforeItStartsServesNobody() {
        var calls = 0
        val served = serveRounds({ true }) {
            calls++
            RoundResult.SERVED
        }

        assertEquals(0, served)
        assertEquals(0, calls)
    }

    /**
     * A round can fail without waiting - a request this host refuses comes back at once - so
     * "carry on after a failure" has to be bounded or a jammed session spins on one thread for as
     * long as the screen is up, looking from outside exactly like one that is working.
     */
    @Test
    fun aSessionThatFailsOverAndOverGivesUp() {
        var calls = 0
        val served = serveRounds(::never) {
            calls++
            RoundResult.FAILED
        }

        assertEquals(0, served)
        assertEquals(MAX_FAILURES_IN_A_ROW, calls)
    }

    /**
     * And the count is consecutive failures, not failures. A room where every other handset has
     * trouble is still a room worth finishing, and counting them all would stop it partway through
     * for a reason nobody watching could see.
     */
    @Test
    fun aFailureBetweenTwoGoodRoundsIsForgotten() {
        val script = listOf(
            RoundResult.FAILED, RoundResult.SERVED,
            RoundResult.FAILED, RoundResult.SERVED,
            RoundResult.FAILED, RoundResult.SERVED,
            RoundResult.NOBODY_ASKED
        )
        var call = 0
        val served = serveRounds(::never) { script[call++] }

        assertEquals(3, served)
        assertEquals(script.size, call)
    }
}
