package com.soundmesh.session

import com.soundmesh.core.SessionConditions
import com.soundmesh.core.SessionState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The live conditions a session's [SessionState] is read off.
 *
 * Each is set by whoever learns of it - the service for the focus, the chunk link for itself, the
 * clock estimator for convergence - and none of them decides what the session's state is. That
 * decision is [SessionState.of]'s alone, so there is one place where the precedence between a lost
 * focus and a lost link is written down.
 *
 * A host never clears [linkUp] or [clockConverged]: it holds the reference clock, so its own
 * nanoTime is host time by definition, and a sink that has not connected is not a reason for the
 * host to fall silent.
 */
class SessionFlags {
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    // Starts false: the session has not been granted the focus until the service says so, and
    // reporting PLAYING in the gap would be a claim to an output this app does not yet hold.
    private val audioFocus = AtomicBoolean(false)
    private val linkUp = AtomicBoolean(true)
    private val clockConverged = AtomicBoolean(true)
    // Starts false for the same reason the others start true: nothing has measured a clock as
    // uncertain yet, and a session that has not converged one reports that instead.
    private val clockUncertain = AtomicBoolean(false)

    fun markStarted() = started.set(true)

    fun markStopped() = stopped.set(true)

    fun setAudioFocus(held: Boolean) = audioFocus.set(held)

    fun setLinkUp(up: Boolean) = linkUp.set(up)

    fun setClockConverged(converged: Boolean) = clockConverged.set(converged)

    fun setClockUncertain(uncertain: Boolean) = clockUncertain.set(uncertain)

    fun isStopped(): Boolean = stopped.get()

    fun state(): SessionState = SessionState.of(
        SessionConditions(
            started = started.get(),
            stopped = stopped.get(),
            hasAudioFocus = audioFocus.get(),
            linkUp = linkUp.get(),
            clockConverged = clockConverged.get(),
            clockUncertain = clockUncertain.get()
        )
    )
}
