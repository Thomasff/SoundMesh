package com.soundmesh.product

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.soundmesh.core.AlignmentPairing
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationReply
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.CalibrationUpdate
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.LinkQuality
import com.soundmesh.core.LinkSurvey
import com.soundmesh.probe.R
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.AlignmentResultClient
import com.soundmesh.probe.sync.AlignmentResultServer
import com.soundmesh.probe.sync.Calibration
import com.soundmesh.probe.sync.CalibrationPlanClient
import com.soundmesh.probe.sync.CalibrationPlanServer
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.PeerCalibrationRunner
import com.soundmesh.probe.sync.holdingRadio
import com.soundmesh.probe.sync.radioHoldOf
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.SyncActivity
import java.io.File

/**
 * Whether a finished run may move the correction this handset carries.
 *
 * [CalibrationUpdate.usable] admits a failed run on purpose, and its own comment gives the reason:
 * a pair that has never been calibrated sits tens of milliseconds out, fails every threshold in
 * the verdict, and is exactly the run the loop has to adopt or no first correction can ever be
 * made. **That reason is spent the moment a constant exists.**
 *
 * C1, the first run on hardware, was such a run - FAIL, readable, and folded - and it moved a good
 * constant from 34511 to 36962 with nothing in any later result to notice it by. The condition the
 * comment already states is applied here rather than widened in [CalibrationUpdate], where the
 * harness's 186 archived runs were all taken under the present rule.
 *
 * A run that cannot say whether it passed is not a run that passed.
 */
internal fun foldsIntoStoredCalibration(observations: Int, passed: Boolean?): Boolean =
    observations == 0 || passed == true

/**
 * The clock layer of a peer calibration report.
 *
 * File scope so it can be judged on what it produces rather than on how its source reads. The
 * assertion that went in the other way round here passed against an unrelated line while the
 * feature it named was not wired up at all.
 */
internal fun clockReportJson(
    intervalMillis: Long,
    windowSize: Int,
    bestCount: Int,
    radioHeld: Boolean,
    link: LinkQuality?,
    atStart: ClockEstimate?,
    atEnd: ClockEstimate?,
    exchanges: List<ClockExchange>
): String =
    "{\"intervalMillis\":$intervalMillis,\"windowSize\":$windowSize,\"bestCount\":$bestCount," +
        "\"radioHeld\":$radioHeld,\"link\":${linkJson(link)}," +
        "\"atStart\":${estimateJson(atStart)},\"atEnd\":${estimateJson(atEnd)}," +
        "\"exchanges\":${exchangesJson(exchanges)}}"

/** The run's own report with [clock] spliced in beside it, under the name the analysis reads. */
internal fun withClockReport(runJson: String, clock: String): String =
    runJson.dropLast(1) + ",\"clock\":" + clock + "}"

/**
 * Four timestamps per exchange, the same shape [SyncActivity] has always written.
 *
 * Kept the same on purpose: the harness's recorded runs and these are the same kind of evidence,
 * and one replay script should read both.
 */
private fun exchangesJson(exchanges: List<ClockExchange>): String =
    exchanges.joinToString(",", "[", "]") { "[${it.t1},${it.t2},${it.t3},${it.t4}]" }

private fun linkJson(link: LinkQuality?): String =
    link?.let {
        "{\"medianRoundTripNanos\":${it.medianRoundTripNanos}," +
            "\"p90RoundTripNanos\":${it.p90RoundTripNanos},\"samples\":${it.samples}}"
    } ?: "null"

private fun estimateJson(estimate: ClockEstimate?): String =
    estimate?.let {
        "{\"offsetNanos\":${it.offsetNanos},\"uncertaintyNanos\":${it.uncertaintyNanos}," +
            "\"driftPpm\":${it.driftPpm},\"sampleCount\":${it.sampleCount}}"
    } ?: "null"

