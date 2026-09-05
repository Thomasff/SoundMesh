package com.soundmesh.session

import android.util.Log
import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkTimeline
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.SessionState
import com.soundmesh.probe.PlaybackUsage
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
     * How far the drift loop lets the write position wander before it corrects it.
     *
     * Settable, and known not to be the knob that matters here. It was the first guess at the
     * clicks - the 48-frame default is 1 ms and one handset's emission moves in ~56-frame steps,
     * so a step would cross it every time - and the guess was wrong. Widening it to 96 frames on a
     * product session moved the trim rate from 3.30 a second to 3.32. What this governs is when
     * the loop corrects; what decides whether the waveform gets cut is [trimFrames].
     */
    deadbandFrames: Int = DriftController.DEFAULT_DEADBAND_FRAMES,
    /**
     * The renderer's trim band, and with it the scheduler's early release.
     *
     * The knob that does move the clicks - see [PRODUCT_TRIM_FRAMES] for the measurement and for
     * why the product's default is not the renderer's own. Settable so a run can go back to the
     * archive's 48 without a rebuild.
     */
    trimFrames: Int = PRODUCT_TRIM_FRAMES,
    /**
     * Which output this handset's own copy goes to. Defaults to media, the archive's attribution.
     *
     * A capturing host has to be something else: capture reads a stream whose media volume is at
     * zero, and a media-usage output is muted along with it. See [SyncRenderer]'s own note.
     */
    playbackUsage: PlaybackUsage = PlaybackUsage.MEDIA,
    /**
     * How far ahead of the media output this handset's chosen output comes out - see
     * [com.soundmesh.probe.sync.StoredOutputLead] for the measurement.
     *
     * Applied to the renderer's clock rather than to the timeline, and that distinction is the
     * whole reason it is safe. The chunks this host stamps, the clock its sinks converge on, and
     * the standing peer correction they apply are all left exactly where they were; only this
     * handset's own view of when a chunk is due moves, so its own speaker fires later by this much
     * and nothing a sink sees changes at all.
     *
     * Zero on the media output and zero on a handset nobody has measured.
     */
    private val outputLeadNanos: Long = 0L,
    /**
     * Released when the session stops, for a source that holds something a file does not.
     *
     * [readChunk] alone cannot do it: it is called until the session ends, so there is no last
     * call to close on. A capture source holds an AudioRecord and a MediaProjection callback, and
     * a session that ended without releasing them leaves the phone recording.
     */
    private val closeSource: () -> Unit = {},
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
        playbackUsage = playbackUsage,
        hostNanosNow = { System.nanoTime() - outputLeadNanos }
    )
    // How long the slowest call to broadcast took, and how many chunks the source produced.
    //
    // Instrumentation rather than a health counter, and it earned its place: it is what measured
    // the defect that produced it. A socket whose peer vanished without closing does not fail a
    // write - the send buffer fills and the write blocks - and broadcast used to write on this
    // thread, which is also the thread that feeds this handset's own output. Twenty-seven seconds
    // in one call, on hardware, with both handsets silent for all of it. What this reads now is
    // the enqueue, and it stays because a number that goes back up says the queue stopped working.
    @Volatile private var maxBroadcastNanos = 0L
    @Volatile private var generated = 0

    private var rendererThread: Thread? = null
    private var producerThread: Thread? = null

    override fun state(): SessionState = flags.state()

    // Null before start(): the renderer exists but has never run, and a report of zeroes reads
    // like a session that played nothing rather than one that has not begun.
    override fun report(): String? {
        if (flags.state() == SessionState.IDLE) return null
        return renderer.report(null).dropLast(1) +
            ",\"maxBroadcastNanos\":$maxBroadcastNanos,\"generated\":$generated" +
            ",\"droppedToSinks\":${chunkServer.droppedChunks()}}"
    }

    override fun onAudioFocusChanged(hasFocus: Boolean) = flags.setAudioFocus(hasFocus)

    /**
     * Nothing. A host dials nobody: it binds every interface and waits, so an address that moved
     * under it costs it no connection of its own.
     *
     * The one thing a moved host does owe its sinks is a corrected mDNS record, and that belongs
     * to whoever registered it - [SessionService], which holds the Context the registration needs
     * and re-registers on the same signal that reaches here.
     */
    override fun onNetworkChanged() = Unit

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
        // The instant a chunk is due comes from here rather than from the clock, because once the
        // source is a capture the two are not the same thing: readChunk blocks until the recorder
        // has audio and hands it over on the recorder's own cadence, and stamping chunks with the
        // moment they happened to arrive put that cadence into the timeline for both handsets to
        // reproduce faithfully. See [ChunkTimeline] for what it cost and how it was measured.
        val timeline = ChunkTimeline(SyncRenderer.FRAMES_PER_CHUNK, SyncRenderer.SAMPLE_RATE)
        while (!flags.isStopped()) {
            val pcm = readChunk()
            val chunk = AudioChunk(sequence, timeline.accept(System.nanoTime()) + LEAD_NANOS, pcm)
            val startedBroadcastAt = System.nanoTime()
            chunkServer.broadcast(chunk)
            maxBroadcastNanos = maxOf(maxBroadcastNanos, System.nanoTime() - startedBroadcastAt)
            if (flags.state().mayEmit) scheduler.submit(chunk)
            generated = sequence + 1
            sequence++
            // Paced against the session's own start rather than the previous pass, so a slow pass
            // is absorbed instead of pushing every later chunk out by the same amount. Against the
            // anchored timeline rather than a fixed grid, so a recorder running fast is not held
            // below its own rate until its buffer overruns; a source that answers instantly holds
            // the anchor at zero and is paced by the grid exactly as before.
            val sleepNanos = (timeline.nextDueNanos() ?: System.nanoTime()) - System.nanoTime()
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
        runCatching { closeSource() }
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
