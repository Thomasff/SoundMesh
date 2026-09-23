package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.ChunkCodec

/**
 * Where one round's sound goes out: started once, fed chunks stamped with host instants, finished.
 *
 * An interface because the round is one copy for a handset and a desktop and the output is not:
 * a handset plays through its scheduler and renderer, a desktop straight onto a frame timeline.
 * Everything that decides what is played and when stays in [PeerCalibrationRunner].
 */
interface RoundSpeaker {
    /** Starts playing, on a thread of its own, until [endAtHostNanos]; returns once it has. */
    fun start(endAtHostNanos: Long)

    fun submit(chunk: AudioChunk)

    /** Ends the playing now rather than at the instant [start] was given. */
    fun stopNow()

    /** Waits for the end and hands back the output's own report as JSON, or null if it has none. */
    fun finish(): String?

    /**
     * Why this round's sound cannot be trusted, or null.
     *
     * A chirp that went out late has not failed to play - it played, at the wrong instant - and
     * a recording of it reads as a confident wrong answer. An output that can tell says so here,
     * and the round refuses rather than reading it. A handset's renderer cannot tell, and says null.
     */
    val failure: String? get() = null
}

/** Where one round's recording comes from: `calibration.wav` under the round's run directory. */
interface RoundRecorder {
    /** The host instant the recording opened at, once [record] has; a search hint, never a reading. */
    val startedAtHostNanos: Long?

    /** Records for [seconds], or until [stopped] answers true. */
    fun record(seconds: Int, stopped: () -> Boolean = { false })

    /** Deletes what [record] wrote, for a caller that has finished reading it. */
    fun discardRecording()
}

/**
 * The chunk grid a round plays on, which the handset renderer used to be the only owner of.
 *
 * Values unchanged: SyncRenderer's own constants are these same numbers, and the handset's
 * renderer reads [CHIRP_REPEAT_STRIDE] from here so the two cannot be set apart.
 */
object RoundChunks {
    const val FRAMES_PER_CHUNK = ChunkCodec.FRAMES_PER_CHUNK
    const val CHUNK_NANOS = FRAMES_PER_CHUNK * 1_000_000_000L / ChirpGenerator.SAMPLE_RATE
    const val CHIRP_SEQUENCE_BASE = ChunkCodec.CHIRP_SEQUENCE_BASE

    /**
     * Sequences per chirp repeat. A run that plays the chirp several times inside one clock
     * session gives repeat n the band starting at [CHIRP_SEQUENCE_BASE] + n * this, which is
     * how the renderer keeps the reported chirp window on the first repeat alone. Far wider than
     * the six chunks a sweep occupies, so the bands cannot run into each other.
     */
    const val CHIRP_REPEAT_STRIDE = 1_000
}
