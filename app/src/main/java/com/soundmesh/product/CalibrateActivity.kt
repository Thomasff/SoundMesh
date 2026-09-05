package com.soundmesh.product

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.soundmesh.probe.PlaybackUsage
import com.soundmesh.probe.R
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.CalibrationAudioSource
import com.soundmesh.probe.sync.OutputLeadRunner
import com.soundmesh.probe.sync.StoredOutputLead
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
 * Startable by name as well as by button, because the first thing this has to do is agree with the
 * measurement it replaces: one handset already has a PC-measured answer, and a self-measurement
 * that disagrees with it is wrong rather than new.
 */
class CalibrateActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    /** One calibration at a time: two would share a microphone and a run directory. */
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The run is a minute of quiet room, and a screen that sleeps takes the CPU with it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
        setContentView(status)
        start()
    }

    /**
     * A second start reaches here rather than [onCreate], because this screen is singleTask.
     *
     * Without it the screen sits on the last run's answer and measures nothing at all - which is
     * how the second Magic6 reading was lost: `am start` returned success, the activity was
     * already up, and three minutes of quiet room bought nothing. A person pressing calibrate
     * twice would have seen exactly the same.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start()
    }

    private fun start() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            show(getString(R.string.calibrate_no_permission))
            return
        }
        running = true
        show(getString(R.string.calibrate_running))
        // Guarded here rather than inside: an uncaught throw on any thread takes the whole process
        // with it, and a calibration that vanishes tells whoever ran it nothing at all.
        Thread({
            runCatching { measure() }.onFailure {
                Log.e(LOG_TAG, "the calibration did not finish", it)
                handler.post { show(getString(R.string.calibrate_failed, it.javaClass.simpleName)) }
            }
            running = false
        }, "SoundMeshCalibrate").start()
    }

    private fun measure() {
        val subject = PlaybackUsage.fromName(intent.getStringExtra("subject") ?: PlaybackUsage.ACCESSIBILITY.name)
        val caseId = intent.getStringExtra("case") ?: OutputLeadRunner.DEFAULT_CASE_ID
        // A verification replays the stored answer through the renderer that will use it, so what
        // it reads is what is left over rather than the whole difference. Near zero means the
        // constant is right; near twice its own size means its sign is not.
        val verifying = intent.getBooleanExtra("verify", false)
        val run = OutputLeadRunner(
            runStore = RunStore(filesDir),
            caseId = caseId,
            subject = subject,
            repeats = intent.getIntExtra("repeats", OutputLeadRunner.DEFAULT_REPEATS),
            warmupNanos = intent.getIntExtra("warmup_seconds", 4).toLong() * 1_000_000_000L,
            audioSource = CalibrationAudioSource.parse(intent.getStringExtra("audio_source")),
            appliedLeadNanos = if (verifying) (StoredOutputLead(filesDir, subject).read() ?: 0L) * 1_000L else 0L
        ).run()
        // Written before anything is stored, so a refused run still leaves its evidence behind.
        File(RunStore(filesDir).prepareRun(caseId), ARTIFACT).writeText(run.json)
        Log.i(LOG_TAG, run.json)
        val micros = run.result.leadMicros
        // A verification never stores: what it measured is a residual, and writing a residual where
        // the constant lives would quietly halve the correction on every run after it.
        if (micros != null && !verifying && intent.getBooleanExtra("apply", true)) {
            StoredOutputLead(filesDir, subject).write(micros)
        }
        handler.post {
            show(
                if (micros == null) getString(R.string.calibrate_refused, run.result.refusal ?: "")
                else getString(R.string.calibrate_done, micros / 1000.0, subject.name)
            )
        }
    }

    private fun show(text: String) {
        status.text = text
    }

    private companion object {
        const val LOG_TAG = "SoundMeshCalibrate"
        const val ARTIFACT = "output-lead.json"
    }
}
