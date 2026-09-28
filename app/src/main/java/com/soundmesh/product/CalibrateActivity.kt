package com.soundmesh.product

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.soundmesh.probe.PlaybackUsage
import com.soundmesh.probe.R
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.CalibrationAudioSource
import com.soundmesh.probe.sync.EventLog
import com.soundmesh.probe.sync.HandsetVolume
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

    /** The language this app was told to be, put on before anything here reads a string. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.inChosenLanguage())
    }

    private val handler = Handler(Looper.getMainLooper())
    private var state by mutableStateOf(CalibrateState())

    /** One calibration at a time: two would share a microphone and a run directory. */
    @Volatile private var running = false

    /**
     * Whether the run now going has been called off, by the button or by leaving the screen.
     *
     * Read from the running thread, so volatile. Cleared where a run starts rather than where one
     * ends: a stop that arrives in the last moments of a run would otherwise be cleared by that
     * run on its way out and be waiting, set, for the next press of start.
     */
    @Volatile private var stopping = false

    /**
     * This handset's own volume on the two outputs this measurement plays on.
     *
     * The phone has to hear itself twice - once on the ordinary path, once on the path a
     * capturing host is heard on - and neither of those is something this screen can work out
     * from anything it already knows. So it draws what each one reads back and refuses a run that
     * cannot work. See [tooQuietFor].
     */
    private val handsetVolume by lazy {
        HandsetVolume(getSystemService(AudioManager::class.java), filesDir)
    }

    /** Whether anything had already moved the volume when this screen opened. See [putVolumeBack]. */
    private var volumeChangedBefore = false

    /** Set by the button that asked for the permission, so the run resumes once it is granted. */
    private var verifyingAfterPermission = false

    /** Which output the measurement now on the screen was taken on. See [replace]. */
    private var offeredSubject: PlaybackUsage? = null

    private val askRecordAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start(verifyingAfterPermission)
        else state = state.copy(message = getString(R.string.calibrate_no_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The run is a minute and a half of quiet room, and a screen that sleeps takes the CPU too.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeChangedBefore = handsetVolume.changed()
        setContent {
            SoundMeshTheme(themeChoiceOf(Preferences(filesDir).read("theme"))) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CalibrateScreen(
                        state = state.copy(stored = storedMicros()),
                        actions = CalibrateActions(
                            calibrate = { begin(verifying = false) },
                            setVolume = { percent -> setVolume(percent) },
                            stop = { stop() },
                            replace = { replace() },
                            keep = { state = state.copy(offered = null) },
                            back = { finish() }
                        )
                    )
                }
            }
        }
        // This screen records a minute and a half of chirps through this handset's own microphone,
        // and hushed nothing at all until now - a room playing over it produces a number rather
        // than a failure, which is the shape of fault this project keeps paying for.
        hushOnArrival(this, EventLog(filesDir))
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
        hushOnArrival(this, EventLog(filesDir))
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

    /**
     * What both outputs are at, re-read rather than waited for.
     *
     * A poll because the thing that moves these is somebody pressing the volume keys beside this
     * screen, which this screen has nothing to listen to for. The same interval the room round
     * uses, off the same reasoning.
     */
    private val readVolumes = object : Runnable {
        override fun run() {
            state = state.copy(volumes = ownVolumes())
            handler.postDelayed(this, VOLUME_MILLIS)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(readVolumes)
    }

    override fun onPause() {
        handler.removeCallbacks(readVolumes)
        super.onPause()
    }

    /**
     * Ends the run on the way out as well as on the button.
     *
     * Leaving with the back button used to leave the run going: a minute of chirps into a room
     * whose screen has gone back to the status page, with nothing anywhere to stop it. The
     * activity is gone by then, so what stops it is the same flag the button sets.
     */
    override fun onDestroy() {
        if (isFinishing) {
            stopping = true
            putVolumeBack()
        }
        super.onDestroy()
    }

    /** Both of the outputs this measurement plays on, read back off the handset itself. */
    private fun ownVolumes(): List<VolumeRow> =
        listOf(false to R.string.calibrate_volume_media, true to R.string.calibrate_volume_alarm)
            .map { (capturing, name) ->
                val reading = handsetVolume.read(capturing)
                VolumeRow(
                    reading.stream,
                    getString(name),
                    reading.percent,
                    reading.index,
                    reading.max,
                    reading.stream
                )
            }

    /** Moves both outputs together: the measurement needs to be heard on each of them. */
    private fun setVolume(percent: Int) {
        val now = handsetVolume.setBoth(percent)
        EventLog(filesDir).write(
            "output lead volume: asked $percent%, now " +
                now.joinToString(", ") { "${it.stream} ${it.percent}%" }
        )
        state = state.copy(volumes = ownVolumes())
    }

    /**
     * Puts the handset back on the way out, but only if this screen is what moved it.
     *
     * The volume is set on other screens too, and each of them restores the level from before the
     * app ever touched the handset - so a screen that restored unconditionally would throw away
     * the level somebody had just chosen for the music elsewhere.
     */
    private fun putVolumeBack() {
        if (!restoresOnLeaving(volumeChangedBefore, handsetVolume.changed())) return
        handsetVolume.restore()
        EventLog(filesDir).write("output lead volume put back on leaving the calibration")
    }

    /**
     * Calls the run off: silence as soon as the renderer notices, and nothing stored.
     *
     * The sentence is said here rather than waited for, because what unwinds a run is the run
     * itself reaching its next check - a second or two - and a button that says nothing for a
     * second or two is a button somebody presses again. The run says the same sentence when it
     * gets there, so there is nothing to flicker between.
     */
    private fun stop() {
        if (!running) return
        stopping = true
        state = state.copy(message = getString(R.string.calibrate_stopping), computing = false, until = null)
    }

    private fun start(verifying: Boolean) {
        running = true
        // Cleared here and nowhere else - see [stopping].
        stopping = false
        // No sentence saying a run has started: the screen says so, and the message line here is
        // kept for the runs that come to nothing.
        state = state.copy(running = true, message = null, offered = null, computing = false, until = null, measured = false)
        // Guarded here rather than inside: an uncaught throw on any thread takes the whole process
        // with it, and a calibration that vanishes tells whoever ran it nothing at all.
        Thread({
            runCatching { measure(verifying) }.onFailure {
                Log.e(LOG_TAG, "the calibration did not finish", it)
                // The same sentence as a refusal: what went wrong is in the log above, and a class
                // name on screen is English on every language and tells nobody what to do.
                show(getString(R.string.calibrate_refused))
            }
            running = false
            // Cleared beside running, not instead of it: a run that threw on its way to the
            // analysis never reaches onAnalysing, and one that threw inside it never comes back
            // out - so this is the only place that is reached either way.
            handler.post { state = state.copy(running = false, computing = false, until = null) }
        }, "SoundMeshCalibrate").start()
    }

    private fun measure(verifying: Boolean) {
        // A repeatability run plays the reference path on both passes, so it measures nothing
        // about the paths and everything about the acquisition between them. Driven over adb
        // because it is a diagnostic and not a thing to offer on a screen:
        // `am start -e same_path true -e repeats 20`.
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
                if (verifying && !samePath) (StoredOutputLead(filesDir, subject).read() ?: 0L) * 1_000L else 0L,
            onAnalysing = { handler.post { state = state.copy(computing = true) } },
            keepsRecording = keepsRecordings(filesDir),
            calledOff = { stopping },
            // From the runner's clock onto the screen's: the countdown ticks on elapsedRealtime,
            // as the room round's does.
            onPlanned = { ends ->
                val until = SystemClock.elapsedRealtime() + (ends - System.nanoTime()) / 1_000_000L
                handler.post { state = state.copy(until = until) }
            }
        ).run()
        // Written before anything is stored, so a refused run still leaves its evidence behind.
        File(RunStore(filesDir).prepareRun(caseId), ARTIFACT).writeText(run.json)
        Log.i(LOG_TAG, run.json)
        val micros = run.result.leadMicros
        // A verification never stores: what it measured is a residual, and writing a residual where
        // the constant lives would quietly halve the correction on every run after it. Nor does a
        // repeatability run, and for a blunter reason: its subject is the reference path, so what
        // it reads is near zero by construction and storing it would wipe the media constant.
        val keeps = !verifying && !samePath && intent.getBooleanExtra("apply", true)
        val landing = landingFor(micros, StoredOutputLead(filesDir, subject).read()?.takeIf { it != 0L }, keeps)
        landing.store?.let { StoredOutputLead(filesDir, subject).write(it) }
        // Nothing is said about a run that worked. What it changed is the number at the top of the
        // screen, or the two numbers it is now asking about - a sentence underneath saying it has
        // just measured is the screen reporting on itself.
        val note = when {
            // Before the refusal below it, because a run that was stopped is refused too and the
            // reason it was refused is not news to the person who stopped it.
            stopping -> getString(R.string.calibrate_stopping)
            // The reason itself is English and for whoever reads output-lead.json, where it is
            // kept; on screen it is only what to do about it.
            micros == null -> getString(R.string.calibrate_refused)
            samePath -> getString(
                R.string.calibrate_repeatability,
                micros / 1000.0,
                (run.result.spreadMicros ?: 0L) / 1000.0,
                run.result.usedReadings
            )
            verifying -> getString(R.string.calibrate_verified, micros / 1000.0)
            else -> null
        }
        offeredSubject = subject
        val workedHere = worked(landing, stopping)
        handler.post { state = state.copy(message = note, offered = landing.offer, measured = workedHere) }
    }

    /**
     * Adopts the measurement the screen is holding, against the output it was measured on.
     *
     * The subject is remembered rather than read back off the intent: a run driven by name can
     * name an output other than the capturing one, and writing its answer under the usual output
     * would leave this handset playing off a number measured on something else.
     */
    private fun replace() {
        val micros = state.offered ?: return
        StoredOutputLead(filesDir, offeredSubject ?: CAPTURING_HOST_USAGE).write(micros)
        state = state.copy(offered = null)
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

        /** How often both outputs are read back while this screen is up. See [readVolumes]. */
        const val VOLUME_MILLIS = 500L
    }
}
