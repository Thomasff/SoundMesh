package com.soundmesh.product

import android.Manifest
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            show(getString(R.string.calibrate_no_permission))
            return
        }
        show(getString(R.string.calibrate_running))
        Thread(::measure, "SoundMeshCalibrate").start()
    }

    private fun measure() {
        val subject = PlaybackUsage.fromName(intent.getStringExtra("subject") ?: PlaybackUsage.ACCESSIBILITY.name)
        val caseId = intent.getStringExtra("case") ?: DEFAULT_CASE
        val run = runCatching {
            OutputLeadRunner(
                runStore = RunStore(filesDir),
                caseId = caseId,
                subject = subject,
                repeats = intent.getIntExtra("repeats", OutputLeadRunner.DEFAULT_REPEATS),
                warmupNanos = intent.getIntExtra("warmup_seconds", 4).toLong() * 1_000_000_000L,
                audioSource = CalibrationAudioSource.parse(intent.getStringExtra("audio_source"))
            ).run()
        }.getOrElse {
            Log.e(LOG_TAG, "the calibration did not finish", it)
            handler.post { show(getString(R.string.calibrate_failed, it.javaClass.simpleName)) }
            return
        }
        // Written before anything is stored, so a refused run still leaves its evidence behind.
        File(RunStore(filesDir).prepareRun(caseId), ARTIFACT).writeText(run.json)
        Log.i(LOG_TAG, run.json)
        val micros = run.result.leadMicros
        if (micros != null && intent.getBooleanExtra("apply", true)) {
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
        const val DEFAULT_CASE = "output-lead"
        const val ARTIFACT = "output-lead.json"
    }
}
