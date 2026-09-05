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
import com.soundmesh.core.ClockOffsetEstimator
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
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.SyncActivity
import java.io.File

/**
 * Measures the fixed offset between this handset and the one it is paired with, and stores it.
 *
 * The constant this produces used to take a PC, a cable and four harness runs driven from a
 * command line - which works once, for the two handsets in this room, and is exactly what the
 * product's premise rules out. `SinkSession` only ever read this file; nothing in the product
 * could write it. This is what writes it.
 *
 * The role is not asked for. It comes from the pairing - the handset that showed the code hosts,
 * the one that scanned follows - because the correction is directional, and a role picked by hand
 * is a role picked wrong once, filed under the wrong peer, and applied silently ever after.
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
     * Which side of the pair this handset is on.
     *
     * A handset that scanned somebody's code follows them; one that has not is the handset whose
     * code was scanned. Never null: a phone with no pairing is hosting and has nothing stored to
     * say otherwise, and the sink path refuses on its own when it finds no pairing to read.
     */
    private fun role(): CalibrationRole =
        if (PairedHost(filesDir).read() != null) CalibrationRole.SINK else CalibrationRole.HOST

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
                when (role()) {
                    CalibrationRole.HOST -> measureAsHost()
                    CalibrationRole.SINK -> measureAsSink(verifying)
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
            val plan = planServer.awaitRequest(PLAN_WAIT_MILLIS) {
                CalibrationPlan(
                    caseId = CASE_MEASURE,
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
        val clockThread = Thread({ clockClient.runFor(CLOCK_SECONDS) }, "SoundMeshPeerClock")
        clockThread.start()
        try {
            val deadline = System.nanoTime() + CONVERGENCE_TIMEOUT_NANOS
            while (clockClient.currentEstimate() == null && System.nanoTime() < deadline) {
                Thread.sleep(CONVERGENCE_POLL_MILLIS)
            }
            val converged = clockClient.currentEstimate()
                ?: return show(getString(R.string.pair_calibrate_failed, "CLOCK_NOT_CONVERGED"))
            val plan = CalibrationPlanClient(paired.address, PLAN_PORT).request()
            // The host id is the file name the correction is stored under. A plan from somebody
            // this handset never scanned would file the answer against the wrong peer, and every
            // later session would apply it with nothing in the result to notice it by.
            if (plan.hostId != paired.hostId) {
                return show(getString(R.string.pair_calibrate_failed, "PLAN_FROM_ANOTHER_HOST"))
            }
            show(getString(R.string.pair_calibrate_running))
            val caseId = if (verifying) CASE_VERIFY else plan.caseId
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
            File(RunStore(filesDir).prepareRun(caseId), ARTIFACT).writeText(run.json)
            Log.i(LOG_TAG, run.json)
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

    private fun show(text: String) {
        handler.post { state = state.copy(message = text) }
    }

    private companion object {
        const val LOG_TAG = "SoundMeshPeerCalibrate"
        const val ARTIFACT = "peer-calibration.json"

        /** RunStore accepts `[A-Z][0-9]+`. C for calibration; the verification keeps its own. */
        const val CASE_MEASURE = "C1"
        const val CASE_VERIFY = "C2"

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

        /** SyncActivity's own convergence bound. Eight samples at 2s is sixteen of these seconds. */
        const val CONVERGENCE_TIMEOUT_NANOS = 40_000_000_000L

        /** Far finer than the seconds convergence takes, and it costs nothing to wait this way. */
        const val CONVERGENCE_POLL_MILLIS = 50L

        /** The sink's correlation pass takes seconds; this bounds a sink that died mid-run. */
        const val RESULT_TIMEOUT_MILLIS = 120_000
    }
}
