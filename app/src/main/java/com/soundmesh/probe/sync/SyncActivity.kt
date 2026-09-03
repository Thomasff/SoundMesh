package com.soundmesh.probe.sync

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.REACQUIRE_THRESHOLD_FRAMES
import com.soundmesh.core.RendererPhase
import com.soundmesh.core.SchedulerStatsWindow
import com.soundmesh.core.TonePcmSource
import com.soundmesh.core.schedulerStatsWindow
import com.soundmesh.probe.ProbeCase
import com.soundmesh.probe.RunStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * ADB driven harness for the M1 and M2 gates. Not product code: the product will discover
 * peers and manage sessions itself, while this takes both from intent extras.
 *
 * `mode=CLOCK_ONLY` runs just the UDP clock exchange (the M1 gate). `mode=FULL` additionally
 * streams a shared audio timeline over TCP and closes with a calibration chirp (the M2 gate).
 */
class SyncActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var runStore: RunStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this)
        setContentView(statusView)
        runStore = RunStore(filesDir)
        // KEEP_SCREEN_ON only holds a screen that is already on; `am start` does not wake a sleeping
        // device, so three runs were launched onto handsets reporting Asleep and Dozing. They still
        // made sound - the audio thread keeps producing at a degraded priority - but the timing this
        // whole harness measures is exactly what the doze scheduler takes away: one missed the chirp
        // deadline outright and two recorded chirps the correlator could not locate at all. Turning
        // the screen on here makes a launch self-sufficient; run-sync also refuses to start against a
        // sleeping device, so the two together fail loudly instead of producing plausible garbage.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        handle()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle()
    }

    private fun handle() {
        val caseId = intent.getStringExtra(ProbeCase.EXTRA_CASE_ID)
        val role = intent.getStringExtra("role")
        val seconds = intent.getIntExtra("seconds", -1)
        val mode = intent.getStringExtra("mode")
        if (caseId == null || !ProbeCase.isSafeCaseId(caseId) || role !in setOf("HOST", "SINK") ||
            seconds !in 10..900 || mode == null || mode !in setOf("CLOCK_ONLY", "FULL")
        ) {
            statusView.text = "REJECTED"
            return
        }
        statusView.text = "$role RUNNING"
        Thread {
            // Both branches write their own sync.json on success (CLOCK_ONLY inline below, FULL
            // inside runHostFull/runSinkFull); this thread only needs to cover failures.
            val failure = runCatching {
                if (role == "HOST") runHost(mode, caseId, seconds) else runSink(mode, caseId, seconds)
            }.exceptionOrNull()
            // A missing clock offset gets a named code rather than an exception class name: it is
            // the one failure that used to be silently absorbed as an offset of zero.
            val failureCode = when (failure) {
                null -> null
                is ClockOffsetUnavailable -> "CLOCK_OFFSET_UNAVAILABLE"
                else -> failure.javaClass.simpleName
            }
            if (failureCode != null) {
                runStore.writeSyncJson(caseId, "{\"schemaVersion\":1,\"role\":\"$role\",\"failureCode\":\"$failureCode\"}")
            }
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                statusView.text = "$role DONE"
            }
        }.start()
    }

    private fun runHost(mode: String, caseId: String, seconds: Int) {
        if (mode == "CLOCK_ONLY") {
            val server = ClockSyncServer(CLOCK_PORT)
            server.start()
            Thread.sleep(seconds * 1000L)
            server.stop()
            runStore.writeSyncJson(caseId, "{\"schemaVersion\":1,\"role\":\"HOST\",\"failureCode\":null}")
        } else {
            runHostFull(caseId, seconds)
        }
    }

    private fun runSink(mode: String, caseId: String, seconds: Int) {
        val address = intent.getStringExtra("host_address")
            ?: throw IllegalArgumentException("SINK needs host_address")
        if (mode == "CLOCK_ONLY") {
            val client = ClockSyncClient(address, CLOCK_PORT, ClockOffsetEstimator())
            val history = client.runFor(seconds)
            runStore.writeSyncJson(
                caseId,
                "{\"schemaVersion\":1,\"role\":\"SINK\",\"failureCode\":null,\"estimates\":${estimatesJson(history)}}"
            )
        } else {
            runSinkFull(address, caseId, seconds)
        }
    }

    // ---- FULL mode ----
    //
    // Host is the clock and the source of truth: it answers UDP clock requests, streams
    // TonePcmSource chunks over TCP timestamped 1.5s into its own future, and plays that same
    // audio locally off its own scheduler. The sink turns its measured offset into a
    // hostNanosNow() and feeds every chunk it receives to an identical scheduler/renderer pair,
    // so both devices are pulling from the same shared timeline, just converted differently.
    //
    // After the audio segment, the sink plays the calibration chirp at a host instant and the
    // host plays the same chirp 500ms later while recording the room throughout, so a single
    // WAV on the host carries both chirps for a PC script to cross-correlate. Neither chirp goes
    // on the wire: each device generates the identical sweep locally and submits it to its own
    // scheduler, so it is heard through the very pipeline the measurement is meant to judge.

    /**
     * Whether this run asked the AudioTrack for the low latency output path, defaulting to off -
     * the path every M1/M2 measurement so far was taken on. Read straight off the intent, like
     * `host_address`: the two paths moved the measured residual from -34.7ms to +90ms, so the
     * choice belongs to the run rather than to the build, and SyncRenderer writes it back into
     * sync.json so a stored artifact says which path produced it.
     */
    private fun lowLatencyRequested(): Boolean = intent.getBooleanExtra("low_latency", false)

    /**
     * The TRACKING -> ACQUIRING fallback threshold this run asks for, defaulting to the production
     * [REACQUIRE_THRESHOLD_FRAMES].
     *
     * Read off the intent for one reason: the production threshold sits far above the drift-sample
     * noise floor, so it fires only on a real slip, and across eight two-handset runs it fired
     * exactly zero times. Eight clean runs with `reacquisitions == 0` say the fallback was never
     * executed - not that it works. Lowering the threshold below the noise floor makes ordinary
     * jitter trip it, which is the only way to watch the transition actually run on a handset.
     * A non-positive value would make every sample a slip and pin the loop in ACQUIRING forever,
     * so it falls back to the default rather than being honoured.
     */
    /**
     * Which capture path this run asks the calibration recording to open, defaulting to the MIC
     * every measurement so far was taken on.
     *
     * Read off the intent for the same reason as the other two overrides: this is an A/B against a
     * measured baseline, so both arms have to be reachable from one build. MIC runs the vendor's
     * noise suppression and beamforming, whose group delay moves while the adaptive stages settle
     * and whose beam is direction-dependent - and both chirps land inside the first one and a half
     * seconds of the recording, so neither the settling nor the steering cancels in the difference.
     * That makes it a candidate for the run-to-run scatter, the placement sensitivity and the
     * device residual at once, and none of the three can be tested without being able to turn it
     * off. See [CalibrationAudioSource].
     */
    private fun calibrationAudioSourceRequested(): CalibrationAudioSource =
        CalibrationAudioSource.parse(intent.getStringExtra("audio_source"))

    /**
     * Whether the sink records the room as well, defaulting to off - the one-recording arrangement
     * every measurement so far was taken on.
     *
     * With both handsets recording, the same chirp pair is heard twice, and the air between them
     * enters the two readings with opposite signs: each hears its own chirp across a few
     * centimetres and its partner's across the room. The half sum of the two readings is then the
     * alignment error with no flight time in it at all, and the half difference is the flight time
     * - so the separation stops being a number a person has to measure and hand in, and becomes a
     * result. Each side's input latency cancels inside its own recording, so neither has to be
     * known either.
     *
     * It also splits the emission jitter from the capture jitter without inferring either: a chirp
     * leaving a speaker late shifts both readings the same way and so lands entirely in the half
     * sum, while anything on a capture path shifts one reading only and cannot.
     *
     * Off by default and not folded into the normal run, because opening a capture path on the
     * sink is a change to the very device under measurement - a handset may route or clock its
     * output differently while its microphone is live. Keeping it opt-in leaves the stored
     * baseline reproducible and makes the two arrangements an A/B rather than a replacement.
     */
    private fun sinkRecordsRequested(): Boolean = intent.getBooleanExtra("sink_records", false)

    /**
     * How many chirp pairs this run plays, and how far apart, defaulting to the single pair every
     * measurement so far was taken on.
     *
     * Repeating the chirp inside ONE clock session is the only way to tell a per-session residual
     * from a per-emission one. Every pair in a run is scheduled against the same clock offset
     * estimate, so a residual coming from that estimate shifts them all together and cancels out of
     * their spread, while jitter in the playout path is redrawn for each pair and does not. Pairs
     * agreeing far better than separate runs do puts the run-to-run scatter in the clock layer;
     * pairs scattering just as widely puts it in the playout path.
     *
     * Non-positive values fall back to the default rather than being honoured, on the same terms as
     * the reacquire threshold: a zero repeat count would leave the recording with nothing to
     * correlate, and the PC side already refuses an interval too short to hold one pair per slice.
     */
    /**
     * The drift loop's deadband for this run, defaulting to DriftController's production
     * [DriftController.DEFAULT_DEADBAND_FRAMES].
     *
     * Read off the intent for the same reason as the reacquire threshold: it is the leading
     * explanation for the per-emission scatter and cannot be tested without being changeable from
     * one build. The loop corrects nothing inside the band, so an emission lands wherever the
     * uncorrected error happens to sit - a uniform draw across the 48 frame default has a standard
     * deviation of 0.577 ms, against the 0.510 ms measured across twelve two-chirp sessions. If
     * that is the mechanism, the scatter shrinks with the band.
     *
     * Non-positive values fall back rather than being honoured; the PC side already refuses
     * anything under the loop's own measurement noise floor.
     */
    private fun deadbandFramesRequested(): Int {
        val requested = intent.getIntExtra("deadband_frames", DriftController.DEFAULT_DEADBAND_FRAMES)
        return if (requested > 0) requested else DriftController.DEFAULT_DEADBAND_FRAMES
    }

    private fun chirpRepeatsRequested(): Int {
        val requested = intent.getIntExtra("chirp_repeats", 1)
        return if (requested > 0) requested else 1
    }

    private fun chirpIntervalNanosRequested(): Long {
        val requested = intent.getIntExtra("chirp_interval_seconds", 0)
        return if (requested > 0) requested * 1_000_000_000L else 0L
    }

    private fun reacquireThresholdRequested(): Int {
        val requested = intent.getIntExtra("reacquire_threshold_frames", REACQUIRE_THRESHOLD_FRAMES)
        return if (requested > 0) requested else REACQUIRE_THRESHOLD_FRAMES
    }

    private fun runHostFull(caseId: String, seconds: Int) {
        val clockServer = ClockSyncServer(CLOCK_PORT)
        val chunkServer = ChunkServer(CHUNK_PORT)
        val scheduler = PlaybackScheduler(SyncRenderer.FRAMES_PER_CHUNK, SCHEDULER_CAPACITY_CHUNKS)
        val renderer = SyncRenderer(scheduler, DriftController(deadbandFramesRequested()), lowLatencyRequested(), reacquireThresholdRequested()) { System.nanoTime() }
        val hostNanosNow: () -> Long = { System.nanoTime() }
        try {
            clockServer.start()
            chunkServer.start()

            val source = TonePcmSource()
            val audioStart = System.nanoTime()
            val until = audioStart + seconds * 1_000_000_000L
            var lastPlayAtHostNanos = until

            renderer.endAt(until + CALIBRATION_GAP_NANOS)
            val rendererThread = Thread { renderer.run() }
            rendererThread.start()

            var sequence = 0
            var frameIndex = 0L
            while (System.nanoTime() < until) {
                val pcm = source.fill(frameIndex, SyncRenderer.FRAMES_PER_CHUNK)
                val playAt = System.nanoTime() + LEAD_NANOS
                val chunk = AudioChunk(sequence, playAt, pcm)
                lastPlayAtHostNanos = playAt
                chunkServer.broadcast(chunk)
                scheduler.submit(chunk)
                sequence++
                frameIndex += SyncRenderer.FRAMES_PER_CHUNK
                val nextAt = audioStart + frameIndex * 1_000_000_000L / SyncRenderer.SAMPLE_RATE
                val sleepNanos = nextAt - System.nanoTime()
                if (sleepNanos > 0) Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
            }

            // Both devices derive the sink's chirp instant from the same wire value - the last
            // chunk's playAtHostNanos - so they agree on it without another message.
            val sinkChirpAt = lastPlayAtHostNanos + CALIBRATION_GAP_NANOS
            val hostChirpAt = sinkChirpAt + STAGGER_NANOS
            val chirpSubmission = submitChirp(scheduler, renderer, hostChirpAt, chirpRepeatsRequested(), chirpIntervalNanosRequested())
            // The renderer started on a provisional bound; only now is the chirp's end known.
            renderer.endAt(chirpSubmission.endHostNanos + CHIRP_DRAIN_NANOS)

            val calibration = CalibrationRunner(runStore, caseId, calibrationAudioSourceRequested())
            awaitHostInstant(sinkChirpAt - RECORD_LEAD_NANOS, hostNanosNow)
            val recordThread = Thread { calibration.record(secondsUntil(chirpSubmission.endHostNanos + RECORD_TAIL_NANOS)) }
            recordThread.start()
            val chirpTiming = awaitChirpStart(hostChirpAt, hostNanosNow)
            rendererThread.join()
            recordThread.join()
            val chirpWindow = chirpWindow(renderer)

            runStore.writeSyncJson(
                caseId,
                "{\"schemaVersion\":1,\"role\":\"HOST\",\"mode\":\"FULL\"," +
                    "\"failureCode\":${topLevelFailureCodeJson(renderer, chirpSubmission, chirpTiming, chirpWindow)}," +
                    // The source that actually opened, not the one asked for: UNPROCESSED is
                    // optional on Android, so a run that requests it may have been recorded on the
                    // fallback and the artifact has to say which path produced the number.
                    "\"audioSource\":${calibration.openedSource?.let { "\"$it\"" } ?: "null"}," +
                    "${chirpTimingJson(chirpTiming)}," +
                    "${chirpScheduleJson()}," +
                    "\"chirpAcquisition\":${chirpAcquisitionJson(chirpSubmission)}," +
                    "\"chirpWindow\":${chirpWindowJson(chirpWindow)}," +
                    "\"renderer\":${renderer.report(renderer.lastStreamingStats()?.silenceFrames)}}"
            )
        } finally {
            chunkServer.stop()
            clockServer.stop()
        }
    }

    private fun runSinkFull(address: String, caseId: String, seconds: Int) {
        val estimator = ClockOffsetEstimator()
        val clockClient = ClockSyncClient(address, CLOCK_PORT, estimator)
        // currentEstimate() returns the estimate from the last exchange cycle, and runFor stores
        // that cycle's result unconditionally - so a cycle whose fit is rejected clears a
        // previously good value back to null. The plausibility guard rejects an ill conditioned
        // window whenever the kept exchanges happen to cluster in time, which a network hiccup
        // makes likely, so this is not rare. Falling back to an offset of zero there would
        // silently replace host time with this device's raw nanoTime(), wrong by however far
        // apart the two phones were last booted - hours, reported as a clean run. The
        // AtomicReference below keeps the last estimate that did succeed and is used instead; it
        // is load-bearing precisely because the client's own cache is allowed to go back to null.
        val cachedEstimate = AtomicReference<ClockEstimate?>(null)
        fun latestEstimate(): ClockEstimate? {
            val fresh = clockClient.currentEstimate()
            if (fresh != null) cachedEstimate.set(fresh)
            return fresh ?: cachedEstimate.get()
        }
        val hostNanosNow: () -> Long = {
            // Never zero: with no estimate ever having succeeded there is no host time at all,
            // and inventing one is exactly the failure this whole path exists to detect.
            val estimate = latestEstimate() ?: throw ClockOffsetUnavailable()
            System.nanoTime() + estimate.offsetNanos
        }

        val scheduler = PlaybackScheduler(SyncRenderer.FRAMES_PER_CHUNK, SCHEDULER_CAPACITY_CHUNKS)
        val renderer = SyncRenderer(scheduler, DriftController(deadbandFramesRequested()), lowLatencyRequested(), reacquireThresholdRequested(), hostNanosNow)
        val lastPlayAt = AtomicLong(0L)
        // The spec requires playback not start before the offset estimate has converged, so
        // nothing is submitted to the scheduler until `converged` is set true below. Anything
        // that arrives before that is not schedulable and must not be counted as a drop, which
        // ruled out just letting the scheduler's own capacity/lateness logic handle it - that
        // would show up as droppedLate/droppedOverflow on an otherwise healthy run.
        val converged = AtomicBoolean(false)
        val chunkClient = ChunkClient(address, CHUNK_PORT) { chunk ->
            if (converged.get()) {
                lastPlayAt.set(chunk.playAtHostNanos)
                scheduler.submit(chunk)
            }
        }

        var history: List<ClockEstimate> = emptyList()
        // runFor stops sending exchanges once its own duration elapses, so bounding it by
        // `seconds` alone would starve the convergence wait below of the very exchanges it is
        // waiting on whenever `seconds` is shorter than convergence can possibly take (the
        // estimator needs 8 samples at a 2s cadence, about 16s) - every such run would then
        // burn the full CONVERGENCE_TIMEOUT_SECONDS regardless of network quality. Running for
        // at least that long keeps convergence possible no matter how short `seconds` is.
        val clockThread = Thread { history = clockClient.runFor(maxOf(seconds, CONVERGENCE_TIMEOUT_SECONDS)) }
        try {
            clockThread.start()
            chunkClient.start()

            // hostNanosNow() is meaningless before the estimator has its first fit: it falls
            // back to a raw, uncorrected nanoTime() that can be off by however long the two
            // devices have been powered on. Clustering exchanges to fill the window faster would
            // make the least-squares fit ill-conditioned (the estimator rejects that as implying
            // impossible drift), so the only correct fix is to wait for a real fit, bounded so a
            // broken link fails loudly rather than hanging or proceeding on a fabricated offset.
            val convergenceStartNanos = System.nanoTime()
            val convergenceDeadline = convergenceStartNanos + CONVERGENCE_TIMEOUT_SECONDS * 1_000_000_000L
            while (latestEstimate() == null && System.nanoTime() < convergenceDeadline) {
                Thread.sleep(50)
            }
            val convergenceWaitNanos = System.nanoTime() - convergenceStartNanos

            if (latestEstimate() == null) {
                runStore.writeSyncJson(
                    caseId,
                    "{\"schemaVersion\":1,\"role\":\"SINK\",\"mode\":\"FULL\",\"failureCode\":\"CLOCK_SYNC_TIMEOUT\"," +
                        "\"convergenceWaitNanos\":$convergenceWaitNanos}"
                )
                return
            }
            converged.set(true)

            renderer.endAt(hostNanosNow() + (seconds + RENDERER_MARGIN_SECONDS) * 1_000_000_000L)
            val rendererThread = Thread { renderer.run() }
            rendererThread.start()

            // The TCP connection stays open through calibration, so a gap in arriving chunks
            // (not a closed stream) is the only signal that the host has stopped broadcasting.
            var lastSeen = lastPlayAt.get()
            var lastChangeNanos = System.nanoTime()
            val idleDeadline = System.nanoTime() + (seconds + RENDERER_MARGIN_SECONDS) * 1_000_000_000L
            while (System.nanoTime() < idleDeadline) {
                Thread.sleep(100)
                val current = lastPlayAt.get()
                if (current != lastSeen) {
                    lastSeen = current
                    lastChangeNanos = System.nanoTime()
                } else if (current > 0 && System.nanoTime() - lastChangeNanos > IDLE_THRESHOLD_NANOS) {
                    break
                }
            }
            // The chirp goes through this device's own scheduler and renderer, so the renderer is
            // now given an exact end - the instant the chirp has been heard - and joined, rather
            // than left idling on the generous bound it started with.
            val observed = lastPlayAt.get()
            val chirpAt = (if (observed > 0) observed else hostNanosNow()) + CALIBRATION_GAP_NANOS
            val chirpSubmission = submitChirp(scheduler, renderer, chirpAt, chirpRepeatsRequested(), chirpIntervalNanosRequested())
            renderer.endAt(chirpSubmission.endHostNanos + CHIRP_DRAIN_NANOS)

            // Deliberately the same two host instants the host opens and closes its own recording
            // on, so the two files cover one window and every pair appears in both. The host's own
            // chirp trails this device's by the stagger, so the tail is measured from there.
            val calibration = if (sinkRecordsRequested()) CalibrationRunner(runStore, caseId, calibrationAudioSourceRequested()) else null
            val recordThread = calibration?.let {
                val untilHostNanos = chirpSubmission.endHostNanos + STAGGER_NANOS + RECORD_TAIL_NANOS
                val fromHostNanos = chirpAt - RECORD_LEAD_NANOS
                awaitHostInstant(fromHostNanos, hostNanosNow)
                Thread { it.record(secondsBetween(fromHostNanos, untilHostNanos)) }.also(Thread::start)
            }

            val chirpTiming = awaitChirpStart(chirpAt, hostNanosNow)
            rendererThread.join()
            recordThread?.join()
            val chirpWindow = chirpWindow(renderer)

            clockThread.join()
            runStore.writeSyncJson(
                caseId,
                "{\"schemaVersion\":1,\"role\":\"SINK\",\"mode\":\"FULL\"," +
                    "\"failureCode\":${topLevelFailureCodeJson(renderer, chirpSubmission, chirpTiming, chirpWindow)}," +
                    // Null on a run that did not ask the sink to record, and on the same terms as
                    // the host otherwise: the source that actually opened, never the one asked for.
                    "\"audioSource\":${calibration?.openedSource?.let { "\"$it\"" } ?: "null"}," +
                    "\"convergenceWaitNanos\":$convergenceWaitNanos,${chirpTimingJson(chirpTiming)}," +
                    "${chirpScheduleJson()}," +
                    "\"chirpAcquisition\":${chirpAcquisitionJson(chirpSubmission)}," +
                    "\"chirpWindow\":${chirpWindowJson(chirpWindow)}," +
                    "\"estimates\":${estimatesJson(history)}," +
                    "\"renderer\":${renderer.report(renderer.lastStreamingStats()?.silenceFrames)}}"
            )
        } finally {
            chunkClient.stop()
        }
    }

    /**
     * Queues the calibration chirp on this device's own scheduler starting at [startHostNanos],
     * and captures the renderer's acquisition/tracking state at that exact instant.
     *
     * Nothing goes on the wire: both devices generate the identical sweep locally and only agree
     * on the instant, so the chunk format and the TCP stream are untouched. What the chirp does
     * pick up on the way out is everything the milestone exists to measure - the scheduler, the
     * getTimestamp based output depth compensation and the drift correction.
     *
     * The renderer state has to be read right here, not later from `renderer.report()` after the
     * chirp has played: the phase moves both ways, so a snapshot taken after the chirp says
     * nothing about where the loop stood at the moment the chirp was actually queued, which is the
     * number that matters.
     */
    private fun submitChirp(
        scheduler: PlaybackScheduler,
        renderer: SyncRenderer,
        startHostNanos: Long,
        repeats: Int,
        intervalNanos: Long
    ): ChirpSubmission {
        val chunks = ChirpGenerator.generateStereoChunks(SyncRenderer.FRAMES_PER_CHUNK)
        // All repeats are queued here, in one go, seconds ahead of the first. The scheduler holds
        // them until each is due, so a later repeat costs nothing but queue space - and queueing
        // them together is what keeps every pair on one submission-time snapshot of the loop.
        for (repeat in 0 until repeats) {
            val repeatStart = startHostNanos + repeat * intervalNanos
            val sequenceBase = SyncRenderer.CHIRP_SEQUENCE_BASE + repeat * SyncRenderer.CHIRP_REPEAT_STRIDE
            chunks.forEachIndexed { index, pcm ->
                scheduler.submit(AudioChunk(sequenceBase + index, repeatStart + index * SyncRenderer.CHUNK_NANOS, pcm))
            }
        }
        return ChirpSubmission(
            // The last repeat's end: it is what the recording and the renderer have to outlive.
            endHostNanos = startHostNanos + (repeats - 1) * intervalNanos + chunks.size * SyncRenderer.CHUNK_NANOS,
            phase = renderer.phase(),
            filteredErrorFrames = renderer.filteredErrorFrames(),
            acquisitionDurationNanos = renderer.acquisitionDurationNanos()
        )
    }

    /**
     * The scheduler counters attributable to the chirp itself, or null if the chirp was never
     * heard.
     *
     * Both boundaries come from the renderer, which is the only thread that sees a chirp chunk
     * leave for the AudioTrack. Snapshotting them here instead - around submission and after the
     * join - would widen the window to the audio tail, the whole calibration gap and the 1s drain,
     * whose several seconds of by-design silence bury the one missing chirp chunk this is meant to
     * catch. Safe to read only after the renderer thread has been joined.
     */
    private fun chirpWindow(renderer: SyncRenderer): SchedulerStatsWindow? {
        val start = renderer.chirpWindowStart() ?: return null
        val end = renderer.chirpWindowEnd() ?: return null
        return schedulerStatsWindow(start, end)
    }

    /** The renderer's acquisition/tracking state at the instant the chirp chunks were submitted. */
    private class ChirpSubmission(
        val endHostNanos: Long,
        val phase: RendererPhase,
        val filteredErrorFrames: Int,
        val acquisitionDurationNanos: Long?
    )

    /** How late the chirp's scheduled start was, measured before the wait for it and again after. */
    private class ChirpTiming(val missedByNanos: Long, val wakeOvershootNanos: Long)

    /**
     * Waits out the chirp's start instant and reports how far host time had already passed it.
     *
     * The chirp is queued on the scheduler seconds in advance, so this only observes. It is
     * measured twice on purpose. Host time on the sink is a moving estimate: if it jumps forward
     * while waiting - which is exactly what a re-fitted or a briefly unavailable offset does -
     * the wait falls straight through and the chirp is already late, yet a check taken only
     * beforehand reports a clean zero and flags nothing.
     */
    private fun awaitChirpStart(startHostNanos: Long, hostNanosNow: () -> Long): ChirpTiming {
        val missedByNanos = (hostNanosNow() - startHostNanos).coerceAtLeast(0L)
        awaitHostInstant(startHostNanos, hostNanosNow)
        val wakeOvershootNanos = (hostNanosNow() - startHostNanos).coerceAtLeast(0L)
        return ChirpTiming(missedByNanos, wakeOvershootNanos)
    }

    /**
     * Waits until [hostNanos], sleeping most of the way and spinning the last stretch.
     *
     * Sleeping the whole way quantises the instant to the millisecond the scheduler can honour,
     * independently on each device, spending one to two milliseconds per side of a five
     * millisecond budget on nothing at all.
     */
    private fun awaitHostInstant(hostNanos: Long, hostNanosNow: () -> Long) {
        val spinFrom = hostNanos - SPIN_GUARD_NANOS
        var remaining = spinFrom - hostNanosNow()
        while (remaining > 0) {
            Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
            remaining = spinFrom - hostNanosNow()
        }
        while (hostNanosNow() < hostNanos) {
            // Spin: the remaining wait is shorter than the sleep granularity being avoided.
        }
    }

    /**
     * The JSON literal for sync.json's top-level failureCode field, combining every source that
     * can invalidate the calibration measurement. Checked in order of severity, first match wins;
     * the nested detail (`renderer`, `chirpAcquisition`, the timing fields) is always written
     * regardless, so nothing is lost by picking one name for the top level.
     *
     * 1. The renderer having thrown ([SyncRenderer.currentFailureCode]) - it is now the only
     *    source of the chirp, so a dead renderer means no chirp reached the recording at all, and
     *    that used to surface as a null failureCode because the renderer's own code was nested
     *    under "renderer" and run-sync.mjs reads only the top level.
     * 2. No chirp chunk having reached the AudioTrack at all ([SyncRenderer.chirpWindowStart] /
     *    [SyncRenderer.chirpWindowEnd] never set). The recording then holds no sweep to correlate
     *    against, and the absence must be named rather than surfaced as an all-zero window that
     *    reads exactly like a clean one.
     * 3. The chirp having been submitted while the renderer was still ACQUIRING - the
     *    median-filtered error had not yet settled into DriftController's deadband when the chirp
     *    was queued, so the measurement rides an unconverged release phase (see
     *    [ChirpSubmission], [submitChirp]) - or having converged earlier but sitting outside the
     *    alignment budget by the time the chirp was queued ([ALIGNMENT_BUDGET_FRAMES]). TRACKING
     *    now reacquires on a wide excursion, but only after two 1Hz samples confirm it, and a
     *    reacquisition still needs seconds to work the error off, so "phase == TRACKING" does not
     *    on its own mean the loop is converged right now and the recorded error still has to be
     *    looked at.
     * 4. The chirp's own deadline timing, as before: being late before the wait is unconditionally
     *    a failure (everything ahead of it is milliseconds coarse, so a genuine miss can never
     *    look like noise); the overshoot after the wait needs a tolerance instead, because the
     *    spin exits a fraction past the instant by construction and the sink's host clock is
     *    re-fitted continuously. A fifth of the five millisecond budget sits far above both and
     *    far below anything that would matter.
     */
    private fun topLevelFailureCodeJson(
        renderer: SyncRenderer,
        chirp: ChirpSubmission,
        timing: ChirpTiming,
        chirpWindow: SchedulerStatsWindow?
    ): String {
        val code = renderer.currentFailureCode()
            ?: if (chirpWindow == null) "CHIRP_WINDOW_UNAVAILABLE" else null
            ?: if (chirp.phase == RendererPhase.ACQUIRING) "CHIRP_SUBMITTED_DURING_ACQUISITION" else null
            ?: if (abs(chirp.filteredErrorFrames) > ALIGNMENT_BUDGET_FRAMES) "ACQUISITION_ERROR_OUT_OF_BUDGET" else null
            ?: if (timing.missedByNanos > 0 || timing.wakeOvershootNanos > CHIRP_WAKE_TOLERANCE_NANOS) "CHIRP_DEADLINE_MISSED" else null
        return code?.let { "\"$it\"" } ?: "null"
    }

    /**
     * What the run actually played, not what it was asked for: a non-positive extra falls back, so
     * the artifact has to carry the honoured values or a repeats run cannot be told from a plain
     * one after the fact. The PC side slices the recording on exactly this interval.
     */
    private fun chirpScheduleJson(): String =
        "\"chirpRepeats\":${chirpRepeatsRequested()},\"chirpIntervalNanos\":${chirpIntervalNanosRequested()}," +
            "\"deadbandFrames\":${deadbandFramesRequested()}"

    private fun chirpTimingJson(timing: ChirpTiming): String =
        "\"chirpMissedByNanos\":${timing.missedByNanos}," +
            "\"chirpWakeOvershootNanos\":${timing.wakeOvershootNanos}"

    private fun chirpAcquisitionJson(chirp: ChirpSubmission): String =
        "{\"phase\":\"${chirp.phase}\",\"filteredErrorFrames\":${chirp.filteredErrorFrames}," +
            "\"acquisitionDurationNanos\":${chirp.acquisitionDurationNanos ?: "null"}}"

    private fun chirpWindowJson(window: SchedulerStatsWindow?): String =
        if (window == null) "null"
        else "{\"played\":${window.played},\"droppedLate\":${window.droppedLate}," +
            "\"droppedOverflow\":${window.droppedOverflow},\"silenceFrames\":${window.silenceFrames}}"

    /** Whole seconds from now to [hostNanos] on the host's own clock, never less than one. */
    private fun secondsUntil(hostNanos: Long): Int {
        val remaining = hostNanos - System.nanoTime()
        return ((remaining + 999_999_999L) / 1_000_000_000L).toInt().coerceAtLeast(1)
    }

    /**
     * The seconds spanned by two host instants, rounded up.
     *
     * The sink cannot use [secondsUntil] for this: that one subtracts a host instant from a raw
     * nanoTime(), which is only the same clock on the host. Here both ends are host instants and
     * the offset between the two clocks cancels in their difference, so the duration is right on
     * either device without a conversion - and without depending on an estimate that could go
     * stale between the two reads.
     */
    private fun secondsBetween(fromHostNanos: Long, toHostNanos: Long): Int =
        ((toHostNanos - fromHostNanos + 999_999_999L) / 1_000_000_000L).toInt().coerceAtLeast(1)

    private fun estimatesJson(history: List<ClockEstimate>): String = buildString {
        append('[')
        history.forEachIndexed { index, estimate ->
            if (index > 0) append(',')
            append("{\"offsetNanos\":").append(estimate.offsetNanos)
            append(",\"uncertaintyNanos\":").append(estimate.uncertaintyNanos)
            append(",\"driftPpm\":").append(estimate.driftPpm)
            append(",\"sampleCount\":").append(estimate.sampleCount).append('}')
        }
        append(']')
    }

    companion object {
        const val CLOCK_PORT = 45123
        const val CHUNK_PORT = 45124

        /** playAtHostNanos = generation instant + this lead. */
        private const val LEAD_NANOS = 1_500_000_000L

        /** Gap between the last audio chunk and the sink's calibration chirp. */
        private const val CALIBRATION_GAP_NANOS = 2_000_000_000L

        /** The host's chirp follows the sink's by this much. */
        private const val STAGGER_NANOS = 500_000_000L

        /** The host keeps recording this long after its own chirp ends. */
        private const val RECORD_TAIL_NANOS = 2_000_000_000L

        /** The host starts recording this long before the sink's chirp. */
        private const val RECORD_LEAD_NANOS = 1_000_000_000L

        /** The renderer keeps writing this long past the chirp so the output buffer drains. */
        private const val CHIRP_DRAIN_NANOS = 1_000_000_000L

        /**
         * The M2 pass criterion itself: 5ms at 48kHz. Used as the ceiling on the drift loop's own
         * filtered error at chirp submission - if the loop knows it is further out than the entire
         * alignment budget, the alignment number it produces is meaningless. Deliberately not
         * DriftController's 1ms deadband: that value is sampled at a single instant and a healthy,
         * converged loop can sit transiently just outside it, so gating there would void good runs.
         */
        private const val ALIGNMENT_BUDGET_FRAMES = 240

        /** The wait for the chirp stops sleeping this far out and spins the rest. */
        private const val SPIN_GUARD_NANOS = 2_000_000L

        /** Host time may drift past the chirp instant by this much during the wait without alarm. */
        private const val CHIRP_WAKE_TOLERANCE_NANOS = 1_000_000L

        /** How much longer than the nominal segment the sink's renderer/idle-wait stay alive. */
        private const val RENDERER_MARGIN_SECONDS = 10

        /** How long the sink waits for its first clock estimate before giving up as a failure. */
        private const val CONVERGENCE_TIMEOUT_SECONDS = 40

        /** No new chunk for this long means the host has stopped broadcasting audio. */
        private const val IDLE_THRESHOLD_NANOS = 800_000_000L

        /** ~3s of audio at 20ms/chunk. */
        private const val SCHEDULER_CAPACITY_CHUNKS = 150
    }
}

/**
 * No clock offset estimate has ever succeeded, so this device has no host time to convert to.
 * A hard failure on purpose: the alternative it replaces was an offset of zero, which reads as a
 * perfectly normal run while every instant on it is wrong by the two phones' boot time apart.
 */
private class ClockOffsetUnavailable : IllegalStateException("no clock offset estimate has ever succeeded")
