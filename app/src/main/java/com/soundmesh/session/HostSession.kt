package com.soundmesh.session

import android.util.Log
import com.soundmesh.core.AudioChunk
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.SessionState
import com.soundmesh.probe.sync.ChunkServer
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.SyncActivity
import com.soundmesh.probe.sync.SyncRenderer

/**
 * The handset that holds the timeline: it decides when every chunk is heard and plays its own copy
 * alongside the sinks.
 *
 * Host time is this device's own `nanoTime`, so there is nothing to converge and no offset to
 * apply. That is the whole of the asymmetry between the two roles - everything else here has a
 * mirror in [SinkSession].
 *
 * [readChunk] supplies one chunk of PCM per call and is asked for the next one only when the
 * timeline is ready for it, so a source may block in it for as long as a chunk lasts without
 * costing anything.
 */
class HostSession(
    private val readChunk: () -> ByteArray,
    /**
     * How far the drift loop lets the write position wander before it edits the waveform.
     *
     * Settable because the default sits below a quantity that is known to exist: the 48-frame
     * deadband is 1 ms, and one handset's emission moves in steps of about 56 frames (1.17 ms).
     * A step larger than the deadband forces a trim every time it happens, and a trim deletes
     * audio - the archived runs average 68 frames, 1.4 ms, per event. Whether that is what a
     * listener still hears is a question for a wider deadband and a pair of ears, not for more
     * reading of this comment.
     */
    deadbandFrames: Int = DriftController.DEFAULT_DEADBAND_FRAMES,
    /**
     * The renderer's trim band, and with it the scheduler's early release.
     *
     * A different knob from [deadbandFrames], which was tried first and changed nothing: the drift
     * loop's deadband governs how far the write position may wander before the loop corrects it,
     * while this governs whether a release that is already off gets the waveform cut. The counters
     * that separate them are trims and silence writes, and only this one moves either.
     */
    trimFrames: Int = SyncRenderer.TRIM_DEADBAND_FRAMES,
    private val flags: SessionFlags = SessionFlags()
) : SyncSession {
    private val clockServer = ClockSyncServer(SyncActivity.CLOCK_PORT)
    private val chunkServer = ChunkServer(SyncActivity.CHUNK_PORT)
    private val scheduler = PlaybackScheduler(
        SyncRenderer.FRAMES_PER_CHUNK,
        SCHEDULER_CAPACITY_CHUNKS,
        earlyReleaseNanos = SyncRenderer.earlyReleaseNanos(trimFrames)
        // exactReleaseFromSequence deliberately left unset: it exists to release calibration chirps
        // without the trim tolerance, and a product session emits no chirps. Passing the harness's
        // value would arm it on ordinary audio after CHIRP_SEQUENCE_BASE chunks - about five and a
        // half hours of continuous playback, which a session can reach.
    )
    private val renderer = SyncRenderer(
        scheduler,
        DriftController(deadbandFrames),
        trimDeadbandFrames = trimFrames,
        hostNanosNow = { System.nanoTime() }
    )
    private var rendererThread: Thread? = null
    private var producerThread: Thread? = null

    override fun state(): SessionState = flags.state()

    // Null before start(): the renderer exists but has never run, and a report of zeroes reads
    // like a session that played nothing rather than one that has not begun.
    override fun report(): String? = if (flags.state() == SessionState.IDLE) null else renderer.report(null)

    override fun onAudioFocusChanged(hasFocus: Boolean) = flags.setAudioFocus(hasFocus)

    override fun start() {
        clockServer.start()
        chunkServer.start()
        // No end instant is known, so the renderer is given one it cannot reach and stop() moves it
        // back to now. endAt is @Volatile precisely so it can be moved from another thread.
        renderer.endAt(Long.MAX_VALUE)
        flags.markStarted()
        rendererThread = Thread({ renderer.run() }, "SoundMeshHostRender").also { it.start() }
        producerThread = Thread(::produce, "SoundMeshHostSource").also { it.start() }
    }

    /**
     * Generates the shared timeline, one chunk at a time.
     *
     * The chunk goes out on the wire whether or not this handset may emit it. A host that lost the
     * audio focus is silent here and only here: the sinks are on their own outputs, their own focus
     * is their own business, and stopping the broadcast would silence a room because one phone rang.
     */
    private fun produce() {
        try {
            generate()
        } catch (error: Throwable) {
            // An uncaught throw on any of a session's threads takes the whole process with it,
            // which on hardware looks like the app vanishing rather than like a session ending.
            // Marked stopped rather than merely logged: the timeline has no producer any more, and
            // a state that still said PLAYING would be a lie a person acts on.
            Log.e(LOG_TAG, "the host's source stopped", error)
            flags.markStopped()
        }
    }

    private fun generate() {
        var sequence = 0
        var frameIndex = 0L
        val startNanos = System.nanoTime()
        while (!flags.isStopped()) {
            val pcm = readChunk()
            val chunk = AudioChunk(sequence, System.nanoTime() + LEAD_NANOS, pcm)
            chunkServer.broadcast(chunk)
            if (flags.state().mayEmit) scheduler.submit(chunk)
            sequence++
            frameIndex += SyncRenderer.FRAMES_PER_CHUNK
            // Paced against the session's own start rather than the previous pass, so a slow pass
            // is absorbed instead of pushing every later chunk out by the same amount.
            val sleepNanos = startNanos + frameIndex * 1_000_000_000L / SyncRenderer.SAMPLE_RATE - System.nanoTime()
            if (sleepNanos > 0) Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
        }
    }

    override fun stop() {
        flags.markStopped()
        renderer.endAt(System.nanoTime())
        producerThread?.join(JOIN_TIMEOUT_MILLIS)
        rendererThread?.join(JOIN_TIMEOUT_MILLIS)
        producerThread = null
        rendererThread = null
        runCatching { chunkServer.stop() }
        runCatching { clockServer.stop() }
    }

    private companion object {
        /** playAtHostNanos = generation instant + this lead. The harness's own value. */
        const val LEAD_NANOS = 1_500_000_000L

        /** ~3s of audio at 20ms/chunk. */
        const val SCHEDULER_CAPACITY_CHUNKS = 150

        const val JOIN_TIMEOUT_MILLIS = 5_000L

        const val LOG_TAG = "SoundMeshSession"
    }
}
