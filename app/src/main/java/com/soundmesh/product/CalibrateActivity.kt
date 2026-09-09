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
import com.soundmesh.probe.PlaybackUsage
import com.soundmesh.probe.R
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.CalibrationAudioSource
import com.soundmesh.probe.sync.OutputLeadRunner
import com.soundmesh.probe.sync.StoredOutputLead
import com.soundmesh.session.CAPTURING_HOST_USAGE
import java.io.File

/**
 * Measures this handset's own output paths against each other and stores what it finds.
 *
 * The screen exists because the constant it produces used to be measured with two phones, a PC and
 * a cable, and then typed into the device by hand. That works once, for the handsets in this room.
 * It is not a thing anyone else can do, and the product's whole premise is two phones and nobody's
 * computer - so the measurement had to become something a phone does to itself. See
 * [OutputLeadRunner] for why one phone is enough.
 *
 * Nothing starts on its own. A calibration is ninety seconds of tones and chirps that only works in
 * a quiet room with the phone left alone, so the screen explains itself and waits to be told - a
 * button that began playing the moment it was tapped would spend most of its runs measuring
 * somebody putting the phone down.
 *
 * Startable by name as well, with `auto`, because the first thing this had to do was agree with the
 * measurement it replaces: one handset already had a PC-measured answer, and a self-measurement
 * that disagreed with it would have been wrong rather than new.
 */
class CalibrateActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var state by mutableStateOf(CalibrateState())

    /** One calibration at a time: two would share a microphone and a run directory. */
    @Volatile private var running = false

    /** Set by the button that asked for the permission, so the run resumes once it is granted. */
    private var verifyingAfterPermission = false

    private val askRecordAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start(verifyingAfterPermission)
        else state = state.copy(message = getString(R.string.calibrate_no_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The run is a minute and a half of quiet room, and a screen that sleeps takes the CPU too.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CalibrateScreen(
                        state = state.copy(stored = storedMicros()),
                        actions = CalibrateActions(
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
     * Without it the screen sat on the last run's answer and measured nothing - which is how the
     * Magic6's second reading was lost: `am start` reported success, the activity was already up,
     * and three minutes of quiet room bought nothing at all.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("auto", false)) begin(intent.getBooleanExtra("verify", false))
    }

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
        state = state.copy(running = true, message = getString(R.string.calibrate_running))
        // Guarded here rather than inside: an uncaught throw on any thread takes the whole process
        // with it, and a calibration that vanishes tells whoever ran it nothing at all.
        Thread({
            runCatching { measure(verifying) }.onFailure {
                Log.e(LOG_TAG, "the calibration did not finish", it)
                show(getString(R.string.calibrate_failed, it.javaClass.simpleName))
            }
            running = false
            handler.post { state = state.copy(running = false) }
        }, "SoundMeshCalibrate").start()
    }

    private fun measure(verifying: Boolean) {
        // A repeatability run plays the reference path on both passes, so it measures nothing
        // about the paths and everything about the acquisition between them - roadmap item 7,
        // section 23. Driven over adb because it is a diagnostic and not a thing to offer on a
        // screen: `am start -e same_path true -e repeats 20`.
        val samePath = intent.getBooleanExtra("same_path", false)
        val subject =
            if (samePath) PlaybackUsage.MEDIA
            else PlaybackUsage.fromName(intent.getStringExtra("subject") ?: CAPTURING_HOST_USAGE.name)
        val caseId = intent.getStringExtra("case")
            ?: if (samePath) OutputLeadRunner.SAME_PATH_CASE_ID else OutputLeadRunner.DEFAULT_CASE_ID
        // A verification replays the stored answer through the renderer that will use it, so what
        // it reads is what is left over rather than the whole difference. Near zero means the
        // constant is right; near twice its own size means its sign is not.
        val run = OutputLeadRunner(
            runStore = RunStore(filesDir),
            caseId = caseId,
            subject = subject,
            repeats = intent.getIntExtra("repeats", OutputLeadRunner.DEFAULT_REPEATS),
            warmupNanos = intent.getIntExtra("warmup_seconds", 4).toLong() * 1_000_000_000L,
            audioSource = CalibrationAudioSource.parse(intent.getStringExtra("audio_source")),
            samePath = samePath,
            appliedLeadNanos =
                if (verifying && !samePath) (StoredOutputLead(filesDir, subject).read() ?: 0L) * 1_000L else 0L
        ).run()
        // Written before anything is stored, so a refused run still leaves its evidence behind.
        File(RunStore(filesDir).prepareRun(caseId), ARTIFACT).writeText(run.json)
        Log.i(LOG_TAG, run.json)
        val micros = run.result.leadMicros
        // A verification never stores: what it measured is a residual, and writing a residual where
        // the constant lives would quietly halve the correction on every run after it. Nor does a
        // repeatability run, and for a blunter reason: its subject is the reference path, so what
        // it reads is near zero by construction and storing it would wipe the media constant.
        if (micros != null && !verifying && !samePath && intent.getBooleanExtra("apply", true)) {
            StoredOutputLead(filesDir, subject).write(micros)
        }
        show(
            when {
                micros == null -> getString(R.string.calibrate_refused, run.result.refusal ?: "")
                samePath -> getString(
                    R.string.calibrate_repeatability,
                    micros / 1000.0,
                    (run.result.spreadMicros ?: 0L) / 1000.0,
                    run.result.usedReadings
                )
                verifying -> getString(R.string.calibrate_verified, micros / 1000.0)
                else -> getString(R.string.calibrate_done, micros / 1000.0, subject.name)
            }
        )
    }

    /**
     * The constant this handset is carrying, or null on one nobody has calibrated.
     *
     * Read on every recomposition rather than held: the run writes it from another thread, and a
     * remembered copy would leave the screen announcing the answer it had before it measured.
     */
    private fun storedMicros(): Long? =
        StoredOutputLead(filesDir, CAPTURING_HOST_USAGE).read()?.takeIf { it != 0L }

    private fun show(text: String) {
        handler.post { state = state.copy(message = text) }
    }

    private companion object {
        const val LOG_TAG = "SoundMeshCalibrate"
        const val ARTIFACT = "output-lead.json"
    }
}
