package com.soundmesh.session

import android.util.Log
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.SessionState
import com.soundmesh.probe.sync.ChunkClient
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.SyncActivity
import com.soundmesh.probe.sync.SyncRenderer
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The handset that follows: it converts the host's instants into its own clock and plays what
 * arrives at the instant it was told to.
 *
 * Every chunk carries the host instant it must be heard at, so nothing here decides when to play.
 * That is what makes an interruption survivable: a session that comes back plays whatever is due
 * now, and the chunks that were due during the interruption are simply past. The design's section
 * 11.2 states the rule the other way round - never resume from where playback paused - and this is
 * the shape that makes obeying it the only thing the code can do.
 *
 * [peerId] names the host, and the alignment correction measured for that host is what makes this
 * handset's output land on the other's. A peer nobody has calibrated yet plays with no correction,
 * which is what the harness measures in order to produce one.
 */
class SinkSession(
    private val hostAddress: String,
    private val chunkPort: Int,
    private val peerId: String,
    private val calibrationDirectory: File,
    private val flags: SessionFlags = SessionFlags()
) : SyncSession {
    private val estimator = ClockOffsetEstimator()
    private val clockClient = ClockSyncClient(hostAddress, SyncActivity.CLOCK_PORT, estimator)

    // currentEstimate() is cleared back to null by any cycle whose fit is rejected, and an
    // ill-conditioned window is not rare on a busy link. Falling back to no offset there would
    // replace host time with this handset's raw nanoTime - wrong by however far apart the two
    // phones were last booted. The last estimate that did succeed is kept and used instead.
    private val cachedEstimate = AtomicReference<ClockEstimate?>(null)

    /** When the most recent chunk arrived, in local time, for the link watchdog below. */
    private val lastArrivalNanos = AtomicLong(0L)

    private val alignmentOffsetNanos =
        (StoredCalibration(calibrationDirectory, peerId).read()?.micros ?: 0L) * 1_000L

    private val scheduler = PlaybackScheduler(
        SyncRenderer.FRAMES_PER_CHUNK,
        SCHEDULER_CAPACITY_CHUNKS,
        earlyReleaseNanos = SyncRenderer.EARLY_RELEASE_NANOS
    )
    private val renderer = SyncRenderer(
        scheduler,
        DriftController(),
        offsetNanosNow = { latestEstimate()?.offsetNanos ?: 0L },
        hostNanosNow = ::hostNanosNow
    )
    private val chunkClient = ChunkClient(hostAddress, chunkPort) { chunk ->
        lastArrivalNanos.set(System.nanoTime())
        flags.setLinkUp(true)
        if (flags.state().mayEmit) scheduler.submit(chunk)
    }

    private var clockThread: Thread? = null
    private var rendererThread: Thread? = null
    private var watchdogThread: Thread? = null
    private var connectThread: Thread? = null

    override fun state(): SessionState = flags.state()

    override fun onAudioFocusChanged(hasFocus: Boolean) = flags.setAudioFocus(hasFocus)

    private fun latestEstimate(): ClockEstimate? {
        val fresh = clockClient.currentEstimate()
        if (fresh != null) cachedEstimate.set(fresh)
        return fresh ?: cachedEstimate.get()
    }

    /**
     * This handset's clock, expressed on the host's timeline and corrected by the stored alignment.
     *
     * Throws rather than falling back to an uncorrected reading: with no estimate having ever
     * succeeded there is no host time on this device at all, and inventing one plays a whole
     * session at the wrong instant while every counter reads healthy.
     */
    private fun hostNanosNow(): Long {
        val estimate = latestEstimate() ?: throw ClockOffsetUnavailable()
        return System.nanoTime() + estimate.offsetNanos - alignmentOffsetNanos
    }

    override fun start() {
        flags.markStarted()
        flags.setClockConverged(false)
        flags.setLinkUp(false)
        clockThread = guarded("SoundMeshSinkClock") { clockClient.runFor(FOREVER_SECONDS, CLOCK_INTERVAL_MILLIS) }
        watchdogThread = guarded("SoundMeshSinkWatch", ::watch)
        // The renderer converts through hostNanosNow, which throws until the estimator has a fit,
        // so it is only started once one exists.
        rendererThread = guarded("SoundMeshSinkRender", ::renderOnceConverged)
        connectThread = guarded("SoundMeshSinkConnect", ::connect)
    }

    /**
     * A started thread whose failure ends the session instead of the process.
     *
     * An uncaught throw on any thread takes the whole app down, which from the room looks like the
     * app vanishing rather than like a session ending. One refused connection did exactly that on
     * hardware. Marked stopped rather than merely logged, because a session missing one of these
     * threads is over whether or not anything says so.
     */
    private fun guarded(name: String, body: () -> Unit): Thread = Thread({
        try {
            body()
        } catch (error: Throwable) {
            // stop() interrupts the clock thread, so an already-stopped session unwinding through
            // here is the ordinary ending rather than a fault worth a line in the log.
            if (flags.isStopped()) return@Thread
            Log.e(LOG_TAG, "$name stopped", error)
            flags.markStopped()
        }
    }, name).also { it.start() }

    /**
     * Dials the host until it answers.
     *
     * One attempt is not enough, and the reason is not network trouble: a host opens its file
     * before it binds anything, and decoding takes as long as it takes. Whoever starts the two
     * handsets has no way to know when that finished, so the sink's first connection has to be
     * allowed to arrive early. It refused once on hardware, and a refusal on the starting thread
     * took the whole process down with it.
     *
     * Retrying only covers the first connection. A link that came up and then went away is a
     * different problem with a different answer, and the state a person sees says RECOVERING for
     * both because both are, from the room's point of view, the same silence.
     */
    private fun connect() {
        while (!flags.isStopped()) {
            if (runCatching { chunkClient.start() }.isSuccess) break
            Thread.sleep(CONNECT_RETRY_MILLIS)
        }
        // A stop that landed between the test above and the connect itself leaves a socket nobody
        // will close otherwise.
        if (flags.isStopped()) runCatching { chunkClient.stop() }
    }

    /**
     * Keeps [SessionFlags] told what is true, at a cadence fast enough for the state a person sees.
     *
     * Convergence and the link are polled rather than pushed because neither has an event to push:
     * the estimator publishes a value once per exchange cycle, and a TCP stream that stopped
     * carrying chunks looks exactly like one carrying silence until enough time has passed. The
     * harness reads the same threshold the same way.
     */
    private fun watch() {
        while (!flags.isStopped()) {
            flags.setClockConverged(latestEstimate() != null)
            val last = lastArrivalNanos.get()
            if (last != 0L) flags.setLinkUp(System.nanoTime() - last < IDLE_THRESHOLD_NANOS)
            Thread.sleep(WATCH_INTERVAL_MILLIS)
        }
    }

    private fun renderOnceConverged() {
        while (!flags.isStopped() && latestEstimate() == null) {
            Thread.sleep(WATCH_INTERVAL_MILLIS)
        }
        if (flags.isStopped()) return
        renderer.endAt(Long.MAX_VALUE)
        renderer.run()
    }

    override fun stop() {
        flags.markStopped()
        // Moved to an instant the renderer's own clock has already passed. Reading host time here
        // is safe: the renderer only runs once an estimate exists, and stopping before that leaves
        // the renderer's own wait loop to end it instead.
        runCatching { renderer.endAt(hostNanosNow()) }
        runCatching { chunkClient.stop() }
        // runFor sleeps between exchanges and has no stop of its own; the interrupt lands in that
        // sleep and unwinds through the socket's own close.
        clockThread?.interrupt()
        clockThread?.join(JOIN_TIMEOUT_MILLIS)
        connectThread?.join(JOIN_TIMEOUT_MILLIS)
        watchdogThread?.join(JOIN_TIMEOUT_MILLIS)
        rendererThread?.join(JOIN_TIMEOUT_MILLIS)
        clockThread = null
        connectThread = null
        watchdogThread = null
        rendererThread = null
    }

    private companion object {
        /** ~3s of audio at 20ms/chunk. */
        const val SCHEDULER_CAPACITY_CHUNKS = 150

        /** The design's clock cadence: 1 ppm of drift moves 2 microseconds across it. */
        const val CLOCK_INTERVAL_MILLIS = 2000L

        /** No new chunk for this long means the host has stopped sending. The harness's value. */
        const val IDLE_THRESHOLD_NANOS = 800_000_000L

        const val WATCH_INTERVAL_MILLIS = 200L

        /** Between attempts on a host that is not listening yet. Its decode is the thing waited on. */
        const val CONNECT_RETRY_MILLIS = 500L

        /** A duration no session reaches, in place of a deadline the product does not have. */
        const val FOREVER_SECONDS = 365 * 24 * 3600

        const val JOIN_TIMEOUT_MILLIS = 5_000L

        const val LOG_TAG = "SoundMeshSession"
    }
}

/**
 * No clock offset estimate has ever succeeded, so this device has no host time to convert to.
 * Thrown rather than answered with zero, which reads as an ordinary session while every instant on
 * it is wrong by the two handsets' boot times apart.
 */
class ClockOffsetUnavailable : IllegalStateException("no clock offset estimate has ever succeeded")
