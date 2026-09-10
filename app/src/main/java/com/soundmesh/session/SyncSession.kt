package com.soundmesh.session

import com.soundmesh.core.SessionState
import com.soundmesh.probe.sync.Playhead

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
/**
 * How late a product session lets a chunk be released before it shortens it.
 *
 * Five milliseconds, against the renderer's own 48-frame default, and the difference is the whole
 * of what a listener was complaining about. At 48 the host edits its own waveform 6.6 times a
 * second - 3.30 trims averaging 77 deleted frames, and 3.31 silence writes - which is what "it
 * keeps hitching" sounds like. At 240 that falls to 0.03 times a second and the hitching is gone;
 * at 120 it falls to 0.03-0.17 and a little remains.
 *
 * It costs nothing measurable. Three runs at 240 (O52-O54) against four archived runs at 48
 * (O47, O49-O51) put the round-to-round scatter at 0.357 ms against 0.354 ms - the same number -
 * and the difference in means at 1.4 standard errors, which this many rounds cannot resolve. What
 * the comparison can exclude is a penalty above about 0.75 ms, against a 5 ms gate. A systematic
 * component would not survive anyway: the stored per-peer calibration is a running mean and
 * absorbs one within a few runs, which is what it did across those three.
 *
 * Why a wider band is not "tolerating more error": the trim and the drift controller correct the
 * same quantity, one by deleting a block of audio at once and one by adding or dropping single
 * frames over seconds. Widening the band hands the work from the audible mechanism to the
 * inaudible one. The device says the inaudible one keeps up - through all of this the host stayed
 * in TRACKING with zero reacquisitions and a filtered error of 9 frames.
 *
 * The harness keeps [com.soundmesh.probe.sync.SyncRenderer.TRIM_DEADBAND_FRAMES] instead. Every
 * archived measurement was taken at 48, and moving the ruler to match the product would end the
 * comparability of all of them.
 */
const val PRODUCT_TRIM_FRAMES = 240

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
     * Starts playing from [micros] into whatever is playing now.
     *
     * Returns as soon as it is asked for rather than once it is heard. Everything in flight is
     * thrown away - three seconds of decoded audio and 1.5 s of chunks already handed to the room
     * - so the room goes quiet for about the length of the lead and then plays the new place.
     * Silence rather than the old place: a room that carried on playing where it was for a second
     * and a half after somebody dragged a slider is a room that looks broken.
     *
     * Declared on both roles rather than defaulted, for the reason [onNetworkChanged] gives.
     */
    fun seekTo(micros: Long)

    /**
     * Starts the song [by] places along the list, from its beginning.
     *
     * Everything [seekTo] throws away is thrown away here too, and for the same reason - what is
     * in flight is the old song. Which song a step lands on at either end of the list is
     * [com.soundmesh.core.songAfterStep]'s decision, taken where the index is known rather than
     * by whoever pressed the button.
     *
     * Declared on both roles rather than defaulted, for the reason [onNetworkChanged] gives.
     */
    fun stepSong(by: Int)

    /**
     * Stops the audio without stopping the session, or starts it again.
     *
     * Separate from [stop] because they are different questions: stopping releases the output, the
     * sockets and the foreground service, and coming back from it means every handset in the room
     * pressing something. A pause holds all of that open.
     *
     * Declared on both roles rather than defaulted, for the reason [onNetworkChanged] gives.
     */
    fun setPaused(paused: Boolean)

    /** Whether [setPaused] is in force. False on a role that has nothing to pause. */
    fun paused(): Boolean = false

    /**
     * How far into the song the room is, or null when nothing can say.
     *
     * Only a host can answer: it is the handset holding the source, and a sink is handed instants
     * rather than positions. Null on a sink, and on a host whose source has no length to measure
     * against - a container that does not say how long it is cannot be drawn as a slider.
     */
    fun playhead(): Playhead?

    /**
     * What the room is playing, by name, or null when nothing has said.
     *
     * Both halves can answer this one, which is the difference between it and [playhead]: the host
     * knows because it opened the list, and a sink knows because it was told. Null on a source that
     * has no names - a capture of another app, the ruler's own runs - and on a sink whose host is
     * an older build, which sends nothing and is not an error.
     */
    fun nowPlaying(): String? = null

    /**
     * Told by the service that owns the audio focus, not asked for by the session.
     *
     * A session that lost the focus stays wired up - its clock keeps exchanging, its link keeps
     * reading - and only stops putting frames on the output. That is what makes the resumption
     * land on the shared timeline instead of re-converging from nothing, and it is why this is a
     * notification rather than a stop and a fresh start.
     */
    fun onAudioFocusChanged(hasFocus: Boolean)

    /**
     * Told by the service that the device moved to a different network, or kept one and changed
     * address on it.
     *
     * Section 11.2 asks for the change to be detected and acted on rather than waited out, and
     * what waiting it out would cost is the whole reason: the peer's address is stale from the
     * instant the switch happens, and the only thing that notices otherwise is a socket timeout
     * measured in tens of seconds.
     *
     * Declared on both roles rather than defaulted, so that a role which does nothing here says so
     * where a reader is looking.
     */
    fun onNetworkChanged()
}
