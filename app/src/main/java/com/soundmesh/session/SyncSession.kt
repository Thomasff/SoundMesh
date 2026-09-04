package com.soundmesh.session

import com.soundmesh.core.SessionState

/**
 * A playback session that runs on its own threads until it is stopped.
 *
 * The harness in `probe.sync` runs for a number of seconds handed to it in an intent extra and
 * then reports. A product session has no such number: it runs until the user says otherwise, and
 * has to survive everything that happens in between. That difference is why this is a separate
 * path rather than a mode of [com.soundmesh.probe.sync.SyncActivity] - the harness is the ruler
 * every alignment measurement to date was taken with, and growing it into the product would mean
 * every future measurement is taken with a different one.
 *
 * Both roles implement this. What differs is what they do between [start] and [stop]; what a
 * caller can ask of them does not.
 */
interface SyncSession {
    /** Starts the session's threads. Returns once they are running, not once audio is flowing. */
    fun start()

    /** Stops everything and waits for the threads to finish. Idempotent. */
    fun stop()

    /** Where the session is right now. Safe to call from any thread. */
    fun state(): SessionState

    /**
     * The renderer's health counters, as JSON, or null before there is a renderer to ask.
     *
     * Section 8.3 calls these product-level indicators rather than debug logging, and until now
     * nothing outside the harness read them. A product session is also the first configuration in
     * which they can be read: a harness run spends most of its length in the by-design silence
     * between calibration chirps, which buries the silence counters under millions of frames that
     * mean nothing about playback quality.
     */
    fun report(): String?

    /**
     * Told by the service that owns the audio focus, not asked for by the session.
     *
     * A session that lost the focus stays wired up - its clock keeps exchanging, its link keeps
     * reading - and only stops putting frames on the output. That is what makes the resumption
     * land on the shared timeline instead of re-converging from nothing, and it is why this is a
     * notification rather than a stop and a fresh start.
     */
    fun onAudioFocusChanged(hasFocus: Boolean)
}