/**
 * Measures the fixed offset between this handset and the one it is paired with, and stores it.
 *
 * The constant this produces used to take a PC, a cable and four harness runs driven from a
 * command line - which works once, for the two handsets in this room, and is exactly what the
 * product's premise rules out. `SinkSession` only ever read this file; nothing in the product
 * could write it. This is what writes it.
 *
 * The role is not asked for twice. It travels in from the home screen, which has already asked
 * which side this phone is being, because the correction is directional and two answers to one
 * question can disagree - and what a disagreement produces here is a correction filed under the
 * wrong peer, applied silently ever after with nothing in any result to notice it by.
 *
 * Nothing starts on its own, on the same terms as [CalibrateActivity]: a calibration is a minute
 * of chirps that only works with two phones left alone in a quiet room, so the screen explains
 * itself and waits to be told.
 *
 * Startable by name as well, with `auto`, because the first thing this has to do is agree with the
 * harness runs it replaces, and that comparison is driven over ADB.
 */
class PeerCalibrateActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var state by mutableStateOf(PeerCalibrateState())

    /** One calibration at a time: two would share a microphone, a port and a run directory. */
    @Volatile private var running = false

    /**
     * Whether the WiFi radio was actually held out of power save for this run.
     *
     * On the record rather than assumed: the lock is best effort, and a run whose lock quietly did
     * nothing looks exactly like a run that proves power save is irrelevant.
     */
    @Volatile private var radioHeld = false

    /** What the link looked like when the clock had filled its window, or null if unmeasured. */
    @Volatile private var link: LinkQuality? = null

    /** Set by the button that asked for the permission, so the run resumes once it is granted. */
    private var verifyingAfterPermission = false

    private val askRecordAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start(verifyingAfterPermission)
        else state = state.copy(message = getString(R.string.pair_calibrate_no_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A minute of quiet room, and a screen that sleeps takes the CPU with it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val stored = storedCalibration()
                    PeerCalibrateScreen(
                        state = state.copy(
                            role = role(),
                            stored = stored?.micros,
                            observations = stored?.observations ?: 0
                        ),
                        actions = PeerCalibrateActions(
                            calibrate = { begin(verifying = false) },
                            verify = { begin(verifying = true) }
                        )
                    )
                }
            }
        }
        if (intent.getBooleanExtra("auto", false)) begin(intent.getBooleanExtra("verify", false))
    }

    /**
     * A second start reaches here rather than [onCreate], because this screen is singleTask.
     *
     * Without it the screen sits on the last run's answer and measures nothing - which is how the
     * Magic6's second output-lead reading was lost: `am start` reported success, the activity was
     * already up, and three minutes of quiet room bought nothing at all.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("auto", false)) begin(intent.getBooleanExtra("verify", false))
    }

    /**
     * Which side of the pair this handset is on, or null if nobody has said yet.
     *
     * Handed in rather than worked out, and deliberately not derived from the pairing file: both
     * handsets in this room hold one, because they have each scanned the other at some point, so
     * "has a scanned pairing" would have made both of them the sink and no run could start. The
     * role is what this phone is being right now, which is the same question the home screen
     * already asks - [HomeScreen]'s own comment says it is kept off disk because swapping the two
     * and trying again is the most common thing anybody does - so this follows that answer instead
     * of inventing a second one that could disagree with it.
     */
    private fun role(): CalibrationRole? =
        CalibrationRole.entries.firstOrNull { it.name == intent.getStringExtra("role") }

    private fun begin(verifying: Boolean) {
        if (running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            verifyingAfterPermission = verifying
            askRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        start(verifying)
    }

    private fun start(verifying: Boolean) {
        running = true
        state = state.copy(running = true, message = getString(R.string.pair_calibrate_waiting))
        // Guarded here rather than inside: an uncaught throw on any thread takes the whole process
        // with it, and a calibration that vanishes tells whoever ran it nothing at all.
        Thread({
            runCatching {
                // Held across the whole run and on both sides. An access point buffers frames for
                // a station that is asleep, and the reply half of an exchange is as much of the
                // round trip as the request half - a host dozing costs the sink exactly the same
                // milliseconds. Whether it was actually taken is recorded, not assumed.
                holdingRadio(radioHoldOf(this), held = { radioHeld = it }) {
                    when (role()) {
                        CalibrationRole.HOST -> measureAsHost()
                        CalibrationRole.SINK -> measureAsSink(verifying)
                        null -> show(getString(R.string.pair_calibrate_no_role))
                    }
                }
            }.onFailure {
                Log.e(LOG_TAG, "the pair calibration did not finish", it)
                show(getString(R.string.pair_calibrate_failed, it.javaClass.simpleName))
            }
            running = false
            handler.post { state = state.copy(running = false) }
        }, "SoundMeshPeerCalibrate").start()
    }

    /**
     * The host's part: serve the clock, mint the plan when asked, play, and combine the two halves.
     *
     * It stores nothing. The correction belongs to the handset that applies it, and this one does
     * not - what this produces is the answer the sink is waiting for on the socket it delivered on.
     */
    private fun measureAsHost() {
        val hostId = HostIdentity(filesDir).current()
        val clockServer = ClockSyncServer(SyncActivity.CLOCK_PORT)
        val resultServer = AlignmentResultServer(SyncActivity.RESULT_PORT)
        val planServer = CalibrationPlanServer(PLAN_PORT)
        try {
            clockServer.start()
            resultServer.start()
            planServer.start()
            val plan = planServer.awaitRequest(PLAN_WAIT_MILLIS) { requested ->
                // The case names a directory RunStore will create, and it arrived over a socket.
                // Only the two this handset runs are honoured; anything else ends the run here
                // rather than at the run store.
                if (requested !in setOf(CASE_MEASURE, CASE_VERIFY)) {
                    throw IllegalArgumentException("not a case this handset runs: $requested")
                }
                CalibrationPlan(
                    caseId = requested,
                    hostId = hostId,
                    // Far enough out to cover the warm-up and the gap the sink has yet to start.
                    firstChirpAtHostNanos = System.nanoTime() + PLAN_LEAD_NANOS,
                    staggerNanos = STAGGER_NANOS,
                    repeats = CHIRP_REPEATS,
                    intervalNanos = CHIRP_INTERVAL_NANOS
                )
            } ?: return show(
                getString(R.string.pair_calibrate_failed, planServer.failureCode ?: "PLAN_LOST")
            )
            show(getString(R.string.pair_calibrate_running))
            val run = PeerCalibrationRunner(
                runStore = RunStore(filesDir),
                caseId = plan.caseId,
                role = CalibrationRole.HOST,
                plan = plan,
                hostNanosNow = { System.nanoTime() }
            ).run()
            // Written before anything is answered, so a refused run still leaves its evidence.
            File(RunStore(filesDir).prepareRun(plan.caseId), ARTIFACT).writeText(run.json)
            Log.i(LOG_TAG, run.json)
            var outcome = getString(R.string.pair_calibrate_kept, run.refusal ?: "ONE_SIDED_RUN")
            resultServer.awaitResult(RESULT_TIMEOUT_MILLIS) { message ->
                val combined = AlignmentPairing.combine(plan.caseId, run.readings, message)
                outcome = combined.verdict?.clusterMeanMs?.let { mean ->
                    getString(
                        R.string.pair_calibrate_host_done,
                        mean,
                        combined.pairs.filterNotNull().map { it.separationMetres }.average()
                    )
                } ?: getString(
                    R.string.pair_calibrate_kept,
                    combined.failure?.name ?: "NO_VERDICT"
                )
                CalibrationReply(
                    // Read through what the sink says it applied, never through this handset's
                    // idea of it: only the sink knows what it actually used.
                    measuredOffsetMicros = CalibrationUpdate.measured(
                        message.appliedOffsetMicros,
                        combined.verdict
                    ),
                    clusterMeanMicros = combined.verdict?.clusterMeanMs?.let { (it * 1000).toLong() },
                    passed = combined.verdict?.passed
                )
            }
            show(outcome)
        } finally {
            planServer.stop()
            resultServer.stop()
            clockServer.stop()
        }
    }

    /**
     * The sink's part: converge the clock, fetch the plan, play, deliver, and fold what comes back.
     *
     * The clock comes first because the plan's instants are in the host's clock and this handset
     * cannot act on one until it can convert. The other order would leave the plan expiring inside
     * a wait it caused itself.
     */
    private fun measureAsSink(verifying: Boolean) {
        val paired = PairedHost(filesDir).read()
            ?: return show(getString(R.string.pair_calibrate_no_pairing))
        val stored = StoredCalibration(filesDir, paired.hostId).read()
        val appliedMicros = stored?.micros ?: 0L
        val estimator = ClockOffsetEstimator()
        val clockClient = ClockSyncClient(paired.address, SyncActivity.CLOCK_PORT, estimator)
        val clockThread =
            Thread({ clockClient.runFor(CLOCK_SECONDS, CLOCK_INTERVAL_MILLIS) }, "SoundMeshPeerClock")
        val clockStartedAt = System.nanoTime()
        clockThread.start()
        try {
            show(getString(R.string.pair_calibrate_clock))
            val deadline = clockStartedAt + CONVERGENCE_TIMEOUT_NANOS
            while (clockClient.currentEstimate() == null && System.nanoTime() < deadline) {
                Thread.sleep(CONVERGENCE_POLL_MILLIS)
            }
            if (clockClient.currentEstimate() == null) {
                return show(getString(R.string.pair_calibrate_failed, "CLOCK_NOT_CONVERGED"))
            }
            // Having an estimate is not the same as having a settled one, and the first run on
            // hardware cost exactly that difference. MIN_SAMPLES is the point the estimator will
            // answer at, eight of a sixty-four wide window; the offset it answers with then is
            // still moving as the window fills. C1 measured its five chirps against five different
            // offsets spanning 8.1 ms, and the five alignment errors moved with them one for one.
            //
            // The harness never met this because it plays two minutes of audio between converging
            // and chirping, which at its own two second cadence is exactly the window's worth of
            // exchanges. This waits for the same thing directly instead of buying it by accident.
            while (System.nanoTime() - clockStartedAt < CLOCK_FILL_NANOS) {
                Thread.sleep(CONVERGENCE_POLL_MILLIS)
            }
            val converged = clockClient.currentEstimate()
                ?: return show(getString(R.string.pair_calibrate_failed, "CLOCK_NOT_CONVERGED"))
            // Read before the chirps rather than after, because that is the only point at which
            // knowing costs nothing. A link this slow cannot be aligned by any estimator - the bias
            // a two-way exchange carries is half the difference between the one way delays, which
            // is systematic - so the alternative to saying so here is fifty seconds of standing
            // still for a number nobody can read.
            link = LinkSurvey.of(clockClient.recordedExchanges())
            link?.takeIf { !it.usable }?.let {
                return show(getString(
                    R.string.pair_calibrate_slow_link,
                    it.medianRoundTripNanos / 1_000_000.0,
                    LinkSurvey.MAX_MEDIAN_ROUND_TRIP_NANOS / 1_000_000.0
                ))
            }
            // Asked for by name: the host cannot tell a measurement from a check, and both landing
            // in one directory cost the measurement's host half once already.
            val caseId = if (verifying) CASE_VERIFY else CASE_MEASURE
            val plan = CalibrationPlanClient(paired.address, PLAN_PORT).request(caseId)
            // The host id is the file name the correction is stored under. A plan from somebody
            // this handset never scanned would file the answer against the wrong peer, and every
            // later session would apply it with nothing in the result to notice it by.
            if (plan.hostId != paired.hostId) {
                return show(getString(R.string.pair_calibrate_failed, "PLAN_FROM_ANOTHER_HOST"))
            }
            // Both sides file under the plan's case. A host that answered with a different one
            // would split one run across two directories with nothing in either saying so.
            if (plan.caseId != caseId) {
                return show(getString(R.string.pair_calibrate_failed, "PLAN_FOR_ANOTHER_CASE"))
            }
            show(getString(R.string.pair_calibrate_running))
            val run = PeerCalibrationRunner(
                runStore = RunStore(filesDir),
                caseId = caseId,
                role = CalibrationRole.SINK,
                plan = plan,
                // The correction is subtracted from this handset's view of host time, exactly as
                // SinkSession applies it, so a verification tests it where the product puts it.
                hostNanosNow = {
                    System.nanoTime() +
                        (clockClient.currentEstimate() ?: converged).offsetNanos -
                        appliedMicros * 1_000L
                },
                offsetNanosNow = { (clockClient.currentEstimate() ?: converged).offsetNanos }
            ).run()
            // Spliced in rather than passed to the runner: the clock belongs to this screen, and
            // the reason to record it is that the constant is only as good as the offset the
            // chirps were scheduled against. Without it, a run whose estimate was still moving
            // reads exactly like a run whose room was noisy.
            // The clock's work is over - what is left is one socket exchange with the host - and
            // recordedExchanges is documented to be read once runFor has returned. It is backed by
            // a plain ArrayList the clock thread appends to, so reading it from here while that
            // thread still runs is a race, and the harness avoids it by joining first (see
            // SyncActivity, which builds its report after clockThread.join()). Stopped here rather
            // than only in the finally so this side does the same.
            clockThread.interrupt()
            clockThread.join(CLOCK_JOIN_MILLIS)
            val json = withClock(
                run.json, estimator, converged, clockClient.currentEstimate(), clockClient.recordedExchanges()
            )
            File(RunStore(filesDir).prepareRun(caseId), ARTIFACT).writeText(json)
            Log.i(LOG_TAG, json)
            // Delivered even when there is nothing to deliver: the host waits on this message, so
            // an empty run and a dead sink look the same from an end of a socket that never opens.
            val reply = AlignmentResultClient(paired.address, SyncActivity.RESULT_PORT)
                .exchange(plan.caseId, appliedMicros, run.readings)
            // A verification measures the residual left after the stored constant is applied.
            // Writing a residual where the constant lives would halve the correction every time.
            if (verifying) return show(
                getString(R.string.pair_calibrate_verified, (reply.clusterMeanMicros ?: 0L) / 1000.0)
            )
            val observed = reply.measuredOffsetMicros
                ?: return show(getString(R.string.pair_calibrate_kept, run.refusal ?: "NOT_USABLE"))
            val observations = stored?.observations ?: 0
            if (!foldsIntoStoredCalibration(observations, reply.passed)) {
                return show(getString(R.string.pair_calibrate_not_folded))
            }
            val folded = CalibrationUpdate.fold(appliedMicros, observations, observed)
                ?: return show(getString(R.string.pair_calibrate_kept, "OFFSET_OUT_OF_RANGE"))
            StoredCalibration(filesDir, paired.hostId).write(folded, observations + 1)
            show(getString(R.string.pair_calibrate_done, folded / 1000.0, observations + 1))
        } finally {
            clockThread.interrupt()
        }
    }

    /**
     * The constant this handset carries for the peer it is paired with, or null if it carries none.
     *
     * Read on every recomposition rather than held: the run writes it from another thread, and a
     * remembered copy would leave the screen announcing the answer it had before it measured.
     */
    private fun storedCalibration(): Calibration? =
        PairedHost(filesDir).read()?.let { StoredCalibration(filesDir, it.hostId).read() }

    /** The run's own report, with the clock it was scheduled against spliced beside it. */
    private fun withClock(
        json: String,
        estimator: ClockOffsetEstimator,
        atStart: ClockEstimate?,
        atEnd: ClockEstimate?,
        exchanges: List<ClockExchange>
    ): String = withClockReport(
        json,
        clockReportJson(
            CLOCK_INTERVAL_MILLIS, estimator.windowSize, estimator.bestCount, radioHeld, link, atStart, atEnd, exchanges
        )
    )

    private fun show(text: String) {
        handler.post { state = state.copy(message = text) }
    }

    private companion object {
        const val LOG_TAG = "SoundMeshPeerCalibrate"
        const val ARTIFACT = "peer-calibration.json"

        /**
         * RunStore accepts `[A-Z][0-9]+` and never clears a directory it is handed, so a case id
         * names a place on disk rather than a run. Every letter is already spoken for by an
         * archived series, and C1 landed on top of one: the first hardware run overwrote the
         * calibration.wav an earlier alignment run had left in runs/C1 on both handsets. Ninety
         * and up is past the end of every series the harness has recorded.
         */
        const val CASE_MEASURE = "C90"
        const val CASE_VERIFY = "C91"

        /** Next after AlignmentResultServer's 45125. */
        const val PLAN_PORT = 45126

        /** How long the host holds the screen open waiting for somebody to pick up the other phone. */
        const val PLAN_WAIT_MILLIS = 300_000

        /**
         * How far out the plan puts the first chirp: the sink's warm-up and the gap after it, plus
         * enough for the sink to have received the plan and started. See CalibrationSchedule.
         */
        const val PLAN_LEAD_NANOS = 7_000_000_000L

        /** SyncActivity's own, and the one AlignmentAnalysis is written around. */
        const val STAGGER_NANOS = 500_000_000L

        /**
         * Five pairs. CalibrationUpdate.usable needs a cluster of at least two, and three is not
         * enough to trust a cluster mean: O60, O61 and O62 were the same binary run back to back
         * without the phones being touched, and came out +1.323, -0.927 and +0.097 ms.
         */
        const val CHIRP_REPEATS = 5

        /**
         * Five seconds, run-sync's own floor: a slice of the recording has to hold the record
         * lead, the stagger and the sweep, with the input latency and start jitter on top.
         */
        const val CHIRP_INTERVAL_NANOS = 5_000_000_000L

        /** Long enough for the whole schedule; the exchange runs the length of the calibration. */
        const val CLOCK_SECONDS = 120

        /**
         * How often the clock is exchanged during a calibration, against the harness's own 2000.
         *
         * The estimator's window is sized in exchanges, not in seconds - sixty-four of them, of
         * which the eight quietest are kept, because round trips on one link are bimodal and a
         * quiet one is about an eighth of the traffic. Filling that window at the harness's cadence
         * takes two minutes, which the harness pays for out of its audio segment and a calibration
         * has no reason to pay at all.
         *
         * Sampling faster is safe for the quantity that matters here: the offset is the mean of the
         * kept midpoints anchored at their centroid, never extrapolated, so a drift slope fitted
         * over a shorter span cannot enter it - and the staleness that anchoring costs is half a
         * window of real drift, which a shorter window makes smaller rather than larger. What it
         * cannot rule out is quiet moments on the link being clustered in time, so that sixty-four
         * exchanges over sixteen seconds meet fewer of them than sixty-four over two minutes. That
         * shows up as a wider `uncertaintyNanos`, which every run now records for exactly this.
         */
        const val CLOCK_INTERVAL_MILLIS = 250L

        /**
         * How long the exchange runs before anything is scheduled against it: one window's worth.
         *
         * Derived rather than chosen - the window size times the cadence - because the property
         * being waited for is structural. Below a full window the estimator is still answering
         * from a growing population and its answer moves as it grows, which C1 measured at 8.1 ms
         * across twenty seconds and paid for in the whole run.
         */
        const val CLOCK_FILL_NANOS =
            ClockOffsetEstimator.DEFAULT_WINDOW * CLOCK_INTERVAL_MILLIS * 1_000_000L

        /** SyncActivity's own convergence bound, and it is the first estimate this bounds. */
        const val CONVERGENCE_TIMEOUT_NANOS = 40_000_000_000L

        /** Far finer than the seconds convergence takes, and it costs nothing to wait this way. */
        const val CONVERGENCE_POLL_MILLIS = 50L

        /**
         * How long the run waits for the clock thread to notice it has been stopped.
         *
         * Bounded rather than open ended: the thread is inside a socket receive or a sleep, both of
         * which end promptly, and a run that has already measured everything it came for should
         * report it even if this one join is the thing that hangs.
         */
        const val CLOCK_JOIN_MILLIS = 2_000L

        /** The sink's correlation pass takes seconds; this bounds a sink that died mid-run. */
        const val RESULT_TIMEOUT_MILLIS = 120_000
    }
}
