package com.soundmesh.probe.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.soundmesh.core.AlignmentPairing
import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.AlignmentResultMessage
import com.soundmesh.core.AudioChunk
import com.soundmesh.core.CalibrationReply
import com.soundmesh.core.CalibrationUpdate
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.DriftController
import com.soundmesh.core.PairedAlignment
import com.soundmesh.core.PeerAdvertisement
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.REACQUIRE_THRESHOLD_FRAMES
import com.soundmesh.core.RendererPhase
import com.soundmesh.core.SchedulerStatsWindow
import com.soundmesh.core.TonePcmSource
import com.soundmesh.core.schedulerStatsWindow
import com.soundmesh.probe.AndroidPlaybackReader
import com.soundmesh.probe.ProbeCase
import com.soundmesh.probe.RunStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * ADB driven harness for the M1 and M2 gates. Not product code: the product will discover
 * peers and manage sessions itself, while this takes both from intent extras.
 *
 * `mode=CLOCK_ONLY` runs just the UDP clock exchange (the M1 gate). `mode=FULL` additionally
 * streams a shared audio timeline over TCP and closes with a calibration chirp (the M2 gate).
 */
class SyncActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var pairingView: ImageView
    private lateinit var runStore: RunStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this)
        // The code a peer scans sits above the status line rather than replacing it: the harness
        // reads the status off the screen, and a run that showed a code and nothing else would
        // have taken that away to gain something only a second handset can see.
        pairingView = ImageView(this).apply { visibility = android.view.View.GONE }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(pairingView)
                addView(statusView)
            }
        )
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


    /**
     * Puts the code on the screen for a peer to scan.
     *
     * On the UI thread because the run itself is not: everything below `startRun` happens on a
     * worker so the audio path never waits on the main looper.
     */
    private fun showPairingCode(payload: String) = runOnUiThread {
        pairingView.setImageBitmap(PairingCodeImage.bitmap(payload, PairingCodeImage.DEFAULT_PIXELS))
        pairingView.visibility = android.view.View.VISIBLE
    }
    private fun handle() {
        val caseId = intent.getStringExtra(ProbeCase.EXTRA_CASE_ID)
        val role = intent.getStringExtra("role")
        val seconds = intent.getIntExtra("seconds", -1)
        val mode = intent.getStringExtra("mode")
        if (caseId == null || !ProbeCase.isSafeCaseId(caseId) || role == null || role !in setOf("HOST", "SINK") ||
            seconds !in 10..900 || mode == null || mode !in setOf("CLOCK_ONLY", "FULL")
        ) {
            statusView.text = "REJECTED"
            return
        }
        // A capture run needs the user's MediaProjection consent before it can produce a single
        // frame, and consent is a dialog. The run is therefore deferred to onActivityResult; every
        // other run starts here exactly as it always has.
        val capturePackage = capturePackageRequested()
        if (role == "HOST" && mode == "FULL" && capturePackage != null) {
            pendingRun = PendingRun(caseId, seconds)
            statusView.text = "HOST AWAITING CAPTURE CONSENT"
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            return
        }
        startRun(role, mode, caseId, seconds, null)
    }

    private class PendingRun(val caseId: String, val seconds: Int)

    private var pendingRun: PendingRun? = null

    /**
     * The PC waits on sync.json, so a denied dialog has to leave one behind. Without this the run
     * would simply never report and the harness would blame its own timeout budget.
     */
    @Deprecated("Activity result API is sufficient for this one-time platform consent boundary.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return
        val pending = pendingRun ?: return
        pendingRun = null
        if (resultCode != RESULT_OK || data == null) {
            runStore.writeSyncJson(pending.caseId, "{\"schemaVersion\":1,\"role\":\"HOST\",\"failureCode\":\"CAPTURE_CONSENT_DENIED\"}")
            statusView.text = "HOST DONE"
            return
        }
        // The projection cannot be taken here. The platform hands it only to a foreground service
        // of type mediaProjection, and an activity asking for it throws SecurityException - which is
        // exactly how the first capture run died. The service takes it and calls back.
        statusView.text = "HOST ACQUIRING PROJECTION"
        SyncProjectionService.pending = { projection ->
            if (projection == null) {
                runStore.writeSyncJson(pending.caseId, "{\"schemaVersion\":1,\"role\":\"HOST\",\"failureCode\":\"CAPTURE_PROJECTION_UNAVAILABLE\"}")
                statusView.text = "HOST DONE"
            } else {
                startRun("HOST", "FULL", pending.caseId, pending.seconds, projection)
            }
        }
        startService(
            Intent(this, SyncProjectionService::class.java)
                .setAction(SyncProjectionService.ACTION_ACQUIRE)
                .putExtra(SyncProjectionService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(SyncProjectionService.EXTRA_RESULT_DATA, data)
        )
    }

    private fun startRun(role: String, mode: String, caseId: String, seconds: Int, projection: MediaProjection?) {
        statusView.text = "$role RUNNING"
        Thread {
            // Both branches write their own sync.json on success (CLOCK_ONLY inline below, FULL
            // inside runHostFull/runSinkFull); this thread only needs to cover failures.
            val failure = runCatching {
                if (role == "HOST") runHost(mode, caseId, seconds, projection) else runSink(mode, caseId, seconds)
            }.exceptionOrNull()
            // A missing clock offset gets a named code rather than an exception class name: it is
            // the one failure that used to be silently absorbed as an offset of zero.
            val failureCode = when (failure) {
                null -> null
                is ClockOffsetUnavailable -> "CLOCK_OFFSET_UNAVAILABLE"
                is SourceUnusable -> failure.code
                is PeerUnavailable -> failure.code
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

    private fun runHost(mode: String, caseId: String, seconds: Int, projection: MediaProjection?) {
        if (mode == "CLOCK_ONLY") {
            val server = ClockSyncServer(CLOCK_PORT)
            server.start()
            Thread.sleep(seconds * 1000L)
            server.stop()
            runStore.writeSyncJson(caseId, "{\"schemaVersion\":1,\"role\":\"HOST\",\"failureCode\":null}")
        } else {
            runHostFull(caseId, seconds, projection)
        }
    }

    private fun runSink(mode: String, caseId: String, seconds: Int) {
        val host = locateHost()
        val address = host.address
        if (mode == "CLOCK_ONLY") {
            val estimator = ClockOffsetEstimator()
            val client = ClockSyncClient(address, CLOCK_PORT, estimator)
            val history = client.runFor(seconds, clockIntervalMillisRequested())
            runStore.writeSyncJson(
                caseId,
                "{\"schemaVersion\":1,\"role\":\"SINK\",\"failureCode\":null,${clockJson(client, estimator, history)}}"
            )
        } else {
            runSinkFull(host, caseId, seconds)
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

    private fun clockIntervalMillisRequested(): Long {
        val requested = intent.getIntExtra("clock_interval_ms", DEFAULT_CLOCK_INTERVAL_MILLIS).toLong()
        return if (requested in MIN_CLOCK_INTERVAL_MILLIS..MAX_CLOCK_INTERVAL_MILLIS) requested else DEFAULT_CLOCK_INTERVAL_MILLIS.toLong()
    }

    /**
     * Standing correction for the fixed part of the acoustic alignment error, in microseconds,
     * as the previous run reported it in `alignmentErrorMs`. Zero, the default, leaves the run
     * exactly where every measurement so far was taken.
     *
     * Negative values are meaningful - the measured error is about -34.7 ms - so this one has no
     * positive-only guard, unlike the helpers around it.
     */
    private fun alignmentOffsetMicrosRequested(): Long =
        intent.getIntExtra("alignment_offset_us", 0).toLong()

    /**
     * The correction the sink actually applies, and where it came from.
     *
     * An explicit extra still wins, so every stored artifact stays reproducible by re-running the
     * same command. Absent one, the handset stands on what the last run told it - which is the
     * whole point: a pair of handsets nobody has ever calibrated has no number for a person to
     * type in, and this is what lets the first run produce it and the second run use it.
     *
     * `hasExtra` rather than a sentinel value, because an explicit zero is a real instruction -
     * "measure me uncorrected" - and must not be confused with "nothing was said".
     */
    private class ResolvedOffset(val micros: Long, val source: String, val observations: Int)

    private fun resolveSinkAlignmentOffset(peerId: String): ResolvedOffset = when {
        // An override starts the history over. The estimate this loop maintains is the mean of the
        // runs behind it, and a person naming a different correction is saying those runs are no
        // longer about this setup - carrying their weight forward would damp the loop against a
        // history it was just told to abandon.
        intent.hasExtra("alignment_offset_us") ->
            ResolvedOffset(alignmentOffsetMicrosRequested(), "intent", 0)
        else -> StoredCalibration(filesDir, peerId).read()
            ?.let { ResolvedOffset(it.micros, "stored", it.observations) }
            ?: ResolvedOffset(0L, "none", 0)
    }

    /**
     * Package whose playback the host captures and streams, or null for the generated tone every
     * measurement so far was taken on. Its presence is the switch; run-sync validates the shape and
     * refuses the probe's own package, which would feed the host's output back into itself.
     */
    private fun capturePackageRequested(): String? = intent.getStringExtra("capture_package")

    /**
     * Which handset was the access point: "hotspot" for the host itself, "shared" for a router or
     * a third device that both joined as ordinary clients.
     *
     * Provenance the run cannot recover afterwards. Every run before this field existed was made
     * on the host's own hotspot and none of them says so, so the configuration had to be recalled
     * from memory months later - and was recalled wrongly. It matters because a host that is also
     * the AP welds the two roles together: the sink's replies climb a contended STA-to-AP uplink
     * while the host's do not, which is the leading candidate for the role residual.
     *
     * Matched against the known values rather than echoed, so an unreadable word cannot reach the
     * report and be mistaken for a mode later.
     */
    /**
     * Whether this run finds its partner instead of being told where it is, defaulting to off -
     * the arrangement every measurement so far was taken on.
     *
     * Opt-in for the same reason the sink's own recording is: it changes the device under
     * measurement. The host starts advertising and the sink spends a discovery window listening on
     * a multicast group, both during a run whose whole subject is timing. Keeping it a flag leaves
     * every stored baseline reproducible and makes the two arrangements an A/B rather than a
     * replacement.
     */
    private fun discoverRequested(): Boolean = intent.getBooleanExtra("discover", false)

    /**
     * Use the host this handset last scanned, instead of finding one or being told one.
     *
     * Checked before `discover` rather than beside it: a run that scanned a code has been told
     * exactly which handset it means, and nothing a five second listen could add to that.
     */
    private fun pairedRequested(): Boolean = intent.getBooleanExtra("paired", false)

    private fun networkModeRequested(): String? =
        intent.getStringExtra("network_mode")?.takeIf { it in NETWORK_MODES }

    /**
     * Name of an audio file in the probe's own external files directory whose head the host
     * decodes and streams, or null for the generated tone. A bare name rather than a path: the
     * directory is fixed here, so a wrong extra cannot point the run at somebody else's file.
     *
     * Unlike capture this needs no consent and no foreground service - and unlike capture, the
     * host plays only what it streams, so the two handsets can be judged by ear.
     */
    private fun sourceFileRequested(): String? =
        intent.getStringExtra("source_file")?.takeIf { SAFE_SOURCE_FILE.matches(it) }

    private fun reacquireThresholdRequested(): Int {
        val requested = intent.getIntExtra("reacquire_threshold_frames", REACQUIRE_THRESHOLD_FRAMES)
        return if (requested > 0) requested else REACQUIRE_THRESHOLD_FRAMES
    }

    /**
     * How late a streamed chunk may be released before the renderer shortens it.
     *
     * Absent leaves [SyncRenderer.TRIM_DEADBAND_FRAMES], the value every run so far was measured
     * on. It exists because a product session put numbers on what the band costs: at the default
     * the host edits its own waveform 6.6 times a second, at 240 frames 0.03 times, and a listener
     * hears the difference. What no product session can say is whether the wider band moves the
     * alignment, because only a chirp answers that - and the chirp is exempt from the band by
     * design, so this run measures the timeline the widened band left behind rather than the band.
     */
    private fun trimFramesRequested(): Int {
        val requested = intent.getIntExtra("trim_frames", SyncRenderer.TRIM_DEADBAND_FRAMES)
        return if (requested > 0) requested else SyncRenderer.TRIM_DEADBAND_FRAMES
    }

    /**
     * The stop callback only records the fact. Tearing the run down from the projection's own
     * thread would race the loop that is mid-chunk; the loop notices on its next read, which
     * returns nothing once the recorder is gone.
     */
    private fun openCapture(projection: MediaProjection): CaptureChunkSource =
        CaptureChunkSource.open(this, projection, capturePackageRequested()!!) { captureStopped.set(true) }

    private val captureStopped = AtomicBoolean(false)

    private fun runHostFull(caseId: String, seconds: Int, projection: MediaProjection?) {
        // Both sources feed the same loop, so one of them would otherwise silently win.
        // run-sync refuses the pair too; this is the half the artifact can prove was honoured.
        if (capturePackageRequested() != null && sourceFileRequested() != null) throw SourceUnusable("SOURCE_CONFLICT")
        val clockServer = ClockSyncServer(CLOCK_PORT)
        val chunkServer = ChunkServer(CHUNK_PORT)
        val resultServer = AlignmentResultServer(RESULT_PORT)
        // Registered before anything binds a socket is fine: mDNS advertises a name and a port,
        // not a listening state, and the sink's discovery window is far longer than the gap.
        val hostId = HostIdentity(filesDir).current()
        val advertisement = if (discoverRequested()) {
            PeerDiscovery(this).register("$SERVICE_NAME_PREFIX-$caseId", CHUNK_PORT, hostId)
        } else {
            null
        }
        // Shown whether or not this run advertised. The code exists for the case where the two
        // handsets have not found each other, so making it conditional on the mechanism that
        // requires they already have would leave it useful only where it is not needed.
        val pairingCode = HostPairingCode.of(hostId, CHUNK_PORT)
        if (pairingCode != null) showPairingCode(pairingCode)
        val scheduler = PlaybackScheduler(
            SyncRenderer.FRAMES_PER_CHUNK,
            SCHEDULER_CAPACITY_CHUNKS,
            earlyReleaseNanos = SyncRenderer.earlyReleaseNanos(trimFramesRequested()),
            exactReleaseFromSequence = SyncRenderer.CHIRP_SEQUENCE_BASE
        )
        val renderer = SyncRenderer(
            scheduler, DriftController(deadbandFramesRequested()), lowLatencyRequested(), reacquireThresholdRequested(),
            trimDeadbandFrames = trimFramesRequested()
        ) { System.nanoTime() }
        val hostNanosNow: () -> Long = { System.nanoTime() }
        var capture: CaptureChunkSource? = null
        try {
            clockServer.start()
            chunkServer.start()
            // Bound now rather than when the sink's delivery is awaited, at the very end: the two
            // handsets finish their correlation passes within a second of each other, so a sink
            // that finishes first must land in the accept backlog instead of being refused.
            resultServer.start()

            val source = TonePcmSource()
            capture = projection?.let { openCapture(it) }
            val file = sourceFileRequested()?.let { FileChunkSource.open(File(getExternalFilesDir(null), it)) }
            val audioStart = System.nanoTime()
            val until = audioStart + seconds * 1_000_000_000L
            var lastPlayAtHostNanos = until

            renderer.endAt(until + CALIBRATION_GAP_NANOS)
            val rendererThread = Thread { renderer.run() }
            rendererThread.start()

            var sequence = 0
            var frameIndex = 0L
            var captureAnchorNanos = 0L
            while (System.nanoTime() < until) {
                // Two paces, one loop. Neither the generator nor the decoded file has one of its
                // own - both produce a chunk as fast as they are asked - so both are held to the
                // timeline by sleeping to the next chunk boundary. Capture has the device's own: a
                // full chunk only exists once the recorder has produced it, so the read blocks for
                // exactly as long as the chunk lasts and the sleep would be counted twice.
                val pcm = when {
                    capture != null -> capture.readChunk() ?: break
                    file != null -> file.readChunk()
                    else -> source.fill(frameIndex, SyncRenderer.FRAMES_PER_CHUNK)
                }
                // The generator's sleep advances the clock by exactly one chunk per pass, so reading
                // it fresh each time and anchoring to the first chunk come to the same instants.
                // Capture has no such guarantee: a stalled pass leaves the recorder holding several
                // chunks, the reads that follow return at once, and every one of them would be
                // stamped with nearly the same instant. Anchoring keeps the timeline the timeline;
                // a loop that falls too far behind then reports droppedLate instead of colliding.
                val playAt = if (capture != null) {
                    if (captureAnchorNanos == 0L) captureAnchorNanos = System.nanoTime() + LEAD_NANOS
                    captureAnchorNanos + sequence * SyncRenderer.CHUNK_NANOS
                } else {
                    System.nanoTime() + LEAD_NANOS
                }
                val chunk = AudioChunk(sequence, playAt, pcm)
                lastPlayAtHostNanos = playAt
                chunkServer.broadcast(chunk)
                scheduler.submit(chunk)
                sequence++
                frameIndex += SyncRenderer.FRAMES_PER_CHUNK
                if (capture == null) {
                    val nextAt = audioStart + frameIndex * 1_000_000_000L / SyncRenderer.SAMPLE_RATE
                    val sleepNanos = nextAt - System.nanoTime()
                    if (sleepNanos > 0) Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
                }
            }

            // Both devices derive the sink's chirp instant from the same wire value - the last
            // chunk's playAtHostNanos - so they agree on it without another message.
            val sinkChirpAt = lastPlayAtHostNanos + CALIBRATION_GAP_NANOS
            val hostChirpAt = sinkChirpAt + STAGGER_NANOS
            val chirpSubmission = submitChirp(scheduler, renderer, hostChirpAt, chirpRepeatsRequested(), chirpIntervalNanosRequested())
            // The renderer started on a provisional bound; only now is the chirp's end known.
            renderer.endAt(chirpSubmission.endHostNanos + CHIRP_DRAIN_NANOS)

            val calibration = CalibrationRunner(runStore, caseId, calibrationAudioSourceRequested(), hostNanosNow)
            awaitHostInstant(sinkChirpAt - RECORD_LEAD_NANOS, hostNanosNow)
            val recordThread = Thread { calibration.record(secondsUntil(chirpSubmission.endHostNanos + RECORD_TAIL_NANOS)) }
            recordThread.start()
            val chirpTiming = awaitChirpStart(hostChirpAt, hostNanosNow)
            rendererThread.join()
            recordThread.join()
            val chirpWindow = chirpWindow(renderer)

            // Read first, then wait: both handsets start correlating at the same instant, so
            // waiting first would spend the sink's whole pass idle and then start the host's.
            val ownAlignment = readOwnAlignment(caseId, calibration, sinkChirpAt)
            // Combined inside the exchange, because the answer the sink is waiting for is made of
            // it: the correction it should stand on from the next run onwards.
            var paired: PairedAlignment? = null
            val delivered = resultServer.awaitResult(RESULT_TIMEOUT_MILLIS) { message ->
                val combined = AlignmentPairing.combine(caseId, ownAlignment.readings, message)
                paired = combined
                CalibrationReply(
                    // Read through what the sink says it already applied, not through this device's
                    // copy of the same launch flag - only the sink knows what it actually used.
                    // One observation: the sink owns the history it gets averaged into.
                    measuredOffsetMicros = CalibrationUpdate.measured(message.appliedOffsetMicros, combined.verdict),
                    clusterMeanMicros = combined.verdict?.clusterMeanMs?.let { (it * 1000).roundToLong() },
                    passed = combined.verdict?.passed
                )
            }

            runStore.writeSyncJson(
                caseId,
                "{\"schemaVersion\":1,\"role\":\"HOST\",\"mode\":\"FULL\"," +
                    "\"failureCode\":${topLevelFailureCodeJson(renderer, chirpSubmission, chirpTiming, chirpWindow)}," +
                    // The source that actually opened, not the one asked for: UNPROCESSED is
                    // optional on Android, so a run that requests it may have been recorded on the
                    // fallback and the artifact has to say which path produced the number.
                    "\"audioSource\":${calibration.openedSource?.let { "\"$it\"" } ?: "null"}," +
                    // Which handset was the access point. Provenance a finished run cannot recover.
                    "\"networkMode\":${networkModeRequested()?.let { "\"$it\"" } ?: "null"}," +
                    // Whether this run advertised itself for the sink to find, rather than the
                    // sink having been handed an address.
                    "\"advertised\":${advertisement != null}," +
                    // What a sink pointed at this screen would read. Null when the handset has no
                    // one address a peer in the room could reach - see [LocalAddress].
                    "\"pairingCode\":${pairingCode?.let { "\"$it\"" } ?: "null"}," +
                    // What turns a whole-interval search into a windowed one. Recorded even while
                    // the analysis still runs on the PC, so the two can be compared before the
                    // device is trusted to measure on its own.
                    "\"recordingStartedAtHostNanos\":${calibration.startedAtHostNanos ?: "null"}," +
                    "\"sinkChirpAtHostNanos\":$sinkChirpAt," +
                    "\"onDeviceAlignment\":${ownAlignment.json}," +
                    // The whole measurement, taken by the two handsets alone. Everything above it
                    // is one side of one; this is the field a pair of phones can act on.
                    "\"pairedAlignment\":${pairedAlignmentJson(ownAlignment.readings.size, paired, delivered, resultServer.failureCode)}," +
                    "${chirpTimingJson(chirpTiming)}," +
                    "${chirpScheduleJson(alignmentOffsetMicrosRequested())}," +
                    // A captured run and a generated one are not comparable, and nothing else in
                    // the report distinguishes them. Same reason alignmentOffsetMicros is echoed.
                    "\"capturePackage\":${capturePackageRequested()?.let { "\"$it\"" } ?: "null"}," +
                    "\"captureStopped\":${captureStopped.get()}," +
                    "\"sourceFile\":${sourceFileRequested()?.let { "\"$it\"" } ?: "null"}," +
                    "\"sourceChunks\":${file?.chunkCount ?: 0}," +
                    "\"chirpAcquisition\":${chirpAcquisitionJson(chirpSubmission)}," +
                    "\"chirpWindow\":${chirpWindowJson(chirpWindow)}," +
                    "\"renderer\":${renderer.report(renderer.lastStreamingStats()?.silenceFrames)}}"
            )
        } finally {
            capture?.close()
            if (projection != null) {
                projection.stop()
                startService(Intent(this, SyncProjectionService::class.java).setAction(SyncProjectionService.ACTION_RELEASE))
            }
            runCatching { advertisement?.close() }
            resultServer.stop()
            chunkServer.stop()
            clockServer.stop()
        }
    }

    /**
     * Where the host is, and how this run came to know.
     *
     * An address on the command line still wins, so every stored baseline stays reproducible; with
     * `discover` it comes off the network instead, and with `paired` off a code this handset scanned
     * earlier. [chunkPort] is the port the record or the code actually carried, rather than this
     * build's copy of the constant.
     *
     * [unreachable] is what to call a host that answered here and then would not take a TCP
     * connection. It differs by path because the same silence means different things: a discovered
     * host that will not connect is client isolation, while a scanned one is a code that has gone
     * stale. Null on the hand-typed path, where the raw failure is already the whole story.
     *
     * [discovery] and [scan] are what the report says about how this run found its host, and only
     * one of them is ever populated. Kept as two fields rather than one renamed by path so that
     * every run already archived still means exactly what it meant when it was recorded.
     */
    private class HostLocation(
        val address: String,
        val chunkPort: Int,
        val unreachable: String?,
        val peerId: String,
        val discovery: String,
        val scan: String
    )

    private fun locateHost(): HostLocation {
        if (pairedRequested()) {
            // No discovery window at all: the code carried the address, and the person holding the
            // handset already said which host they meant by pointing it at one screen rather than
            // another. That is the whole point of the code, and the one thing mDNS cannot do.
            val code = PairedHost(filesDir).read() ?: throw PeerUnavailable("PAIRING_NOT_SCANNED")
            return HostLocation(
                address = code.address,
                chunkPort = code.chunkPort,
                unreachable = "PEER_UNREACHABLE_AFTER_SCAN",
                peerId = code.hostId,
                discovery = "null",
                scan = "{\"scanned\":true,\"hostId\":\"${code.hostId}\"," +
                    "\"address\":\"${code.address}\",\"port\":${code.chunkPort}}"
            )
        }
        if (!discoverRequested()) {
            val address = intent.getStringExtra("host_address")
                ?: throw IllegalArgumentException("SINK needs host_address")
            // Nothing on this path ever learns who answered, so there is no name to file the
            // correction under. Named rather than left to share whichever peer happened
            // to be first, so the report says plainly that the correction belongs to nobody.
            return HostLocation(
                address,
                CHUNK_PORT,
                unreachable = null,
                peerId = StoredCalibration.ANONYMOUS_PEER,
                discovery = "null",
                scan = "null"
            )
        }
        val startedAt = System.nanoTime()
        val outcome = PeerDiscovery(this).discover(DISCOVERY_WINDOW_MILLIS)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        // Thrown rather than fallen back to `host_address`: a run that asked to find its partner
        // and silently used a typed-in address instead would report discovery as working.
        val peer = outcome.peer ?: throw PeerUnavailable("DISCOVERY_${outcome.failure}")
        return HostLocation(
            address = peer.hostAddress,
            chunkPort = peer.port,
            unreachable = "PEER_UNREACHABLE_AFTER_DISCOVERY",
            peerId = PeerAdvertisement.hostIdOf(peer),
            scan = "null",
            discovery = "{\"seen\":${outcome.seen},\"compatible\":${outcome.compatible}," +
                "\"elapsedMillis\":$elapsedMillis,\"name\":\"${peer.name}\",\"port\":${peer.port}}"
        )
    }

    private fun runSinkFull(host: HostLocation, caseId: String, seconds: Int) {
        val address = host.address
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
        // Applied to the sink's whole view of host time, not to the chirp alone: the renderer
        // releases audio chunks and chirps through this same conversion, so correcting it moves
        // what the device actually plays. Shifting only the chirp would move the ruler instead.
        //
        // Attributing the entire error to the sink is a convention, not a finding - the acoustic
        // measurement gives the difference between the two handsets and cannot say which one is
        // late. The spec makes the host the reference and gives each sink its own offset.
        //
        // Sign: pass back the alignmentErrorMs a previous run reported. A negative error means the
        // measured stagger came out short, so the sink emitted late; subtracting it advances this
        // clock, the renderer finds each chunk due sooner, and the sink emits earlier by that much.
        // Resolved once, at the top of the run: it is read from a file that this same run rewrites
        // at the end, so re-reading it later would have the report say what the next run will
        // apply rather than what this one did.
        val resolvedOffset = resolveSinkAlignmentOffset(host.peerId)
        val alignmentOffsetNanos = resolvedOffset.micros * 1_000L
        val hostNanosNow: () -> Long = {
            // Never zero: with no estimate ever having succeeded there is no host time at all,
            // and inventing one is exactly the failure this whole path exists to detect.
            val estimate = latestEstimate() ?: throw ClockOffsetUnavailable()
            System.nanoTime() + estimate.offsetNanos - alignmentOffsetNanos
        }

        val scheduler = PlaybackScheduler(
            SyncRenderer.FRAMES_PER_CHUNK,
            SCHEDULER_CAPACITY_CHUNKS,
            earlyReleaseNanos = SyncRenderer.earlyReleaseNanos(trimFramesRequested()),
            exactReleaseFromSequence = SyncRenderer.CHIRP_SEQUENCE_BASE
        )
        val renderer = SyncRenderer(
            scheduler, DriftController(deadbandFramesRequested()), lowLatencyRequested(), reacquireThresholdRequested(),
            trimDeadbandFrames = trimFramesRequested(),
            // Not thrown when absent, unlike hostNanosNow: this only annotates a release that has
            // already happened, and a release cannot have happened without an offset to convert it.
            offsetNanosNow = { latestEstimate()?.offsetNanos ?: 0L },
            hostNanosNow = hostNanosNow
        )
        val lastPlayAt = AtomicLong(0L)
        // The spec requires playback not start before the offset estimate has converged, so
        // nothing is submitted to the scheduler until `converged` is set true below. Anything
        // that arrives before that is not schedulable and must not be counted as a drop, which
        // ruled out just letting the scheduler's own capacity/lateness logic handle it - that
        // would show up as droppedLate/droppedOverflow on an otherwise healthy run.
        val converged = AtomicBoolean(false)
        val chunkClient = ChunkClient(address, host.chunkPort) { chunk ->
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
        // The chirp schedule keeps playing after the audio segment ends, and every chirp on it is
        // converted to local time through the offset estimate - so the estimate has to be kept
        // alive for the whole of it. Bounding this by `seconds` alone froze the offset the moment
        // the audio stopped, and every repeat after the first was then played against a stale one:
        // at the 8 ppm measured between these two crystals that is about 6 frames per 15s gap,
        // which is the same size as the sink's whole emission jitter.
        val playbackSeconds = seconds + chirpScheduleSeconds()
        val clockThread = Thread { history = clockClient.runFor(maxOf(playbackSeconds, CONVERGENCE_TIMEOUT_SECONDS), clockIntervalMillisRequested()) }
        try {
            clockThread.start()
            // A host that answered mDNS but will not take a TCP connection is the signature of
            // client isolation, which the audio stream cannot survive either. Named here, because
            // as a bare ConnectException it reads like a host that never started.
            runCatching { chunkClient.start() }.onFailure {
                throw host.unreachable?.let { name -> PeerUnavailable(name) } ?: it
            }

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
            val calibration = if (sinkRecordsRequested()) CalibrationRunner(runStore, caseId, calibrationAudioSourceRequested(), hostNanosNow) else null
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

            // Read this side, then hand it over. The host cannot answer the alignment question
            // from its own recording alone - it hears its own chirp across centimetres and this
            // one across the room - so this delivery is what turns two halves into a measurement.
            // Sent even when there is nothing to send: an empty run and a dead sink look the same
            // from the host's end of a socket that never opens, and they are not the same.
            val ownAlignment = calibration?.let { readOwnAlignment(caseId, it, chirpAt) }
            val exchange = runCatching {
                AlignmentResultClient(address, RESULT_PORT)
                    .exchange(caseId, resolvedOffset.micros, ownAlignment?.readings ?: emptyList())
            }
            val resultDelivery = exchange.exceptionOrNull()?.javaClass?.simpleName
            // The loop closes here, and it closes by averaging rather than by replacing. Stored
            // only when the host had something to say: a run it could not combine, or whose scatter
            // makes the mean meaningless, leaves this handset on the estimate it already had, with
            // its observation count untouched - a run that cannot be read is not an observation.
            val observedOffset = exchange.getOrNull()?.measuredOffsetMicros
            val adopted = observedOffset?.let {
                CalibrationUpdate.fold(resolvedOffset.micros, resolvedOffset.observations, it)
            }
            if (adopted != null) {
                StoredCalibration(filesDir, host.peerId).write(adopted, resolvedOffset.observations + 1)
            }

            runStore.writeSyncJson(
                caseId,
                "{\"schemaVersion\":1,\"role\":\"SINK\",\"mode\":\"FULL\"," +
                    "\"failureCode\":${topLevelFailureCodeJson(renderer, chirpSubmission, chirpTiming, chirpWindow)}," +
                    // Null on a run that did not ask the sink to record, and on the same terms as
                    // the host otherwise: the source that actually opened, never the one asked for.
                    "\"audioSource\":${calibration?.openedSource?.let { "\"$it\"" } ?: "null"}," +
                    // Which handset was the access point. Provenance a finished run cannot recover.
                    "\"networkMode\":${networkModeRequested()?.let { "\"$it\"" } ?: "null"}," +
                    // The sink's own chirp, and when its own recording opened. Both sides carry
                    // the pair in host time, so either can window its search without the other.
                    "\"recordingStartedAtHostNanos\":${calibration?.startedAtHostNanos ?: "null"}," +
                    "\"sinkChirpAtHostNanos\":$chirpAt," +
                    "\"onDeviceAlignment\":${ownAlignment?.json ?: "null"}," +
                    // Null when the readings reached the host. Named otherwise, because a run whose
                    // delivery failed leaves the host with a one-sided report and no cause in it.
                    "\"resultDelivery\":${resultDelivery?.let { "\"$it\"" } ?: "null"}," +
                    // Where the correction this run applied came from, and what the next run will
                    // stand on. Null adopted means the correction was kept, not that it was zeroed.
                    // The count is the loop's gain, so without it the two offsets cannot be
                    // reconciled: the step between them is the observation divided by count plus one.
                    // Who that correction belongs to. `anonymous` means this run was handed an
                    // address and never learned who answered, so the correction is attached to
                    // nobody and a different partner would silently inherit it.
                    "\"alignmentOffsetPeer\":\"${host.peerId}\"," +
                    "\"alignmentOffsetSource\":\"${resolvedOffset.source}\"," +
                    "\"alignmentOffsetObservations\":${resolvedOffset.observations}," +
                    "\"observedOffsetMicros\":${observedOffset ?: "null"}," +
                    // How this run found the host, or null when it was told. The counts are what
                    // separate a quiet network from one carrying a build that cannot be talked to.
                    "\"discovery\":${host.discovery},\"scan\":${host.scan}," +
                    "\"adoptedOffsetMicros\":${adopted ?: "null"}," +
                    "\"convergenceWaitNanos\":$convergenceWaitNanos,${chirpTimingJson(chirpTiming)}," +
                    "${chirpScheduleJson(resolvedOffset.micros)}," +
                    "\"chirpAcquisition\":${chirpAcquisitionJson(chirpSubmission)}," +
                    "\"chirpWindow\":${chirpWindowJson(chirpWindow)}," +
                    "${clockJson(clockClient, estimator, history)}," +
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
    /**
     * How long the chirp schedule runs past the audio segment, in whole seconds.
     *
     * The margin covers what sits between the audio's end and the last chirp being heard: the 2s
     * calibration gap, the sweep and its 1s drain.
     */
    private fun chirpScheduleSeconds(): Int =
        ((chirpRepeatsRequested() - 1).toLong() * chirpIntervalNanosRequested() / 1_000_000_000L).toInt() +
            CHIRP_SCHEDULE_MARGIN_SECONDS

    /** [alignmentOffsetMicros] is the correction the run honoured, which on the sink is not
     * necessarily the one the launch flag asked for - see [resolveSinkAlignmentOffset]. */
    private fun chirpScheduleJson(alignmentOffsetMicros: Long): String =
        "\"chirpRepeats\":${chirpRepeatsRequested()},\"chirpIntervalNanos\":${chirpIntervalNanosRequested()}," +
            "\"deadbandFrames\":${deadbandFramesRequested()}," +
            "\"alignmentOffsetMicros\":$alignmentOffsetMicros"

    /**
     * The device's own reading of its own recording, or null if it could not take one.
     *
     * Wrapped rather than allowed to throw: this runs at the tail of a twelve minute run, on code
     * that has never met a real recording, and the WAV is on disk either way. A failure here has
     * to cost the on-device number and nothing else - the PC analysis reads the same file and is
     * still the reference the port is checked against.
     *
     * `elapsedMillis` is the point of recording it separately. The whole reason the recording is
     * timestamped is to collapse a whole-interval search into a windowed one, and how much that
     * actually buys on a handset - as opposed to on a PC, where it was measured - is not something
     * to reason about.
     */
    private class OwnAlignment(val readings: List<AlignmentReading>, val json: String)

    private fun readOwnAlignment(caseId: String, calibration: CalibrationRunner, firstChirpAtHostNanos: Long): OwnAlignment {
        val startedAt = calibration.startedAtHostNanos ?: return OwnAlignment(emptyList(), "null")
        var readings: List<AlignmentReading> = emptyList()
        val json = runCatching {
            val recording = File(runStore.prepareRun(caseId), "calibration.wav")
            val recorded = com.soundmesh.probe.WavFileReader.readMono(recording)
            val startedNanos = System.nanoTime()
            readings = OnDeviceAlignment.readRun(
                recorded = recorded,
                reference = ChirpGenerator.generateMono(),
                recordingStartedAtHostNanos = startedAt,
                firstChirpAtHostNanos = firstChirpAtHostNanos,
                staggerNanos = STAGGER_NANOS,
                chirpRepeats = chirpRepeatsRequested(),
                chirpIntervalNanos = chirpIntervalNanosRequested()
            )
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000
            val pairs = readings.joinToString(",") { reading ->
                "{\"firstIndex\":${reading.firstIndex ?: "null"},\"secondIndex\":${reading.secondIndex ?: "null"}," +
                    "\"alignmentErrorMs\":${reading.alignmentErrorMs ?: "null"}," +
                    "\"confidence\":\"${reading.confidence}\"," +
                    "\"ratios\":[${reading.ratios.joinToString(",") { it?.toString() ?: "null" }}]," +
                    "\"atSearchEdge\":[${reading.atSearchEdge.joinToString(",") { it?.toString() ?: "null" }}]}"
            }
            "{\"elapsedMillis\":$elapsedMillis,\"frames\":${recorded.size},\"pairs\":[$pairs]}"
        }.getOrElse {
            // A partial list would be combined against the other side as though it were whole.
            readings = emptyList()
            "{\"failure\":\"${it.javaClass.simpleName}\"}"
        }
        return OwnAlignment(readings, json)
    }

    /**
     * The two handsets' readings combined into the measurement itself, judged on the spot.
     *
     * This is the field the migration exists for. Every other alignment number in this report is
     * one side of a measurement - it carries the flight time across the room, which is why the
     * separation used to have to be measured with a tape and handed in. [AlignmentPairing] removes
     * it by construction and judges what is left, so the pair answers "are we aligned" with nothing
     * attached to either handset.
     *
     * Only the shape of the answer is decided here. Which deliveries may be combined at all is
     * [AlignmentPairing]'s to say, and a delivery that never arrived is the transport's.
     */
    private fun pairedAlignmentJson(
        hostPairs: Int,
        paired: PairedAlignment?,
        delivered: AlignmentResultMessage?,
        deliveryFailure: String?
    ): String {
        val head = "\"hostPairs\":$hostPairs,\"sinkPairs\":${delivered?.readings?.size ?: "null"}," +
            "\"sinkAppliedOffsetMicros\":${delivered?.appliedOffsetMicros ?: "null"}"
        if (paired == null) return "{$head,\"failure\":\"${deliveryFailure ?: "RESULT_UNAVAILABLE"}\"}"
        val verdict = paired.verdict ?: return "{$head,\"failure\":\"${paired.failure}\"}"

        val pairsJson = paired.pairs.joinToString(",") { pair ->
            if (pair == null) "null"
            else "{\"alignmentErrorMs\":${pair.alignmentErrorMs},\"separationMetres\":${pair.separationMetres}," +
                "\"flightTimeMs\":${pair.flightTimeMs},\"rawHostMs\":${pair.rawHostMs},\"rawSinkMs\":${pair.rawSinkMs}}"
        }
        return "{$head,\"failure\":null,\"pairs\":[$pairsJson]," +
            "\"verdict\":{\"clusterMeanMs\":${verdict.clusterMeanMs}," +
            "\"clusterSdMs\":${verdict.clusterSdMs},\"clusterCount\":${verdict.clusterCount}," +
            "\"outliers\":[${verdict.outliers.joinToString(",")}],\"maxAbsMs\":${verdict.maxAbsMs}," +
            "\"passed\":${verdict.passed}," +
            "\"failures\":[${verdict.failures.joinToString(",") { "\"$it\"" }}]}}"
    }

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

    /**
     * The clock layer of a sink report: the estimates the run used, and the exchanges they were fitted from.
     *
     * The exchanges are the expensive half to collect and the cheap half to store - a few tens of
     * kilobytes against a 50 MB recording - and they are what lets a candidate estimator design be
     * scored offline on the input a real run actually saw, instead of on a second run of the phones.
     */
    private fun clockJson(client: ClockSyncClient, estimator: ClockOffsetEstimator, history: List<ClockEstimate>): String =
        "\"clockIntervalMillis\":${clockIntervalMillisRequested()}," +
            "\"estimatorWindow\":${estimator.windowSize},\"estimatorBest\":${estimator.bestCount}," +
            "\"estimates\":${estimatesJson(history)}," +
            "\"exchanges\":${exchangesJson(client.recordedExchanges())}"

    private fun exchangesJson(exchanges: List<ClockExchange>): String = buildString {
        append('[')
        exchanges.forEachIndexed { index, exchange ->
            if (index > 0) append(',')
            append("[").append(exchange.t1).append(',').append(exchange.t2)
            append(',').append(exchange.t3).append(',').append(exchange.t4).append(']')
        }
        append(']')
    }

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

        /** Clock exchange cadence. A run may ask for a denser one; see [clockIntervalMillisRequested]. */
        private const val DEFAULT_CLOCK_INTERVAL_MILLIS = 2000
        private const val MIN_CLOCK_INTERVAL_MILLIS = 100L
        private const val MAX_CLOCK_INTERVAL_MILLIS = 10_000L
        const val CHUNK_PORT = 45124

        /** Where the host takes delivery of the sink's own reading of the run. */
        const val RESULT_PORT = 45125

        /**
         * How long the host waits for that delivery.
         *
         * What it bounds is the gap between the two handsets finishing, not the correlation pass
         * itself: both start theirs at the same instant, and the measured passes were 10.9s and
         * 11.5s, so the wait is normally about a second. Generous enough that a slower handset is
         * never cut off, short enough that a dead sink costs a minute of a twelve minute run
         * rather than the report.
         */
        const val RESULT_TIMEOUT_MILLIS = 60_000

        /** playAtHostNanos = generation instant + this lead. */
        private const val REQUEST_MEDIA_PROJECTION = 41
        private const val LEAD_NANOS = 1_500_000_000L

        // A bare file name. Dots are allowed anywhere, so this does not rule out ".." on its own -
        // what rules out walking off the directory is that no path separator matches at all, and
        // the leading character must be alphanumeric so the name cannot itself be "..".
        private val SAFE_SOURCE_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        /** The same two the runner offers. Kept in step with NETWORK_MODES in run-sync.mjs. */
        private val NETWORK_MODES = setOf("hotspot", "shared")

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

        /** Slack past the last chirp instant: the calibration gap, the sweep and its drain. */
        private const val CHIRP_SCHEDULE_MARGIN_SECONDS = 5

        /** No new chunk for this long means the host has stopped broadcasting audio. */
        private const val IDLE_THRESHOLD_NANOS = 800_000_000L

        /**
         * How long the sink listens before deciding what is on the network.
         *
         * The whole window is spent every time. mDNS never says "that was all of them", so
         * returning at the first answer would silently turn two hosts into whichever replied
         * first - and two hosts is the one outcome discovery must refuse to guess at.
         */
        private const val DISCOVERY_WINDOW_MILLIS = 5_000

        /** Instance name prefix. The case id follows it, so two runs cannot collide on a name. */
        private const val SERVICE_NAME_PREFIX = "SoundMesh"

        /** Big enough to be read across a room off a phone screen, small enough to fit on one. */

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
