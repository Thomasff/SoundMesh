package com.soundmesh.core

/**
 * Which song a folder moves to when the listener asks for the next one or the one before.
 *
 * Only one end needs deciding. Before the first song there is nowhere to go, so the song plays
 * again: that is the only answer which is not a press that did nothing, and a press that does
 * nothing is indistinguishable on a phone from a touch the screen missed. Past the last song the
 * answer is an index the list does not reach, which ends it - a folder that runs out already
 * stops rather than starting again, and a button that wrapped round would be looping turned back
 * on by the back door.
 *
 * There is deliberately no upper clamp and no song count. One was written, and a mutation check
 * showed it could be deleted without reddening a test: with steps of one from a valid index it
 * never binds. A branch no caller can reach is a branch nothing is guarding.
 *
 * A separate function from the source that uses it because that source is a MediaCodec loop with
 * nothing to hold on to off a handset, and this decision is the part worth pinning down. Same
 * shape and the same reason as [driftIntervalNanos] and [acquiringTotalNanos].
 */
fun songAfterStep(current: Int, by: Int): Int = (current + by).coerceAtLeast(0)
