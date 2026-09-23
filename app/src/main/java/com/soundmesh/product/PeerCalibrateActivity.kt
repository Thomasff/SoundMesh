package com.soundmesh.product

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.AlignmentPairing
import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.AlignmentVerdict
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.PeerBadge
import com.soundmesh.core.CalibrationReply
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.FacingPair
import com.soundmesh.core.HostId
import com.soundmesh.core.CalibrationUpdate
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.EmissionDeviation
import com.soundmesh.core.LinkQuality
import com.soundmesh.core.LinkSurvey
import com.soundmesh.core.PairedAlignment
import com.soundmesh.core.RoomReply
import com.soundmesh.core.RoomResultMessage
import com.soundmesh.core.RunVerdict
import com.soundmesh.probe.R
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.AlignmentResultClient
import com.soundmesh.probe.sync.AlignmentResultServer
import com.soundmesh.probe.sync.Calibration
import com.soundmesh.probe.sync.CalibrationPlanClient
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomOrder
import com.soundmesh.core.RoomExcuse
import com.soundmesh.probe.sync.CalibrationPlanServer
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.RoomCommands
import com.soundmesh.probe.sync.tellHostWhy
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.HandsetVolume
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.handsetName
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.CalibrationAudioSource
import com.soundmesh.probe.sync.handsetPeerCalibrationRunner
import com.soundmesh.probe.sync.PeerRunLog
import com.soundmesh.probe.sync.RoomResultClient
import com.soundmesh.probe.sync.RoomResultServer
import com.soundmesh.probe.sync.StoredApproximateCalibration
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.StoredRoomField
import com.soundmesh.probe.sync.StoredListenerDistance
import com.soundmesh.probe.sync.EventLog
import java.util.Collections
import java.util.Locale
import com.soundmesh.probe.sync.StoredSeparation
import com.soundmesh.probe.sync.SyncActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the stop button can mean for the arm about to run. See [StopOffer].
 *
 * Either job begins at [StopOffer.BEFORE_SOUND] and moves to [StopOffer.UNDER_WAY] the moment the
 * schedule is handed out. What changes there is what the press costs, not whether it works: before
 * it, the round is called off by refusing to answer for it; after it, by saying so over the
 * standing channel and discarding what has been measured so far.
 *
 * Which job is running used to change the answer, while the pair host served a queue and had a
 * next handset to stop waiting for. It does not any more, so it is not asked about.
 */
internal fun stopOfferFor(role: CalibrationRole?): StopOffer =
    if (role != CalibrationRole.HOST) StopOffer.NONE else StopOffer.BEFORE_SOUND

/** What one round of serving one sink came to, for the timeline that records it. */
internal enum class RoundResult {
    /** A handset was measured. Whatever verdict it got, the round did its job. */
    SERVED,

    /** The wait for somebody to ask ran out. */
    NOBODY_ASKED,

    /** This round broke. */
    FAILED
}

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

    /** The language this app was told to be, put on before anything here reads a string. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.inChosenLanguage())
    }

    private val handler = Handler(Looper.getMainLooper())

    /** One timeline, shared with every other part of the app. See [EventLog]. */
    private val events: EventLog by lazy { EventLog(filesDir) }
    private var state by mutableStateOf(PeerCalibrateState())

    /**
     * This handset's own volume, and the room's, while this screen is up.
     *
     * The room has to be loud enough for every handset to be heard by every other one, and that is
     * not a thing this screen can work out from anything it already knows - so it draws what each
     * handset reads back and refuses a round that cannot work. See [tooQuietFor].
     */
    private val handsetVolume by lazy {
        HandsetVolume(getSystemService(AudioManager::class.java), filesDir)
    }

    /**
     * Whether anything had already moved the volume when this screen opened.
     *
     * Read once, before this screen touches anything, because what it decides is whether leaving
     * here puts the room back - and that has to be about the state this screen found rather than
     * the state it made. See [restoresOnLeaving].
     */
    private var volumeChangedBefore = false

    /** Which colour each handset is drawn in, off the saved drawing. Re-read on every resume. */
    private var colours by mutableStateOf(emptyMap<String, Int>())

    /**
     * Which of the position calibration's two steps is the one to do now.
     *
     * Held here rather than in the screen because the screen is composed afresh every time a round
     * hands this activity a new intent - see [restartAsRoom] - and a step remembered inside it
     * would go back to the first one each time a round ended. It starts at the first step on every
     * visit: the one it measures is where somebody is sitting, which is a thing that changes
     * between one evening and the next, and the step below it says out loud that it can be skipped.
     */
    private var roomStep by mutableStateOf(1)

    /**
     * Which step the result now on screen came from, or null while there is no such result.
     *
     * The walk-through this sits beside only goes forwards, so it is the whole of the way back:
     * see [PeerCalibrateState.redo]. Set where a round finishes and cleared where one starts,
     * because what it has to name is the round whose answer is on the screen - an aborted round
     * leaves a message rather than an answer, and offering to run "that step" again beside it
     * would be naming a step this screen did not measure.
     */
    private var roomRedo: Int? by mutableStateOf(null)

    /** One calibration at a time: two would share a microphone, a port and a run directory. */
    @Volatile private var running = false

    /**
     * Set by the stop button, and read everywhere a round waits.
     *
     * It used to be read only between rounds, on the argument that a round part-way through is a
     * measurement part-way through and there is no gain in throwing one away. That argument was
     * about the measurement and the button is not: somebody presses it because two phones are
     * chirping in a room where that has become the wrong thing to be doing - the handsets are
     * placed wrong, or somebody has started talking - and waiting out the minute buys a number
     * that is going to be discarded anyway. So a press now ends the round rather than the
     * session: see [PeerCalibrationRunner] for the instants that read it here, and
     * [RoomCommand.CALL_OFF] for how the other handset is told.
     */
    @Volatile private var stopping = false

    /**
     * The plan server of a host session in flight, or null when none is serving.
     *
     * Held here only so the stop button can reach it. The run thread spends most of a session
     * parked in that server's accept(), and closing the socket is the only thing that wakes it -
     * without which the button would take up to five minutes to have any visible effect, which is
     * indistinguishable from a button that does not work.
     */
    @Volatile private var hostPlanServer: CalibrationPlanServer? = null

    /**
     * Whether the WiFi radio was actually held out of power save for this run.
     *
     * On the record rather than assumed: the lock is best effort, and a run whose lock quietly did
     * nothing looks exactly like a run that proves power save is irrelevant.
     */
    @Volatile private var radioHeld = false

    /**
     * Whether this round ended because the host called it off, as opposed to ending on its own.
     *
     * Read by [putItselfAway]: a called-off round has no result for anybody to read here, and the
     * person who pressed the button is standing at the host watching for these handsets to come
     * back to their home screens. Every millisecond spent lingering is a millisecond in which the
     * next press reaches nobody.
     */
    @Volatile private var roundCalledOff = false

    /** Set by the button that asked for the permission, so the run resumes once it is granted. */
    private var verifyingAfterPermission = false
    private var allowSlowLinkAfterPermission = false

    /**
     * The schedule this round is running, set the moment its case is known and read everywhere
     * afterwards - including by the report, so a run says which arm it was on rather than what
     * the command line happened to ask for.
     */
    @Volatile
    private var timing = defaultTimingFor(null)

    private val askRecordAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start(verifyingAfterPermission, allowSlowLinkAfterPermission)
        else state = state.copy(message = getString(R.string.pair_calibrate_no_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A minute of quiet room, and a screen that sleeps takes the CPU with it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeChangedBefore = handsetVolume.changed()
        setContent {
            SoundMeshTheme(themeChoiceOf(Preferences(filesDir).read("theme"))) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val stored = storedCalibration()
                    PeerCalibrateScreen(
                        state = state.copy(
                            role = role(),
                            stored = stored?.micros,
                            approximate = approximateCalibration(),
                            observations = stored?.observations ?: 0,
                            step = roomStep,
                            redo = roomRedo,
                            colours = colours
                        ),
                        // Unrecognised or absent means PAIR - see peerJobOf - which is what every
                        // ADB-driven `am start` of this activity has always meant with no extra.
                        job = peerJobOf(intent.getStringExtra(PEER_JOB_EXTRA)),
                        actions = PeerCalibrateActions(
                            calibrate = { begin(verifying = false, allowSlowLink = false) },
                            skipStep = { roomStep = 2 },
                            back = { finish() },
                            setRoomVolume = { percent -> setRoomVolume(percent) },
                            verify = { begin(verifying = true, allowSlowLink = false) },
                            forget = { forget() },
                            stop = { stopServing() },
                            measureRoom = { restartAsRoom(overhead = false) },
                            measureOverhead = { restartAsRoom(overhead = true) },
                            moveIcon = { moved -> changeRoom { withIconMoved(it, moved) } },
                            fitRoom = {
                                changeRoom { room ->
                                    fitOffer(room)?.let {
                                        room.copy(
                                            icons = it.icons,
                                            metresPerUnit = it.metresPerUnit,
                                            fitted = true
                                        )
                                    } ?: room
                                }
                            }
                        )
                    )
                }
            }
        }
        // Before anything is pressed. See hushOnArrival.
        hushOnArrival(this, events)
        if (intent.getBooleanExtra("auto", false)) beginFrom(intent)
    }

    /**
     * Starts a room round by handing this screen a fresh intent instead of a fresh argument.
     *
     * The intent is this class's record of which arm is running: [roomAsked] and [overhead] are
     * read off it at five places between naming the case and filing the answer, and onNewIntent
     * already replaces it. Threading two more flags through begin() would have put a second
     * answer to "which arm is this" beside the one that exists, which is the fault [which arm
     * ran] keeps costing this project a session at a time.
     */
    private fun restartAsRoom(overhead: Boolean) {
        if (running) return
        startActivity(
            Intent(this, PeerCalibrateActivity::class.java)
                .putExtra("role", role()?.name)
                .putExtra(PEER_JOB_EXTRA, PeerJob.ROOM.name)
                .putExtra("room", true)
                .putExtra("overhead", overhead)
                .putExtra("auto", true)
        )
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
        hushOnArrival(this, events)
        if (intent.getBooleanExtra("auto", false)) beginFrom(intent)
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

    /**
     * The next three are read off the intent the way [role] is, rather than threaded through
     * [begin], because the screen is singleTask and onNewIntent calls setIntent: the intent is
     * always the one that started the run in progress. Threading them would have added three
     * parameters to four signatures and three more fields to hold across the permission prompt.
     *
     * All three are diagnostic arms of one experiment (queue item 16 and 20a), reachable only from
     * a command line, and each is written into the run's report by the side that honours it. None
     * changes what a listener's handset does: the defaults are the shipped constants.
     *
     * Note the flag types. `--ei` and `--ez`, not `-e`: a string extra read as an int or a boolean
     * returns the default with only a log line to say so, and the run then measures the shipped arm
     * while the command line says otherwise. That has already cost this project one silent session.
     */
    /**
     * The schedule this round runs: the arm's own defaults, with anything the command line asked
     * for on top.
     *
     * Every number here is reachable, and every one of them is written into the report by the side
     * that honours it. A knob that cannot be turned cannot be swept, and the interval is the one
     * whose floor had to be found by sweeping - the arithmetic said 1.5 s and the handsets said
     * otherwise.
     */
    private fun timingFor(caseId: String?): ArmSchedule {
        val shipped = defaultTimingFor(caseId)
        return shipped.copy(
            repeats = intent.getIntExtra("chirp_repeats", shipped.repeats),
            intervalNanos = millisExtra("chirp_interval_millis", shipped.intervalNanos),
            planLeadNanos = millisExtra("plan_lead_millis", shipped.planLeadNanos),
            clockFillNanos = millisExtra("clock_fill_millis", shipped.clockFillNanos)
        )
    }

    private fun millisExtra(name: String, fallbackNanos: Long): Long =
        intent.getIntExtra(name, (fallbackNanos / 1_000_000L).toInt()) * 1_000_000L

    /** `--ez frozen_count true` restores the pre-section-26 rule. See [ClockOffsetEstimator]. */
    private fun keepFractionWhileFilling(): Boolean = !intent.getBooleanExtra("frozen_count", false)

    /**
     * How long to let the window fill before asking for a plan. `--ei clock_fill_millis 0`
     * fires the first chirp as soon as the estimator will answer at all.
     *
     * [planLeadNanos] cannot reach this: the lead moves the first chirp relative to the plan
     * request, and this wait ends before that request is made. Section 26 found the published
     * offset biased +5.10 ms over the first four seconds and was never checked acoustically
     * because of exactly that - measured 09-10, a lead of 7000 put the first chirp at 22.9 s
     * of estimator life and a lead of 2000 at 18.0 s, both far outside the four.
     *
     * Shipping keeps the full wait, and [CLOCK_FILL_NANOS] carries why. What this opens is the
     * run that can say whether the wait is still buying anything under the section-26 rule -
     * if it is not, sixteen seconds come off every calibration.
     */
    /**
     * `--ez distance_only true` measures how far apart the two handsets are and nothing else.
     *
     * Command line only for now, because what reads the answer does not exist yet: the room
     * screen scales a drawing by it, and until it does, a listener offered this button would be
     * standing still for a number nothing displays. The measurement itself is a product one -
     * this is not an experiment arm and does not belong beside [allow_slow_link] in that sense.
     */
    private fun distanceOnly(): Boolean = intent.getBooleanExtra("distance_only", false)

    /**
     * `--ez room true` measures the whole room in one window instead of one pair at a time.
     *
     * On screen since 09-11, under "几台一起量": the room panel draws the lengths and moves the
     * icons onto them, so the answer this produces is now something a person can look at.
     * [restartAsRoom] is how the button reaches this, and the command line still does too.
     *
     * Needed on every handset in the room: the host gathers under it and each sink asks under
     * it. A handset left without it runs the pair arm, and the two flows refuse each other's
     * cases rather than producing a plausible schedule for a run nobody is running. That is why
     * the button is on both roles' screens and the wording tells everybody to press it.
     */
    private fun roomAsked(): Boolean = intent.getBooleanExtra("room", false)

    /**
     * The one handset this pair round is for, or null when nobody was named.
     *
     * Named only from the status screen's per-handset row, which is the only place that knows
     * which handset is being pointed at. Null everywhere else - including every command-line arm -
     * and then this screen behaves as it always has: it serves whichever sink presses start.
     */
    private fun aimedAt(): String? = intent.getStringExtra(AIMED_AT_EXTRA)

    /**
     * `--ez overhead true` says this handset is being held above somebody's head, not standing
     * where it will play.
     *
     * It changes nothing about the measurement and everything about where the answer is filed.
     * The distances this handset is an end of are the listener's - the one thing in the room
     * nobody has ever been able to measure - and the ones it is not an end of are ordinary
     * separations, unaffected by where this handset happens to be. Filed together they would be
     * the same two names meaning two different things, and the second round would erase the first.
     *
     * Only the handset being held needs it: it is the only one that writes anything, and only
     * the host can be it - so the button that sets this is offered to the host alone.
     */
    private fun overhead(): Boolean = intent.getBooleanExtra("overhead", false)

    /**
     * The capture source, default MIC as every archived run used.
     *
     * MIC is the vendor processing chain, whose convergence is time-varying and could be landing on
     * the chirp onset; UNPROCESSED is the control. CalibrationRunner falls back if the source will
     * not open, and records which it opened, so asking is not the same as getting.
     */
    private fun audioSource(): CalibrationAudioSource =
        CalibrationAudioSource.parse(intent.getStringExtra("audio_source"))

    /**
     * The ADB-driven start, which serves one handset - the same one round the screen now runs.
     *
     * One round is what every archived run of this screen did, and it is what a driver expects: a
     * host that went on serving would still be running five minutes later, and the next
     * `am start` would find [running] set and be ignored - which looks from outside like a
     * command that succeeded and measured nothing.
     */
    private fun beginFrom(intent: Intent) = begin(
        verifying = intent.getBooleanExtra("verify", false),
        // Deliberately reachable only from a command line. The gate exists because a link this
        // slow cannot be aligned by any estimator, so a listener who got past it by pressing
        // something would be handed a correction measured on a link that cannot carry one - and
        // it would then be applied to every session afterwards with nothing to notice it by.
        // What is on the other side of it is an experiment: measure the network asymmetry
        // acoustically on a link slow enough to have one worth measuring.
        allowSlowLink = intent.getBooleanExtra("allow_slow_link", false)
    )

    private fun begin(verifying: Boolean, allowSlowLink: Boolean) {
        if (running) return tellHost(RoomExcuse.BUSY)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Said before the dialog goes up, not after it is answered. The dialog is on this
            // handset's screen and the person is standing at a different one, which is the whole
            // of the problem: on 09-13 a room waited out its window for a phone that was waiting
            // for somebody who had no way to know it was waiting.
            tellHost(RoomExcuse.NO_MICROPHONE)
            verifyingAfterPermission = verifying
            allowSlowLinkAfterPermission = allowSlowLink
            askRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        start(verifying, allowSlowLink)
    }

    /**
     * Tells the host why this handset is not going to measure.
     *
     * Only a sink has anybody to tell, and only one that has scanned a pairing code knows an
     * address to tell it at - a handset that has never been paired is invisible to the host by
     * construction, and no channel added here would reach it.
     */
    private fun tellHost(excuse: RoomExcuse) {
        if (role() != CalibrationRole.SINK) return
        val paired = PairedHost(filesDir).read() ?: return
        events.write("excuse-told ${excuse.name}")
        tellHostWhy(paired.address, COMMAND_PORT, HostIdentity(filesDir).current(), excuse)
    }

    private fun start(verifying: Boolean, allowSlowLink: Boolean = false) {
        running = true
        // Cleared here rather than when the session ends, so a stop pressed as the last round
        // finished cannot end the next session before it has served anybody.
        stopping = false
        roundCalledOff = false
        MeasuringNow.calledOff = false
        // The last round's second chance goes with the last round's answer. Whether this run
        // earns one is decided where it finishes, so a run that is called off or throws leaves
        // none - see [roomRedo].
        roomRedo = null
        state = state.copy(
            running = true,
            stopOffer = stopOfferFor(role()),
            message = getString(R.string.pair_calibrate_waiting)
        )
        // Guarded here rather than inside: an uncaught throw on any thread takes the whole process
        // with it, and a calibration that vanishes tells whoever ran it nothing at all.
        Thread({
            runCatching {
                // Held across the whole run and on both sides. An access point buffers frames for
                // a station that is asleep, and the reply half of an exchange is as much of the
                // round trip as the request half - a host dozing costs the sink exactly the same
                // milliseconds. Whether it was actually taken is recorded, not assumed.
                // Held across the whole run and on both sides, and shared with StandbyService so
                // that a round nobody is looking at is held awake exactly the same way.
                // Before anything opens a microphone, and on this side too: the handset that
                // gathers a room records it like every other, so a host measuring through its own
                // music gets the same confident wrong answer a sink would.
                hushWhateverIsPlaying(this, events)
                withRadioAwake(this, events, held = { radioHeld = it }) {
                    when (role()) {
                        // A room and a pair are different runs rather than a wider and a
                        // narrower one: this host gathers everybody and hands out one
                        // schedule naming all of them, where the pair host serves a queue.
                        CalibrationRole.HOST ->
                            if (roomAsked()) measureAsRoom() else measureAsHost()
                        CalibrationRole.SINK -> measureAsSink(verifying, allowSlowLink)
                        null -> show(getString(R.string.pair_calibrate_no_role))
                    }
                }
            }.onFailure {
                Log.e(LOG_TAG, "the pair calibration did not finish", it)
                events.write("calibration-failed ${it.javaClass.simpleName}: ${it.message}")
                show(getString(R.string.pair_calibrate_failed, it.javaClass.simpleName))
            }
            running = false
            handler.post {
                state = state.copy(running = false, stopOffer = StopOffer.NONE)
                putItselfAway()
            }
        }, "SoundMeshPeerCalibrate").start()
    }

    /**
     * Goes back where it came from, for a round nobody opened this screen to watch.
     *
     * A handset that got here because its host said so has to leave the same way. Left sitting on
     * a finished result it is holding the clock port - which the next round and the next session
     * both need - and it is not standing by either, because the channel only lives on the home
     * screen. So the host asks the room to measure again and nothing answers, then asks it to
     * play and nothing answers, and every one of those looks like the network. Reported on 09-11
     * as a room that measured once and then would not do anything at all.
     *
     * A few seconds late, so whatever the round has to say is on screen long enough to read.
     */
    private fun putItselfAway() {
        if (!intent.getBooleanExtra("sent", false)) return
        handler.postDelayed({ if (!running) finish() }, if (roundCalledOff) 0L else LINGER_MILLIS)
    }

    /**
     * A round nobody is looking at is a round nobody wants.
     *
     * Without this, leaving this screen with the back button leaves the run going: it holds the
     * clock port, the room port and the plan port, and the next thing to want any of them - a
     * session on the home screen, or a second round from a fresh instance of this screen - fails
     * to bind. The screen it fails on is a new instance with nothing running, so it says there is
     * no calibration in flight while the ports say otherwise. That is 09-11, exactly: "回到主页,
     * 点击播放,显示放不了,让我停止对时。但是我到对时页面发现并没有正在对时".
     */
    override fun onResume() {
        super.onResume()
        MeasuringNow.onScreen = true
        colours = runCatching { StoredRoomDrawing(filesDir).read()?.room?.colours }
            .getOrNull().orEmpty()
        handler.post(readVolumes)
    }

    override fun onPause() {
        MeasuringNow.onScreen = false
        handler.removeCallbacks(readVolumes)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopServing()
            putVolumeBack()
        }
        super.onDestroy()
    }

    /**
     * What every handset is at, re-read rather than waited for.
     *
     * A poll because the thing that moves these is somebody pressing the volume keys on a phone,
     * which happens beside this screen rather than because of it - on this handset there is
     * nothing to listen to at all, and on the others the report arrives whenever it arrives.
     */
    private val readVolumes = object : Runnable {
        override fun run() {
            if (role() == CalibrationRole.HOST) state = state.copy(volumes = roomVolumes())
            handler.postDelayed(this, VOLUME_MILLIS)
        }
    }

    /**
     * What each handset in the room actually landed on, this one first.
     *
     * This handset's row is read off its own streams here and now; everybody else's is what they
     * said after setting theirs. Neither is what was asked for, which is the point - a stream that
     * took a value and did not move is only visible in what it reads back.
     */
    private fun roomVolumes(): List<VolumeRow> {
        val mine = handsetVolume.read(capturing = false)
        return listOf(
            VolumeRow(
                HostIdentity(filesDir).current(),
                handsetName(this),
                mine.percent,
                mine.index,
                mine.max,
                mine.stream
            )
        ) + RoomCommands.volumes().map { (peerId, said) ->
            VolumeRow(
                peerId,
                RoomCommands.nameOf(peerId) ?: peerId.takeLast(SHORT_NAME_CHARACTERS),
                said.percent,
                said.index,
                said.max,
                said.stream
            )
        }
    }

    /** Tells the whole room, this handset included, what volume to be. */
    private fun setRoomVolume(percent: Int) {
        val now = handsetVolume.set(percent, capturing = false)
        RoomCommands.send(RoomOrder(RoomCommand.SET_VOLUME, percent))
        events.write("room volume for a round: asked $percent%, this handset is ${now.percent}%")
        state = state.copy(volumes = roomVolumes())
    }

    /**
     * Puts the room back on the way out, but only if this screen is what moved it.
     *
     * Only the host does it, and only over the standing line: each handset holds what it was at
     * before this app touched it, on its own disk, so one command is enough and it survives a
     * handset that has been restarted since.
     */
    private fun putVolumeBack() {
        if (role() != CalibrationRole.HOST) return
        if (!restoresOnLeaving(volumeChangedBefore, handsetVolume.changed())) return
        RoomCommands.send(RoomCommand.RESTORE_VOLUME)
        handsetVolume.restore()
        events.write("room volume put back on leaving the calibration")
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
        // Reachable from the stop button, which ends the wait by closing the socket this thread is
        // parked in accept() on. Without that the button does nothing visible for five minutes.
        hostPlanServer = planServer
        try {
            clockServer.start()
            resultServer.start()
            planServer.start()
            // Said out loud at both ends, because these three are the same ports a session wants
            // and the second feature to ask is refused with nothing but a Java class name. A user
            // hit exactly that: calibration finished at 21:35 and a session still could not bind
            // at 21:38, and there was no way afterwards to tell whether the stop button had been
            // pressed at all. These two lines make the next occurrence answerable.
            Log.i(LOG_TAG, "the calibration servers are up: clock ${SyncActivity.CLOCK_PORT}, " +
                "result ${SyncActivity.RESULT_PORT}, plan $PLAN_PORT")
            // After the three servers are up and not before, which is the whole of the ordering
            // problem this replaces: the handset being told goes straight for the plan port, and
            // a sink that got there first found nothing listening and gave up.
            aimedAt()?.let { peerId ->
                val reached = RoomCommands.sendTo(peerId, RoomOrder(RoomCommand.MEASURE_PAIR))
                events.write("pair-told $peerId, reached=$reached")
                if (!reached) show(getString(R.string.pair_calibrate_aimed_gone))
            }
            // One round, one handset. It used to serve a queue - press once, then walk from phone
            // to phone pressing start on each - which is not what the chip on a roster line means:
            // that chip names one handset, and it is the handset this round is with. What the
            // queue cost was a host that never stopped on its own, waiting five minutes for a
            // second volunteer that nothing on screen had asked for.
            events.write("pair-round " + serveOneSink(hostId, planServer, resultServer))
        } finally {
            hostPlanServer = null
            planServer.stop()
            resultServer.stop()
            clockServer.stop()
            Log.i(LOG_TAG, "the calibration servers are down; the clock port is free again")
        }
    }

    /**
     * The room's part: gather everybody, hand out one schedule, chirp in this handset's own
     * slot, then combine every pair out of what they all heard.
     *
     * Not [measureAsHost] with more handsets in it. That one serves a queue - one sink asks,
     * gets a plan naming the two of them, and is answered before the next is let in. A room's
     * schedule names every slot, so it does not exist until everybody has asked; and the pair
     * between two sinks is made of two deliveries that neither of them can combine.
     */
    private fun measureAsRoom() {
        val hostId = HostIdentity(filesDir).current()
        val clockServer = ClockSyncServer(SyncActivity.CLOCK_PORT)
        val roomServer = RoomResultServer(ROOM_PORT)
        val planServer = CalibrationPlanServer(PLAN_PORT)
        // Reachable from the stop button, which ends the wait by closing the socket this
        // thread is parked in accept() on.
        hostPlanServer = planServer
        try {
            clockServer.start()
            roomServer.start()
            planServer.start()
            // Every server is bound, so this is the first instant a handset dialling in would be
            // answered rather than refused: CalibrationPlanClient opens a socket and throws if
            // nothing is listening, it does not retry. So the room is told from here and not from
            // the screen that started this - which is also why the command server outlives that
            // screen. Handsets not standing by are unaffected; somebody presses those by hand.
            // How many it was said to, kept for the whole wait. The count of handsets standing
            // by is not the denominator to show against arrivals: obeying means leaving the
            // home screen, so a handset that is on its way here has already stopped being
            // counted, and a screen reading "1 of 0" says nothing anybody can act on.
            // Cleared before the ask, because an excuse is about one press of one button: a
            // handset that could not measure an hour ago is not a fact about this round.
            RoomCommands.forgetExcuses()
            // And the lines on screen with them, for the same reason: they are one per handset
            // and they are about the round that just ran.
            handler.post { state = state.copy(outcomes = emptyList()) }
            // Heard live rather than collected afterwards, and that is the whole value of it: a
            // handset waiting on its own permission dialog is fixable in the ten seconds before
            // the window closes and unfixable a minute later.
            //
            // Into the per-handset lines rather than the one message, which is what 09-13 got
            // wrong: the excuse arrived 152 ms after the room opened and the count of who had
            // joined overwrote it 1.9 seconds later, so it was on screen for under two seconds
            // and the person it was for never saw it. There is one message and every later write
            // wins; these lines are one per handset and stay.
            // Kept as names rather than a count, so a handset that excuses twice is still one
            // handset. The room is complete when everybody told has either asked or said why not,
            // and a count would let one noisy refusal close the room on somebody still arriving.
            val excused = Collections.synchronizedSet(HashSet<String>())
            RoomCommands.listenForExcuses { peerId, excuse ->
                events.write("room-excuse $peerId ${excuse.name}")
                record(peerId, reasonFor(excuse))
                excused += peerId
            }
            // Handsets told to leave their home screens a moment ago are on their way back to
            // them, and a room told while they are in the air reaches nobody at all. Reported on
            // 09-13: call a round off, press again straight away, and the host says it told
            // nobody. This costs nothing when they are already there - the first look answers.
            if (!awaitBriefly(ROOM_RETURN_GRACE_MILLIS) { RoomCommands.standingBy() > 0 }) {
                events.write("room-nobody-standing after ${ROOM_RETURN_GRACE_MILLIS}ms of waiting")
            }
            val told = RoomCommands.send(
                if (overhead()) RoomCommand.MEASURE_OVERHEAD else RoomCommand.MEASURE_ROOM
            )
            Log.i(
                LOG_TAG,
                "the room servers are up: clock ${SyncActivity.CLOCK_PORT}, " +
                    "room $ROOM_PORT, plan $PLAN_PORT"
            )
            show(
                if (told == 0) getString(R.string.pair_calibrate_room_told_nobody)
                else getString(R.string.pair_calibrate_room_waiting, told, ROOM_WINDOW_MILLIS / 1000)
            )
            events.write(
                "room-gathering opened as ${if (overhead()) "overhead" else "room"}, " +
                    "told $told handsets, waiting up to ${ROOM_WINDOW_MILLIS / 1000}s"
            )
            timing = timingFor(CASE_ROOM)
            val plan = planServer.awaitRoom(
                PLAN_WAIT_MILLIS,
                ROOM_SETTLE_MILLIS,
                ROOM_WINDOW_MILLIS,
                onJoined = { joined ->
                    events.write("room-joined $joined of $told told")
                    show(getString(R.string.pair_calibrate_room_joined, joined, told))
                },
                // Everybody told has either asked or said why not, so there is nobody left to
                // wait for. Guarded on having told anybody at all: a round nobody was told about
                // would otherwise be complete the moment one stranger asked.
                enough = { joined ->
                    told > 0 && joined >= told - excused.size
                }
            ) { asks ->
                // Every ask has to be this arm's. A handset running the pair flow would be
                // handed a slot it never agreed to chirp in, and the room would then hold one
                // silent slot with nothing afterwards saying whose it was.
                asks.firstOrNull { it.caseId != CASE_ROOM }?.let {
                    throw IllegalArgumentException("not a room this handset runs: ${it.caseId}")
                }
                // The names reach a file name on this side too, on the same terms as the pair
                // path: checked for shape here rather than trusted from where they came.
                asks.firstOrNull { !HostId.isValid(it.sinkId) }?.let {
                    throw IllegalArgumentException("not a handset name: ${it.sinkId}")
                }
                // The host takes the last slot, which is the convention combineFacing's signs
                // are written in and the one CalibrationSchedule's role overload encodes.
                val slots = asks.map { it.sinkId } + hostId
                events.write("room-gathered ${slots.size} handsets: ${slots.joinToString(" ")}")
                // Said out loud, because a handset asking twice is the visible end of something
                // that went wrong out of sight - on 09-13 it was one stuck behind a permission
                // dialog resuming a round that had already ended.
                if (planServer.supersededAsks > 0) events.write(
                    "room-superseded ${planServer.supersededAsks}: a handset asked twice and " +
                        "the older ask was dropped"
                )
                require(slots.size == slots.distinct().size) {
                    "one handset asked twice, and a room names each of them once: $slots"
                }
                CalibrationPlan(
                    caseId = CASE_ROOM,
                    hostId = hostId,
                    firstChirpAtHostNanos = System.nanoTime() + timing.planLeadNanos,
                    staggerNanos = timing.staggerNanos,
                    repeats = timing.repeats,
                    // Widened for the room this turned out to be, which is the first moment
                    // anything knows how big it is.
                    intervalNanos = roomIntervalNanos(timing.intervalNanos, slots.size, timing.staggerNanos),
                    slotIds = slots
                )
            } ?: return show(
                if (planServer.failureCode == CalibrationPlanServer.CALLED_OFF) {
                    events.write("room-called-off: pressed while the room was still gathering")
                    getString(R.string.pair_calibrate_room_called_off_here)
                } else {
                    getString(R.string.pair_calibrate_failed, planServer.failureCode ?: "ROOM_LOST")
                }
            )
            // The plan is out. Up to here calling the round off meant refusing to answer it; from
            // here it means telling everybody who holds it to stop, which is a different sentence
            // beside the same button - see [StopOffer].
            handler.post { state = state.copy(stopOffer = StopOffer.UNDER_WAY) }
            val ownSlot = plan.slotIds.indexOf(hostId)
            val runner = handsetPeerCalibrationRunner(
                runStore = RunStore(filesDir),
                caseId = plan.caseId,
                role = CalibrationRole.HOST,
                plan = plan,
                ownSlot = ownSlot,
                hostNanosNow = { System.nanoTime() },
                audioSource = audioSource(),
                edgeShares = AlignmentAnalysis.DISTANCE_EDGE_SHARES,
                calledOff = { stopping },
                keepsRecording = keepsRecordings(filesDir)
            )
            show(
                getString(R.string.pair_calibrate_running),
                whenItReaches(runner.timing().recordUntilHostNanos) { System.nanoTime() }
            )
            val run = runner.run()
            // A called-off round leaves nothing behind. Not even the half of it this handset
            // heard: a recording that stops in the middle of a schedule still correlates, and a
            // file that looks like every other run but holds a room that was never finished is
            // worse than no file at all. What it does leave is a line in the timeline.
            if (stopping) {
                events.write("room-cancelled while chirping; nothing of this round is kept")
                return
            }
            // Written before anything is combined, so a room that loses everybody still leaves
            // this handset's own hearing of it on disk.
            File(RunStore(filesDir).prepareRun(plan.caseId), hostArtifact(hostId)).writeText(run.json)
            fileAttempt("HOST-${plan.caseId}-$hostId", run.json)
            Log.i(LOG_TAG, run.json)
            // Keyed by slot throughout: the analysis speaks slots, and the one place a slot
            // becomes a name again is roomField.
            val heard = LinkedHashMap<Int, List<List<ChirpArrival?>>>()
            heard[ownSlot] = run.arrivalsByRepeat
            val heardFrom = ArrayList<String>()
            var field = RoomField(emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0)
            roomServer.awaitRoom(plan.slotIds.size - 1, ROOM_RESULT_TIMEOUT_MILLIS) { delivered ->
                for (message in delivered) {
                    val slot = plan.slotIds.indexOf(message.senderId)
                    // A delivery from a handset this plan never named, or one that read its
                    // window against a slot other than the one it was given, is a hearing of a
                    // different room. Combining it puts a real pair on the wrong two phones.
                    if (slot < 0 || slot != message.ownSlot) continue
                    heard[slot] = message.arrivalsByRepeat
                    heardFrom += message.senderId
                }
                field = roomField(plan, heard, AlignmentAnalysis.DISTANCE_EDGE_SHARES)
                delivered.associate {
                    it.senderId to RoomReply(
                        plan.slotIds.size,
                        pairsReadableFor(field, it.senderId),
                        // Offered to everybody who delivered, because the host cannot tell who
                        // needs it: the constant lives on the handset that applies it, and the
                        // channel where a handset says what it carries is closed for the whole
                        // of a round. The receiver keeps it only if it has nothing better.
                        offeredTo(field, hostId, it.senderId)
                    )
                }
            }
            fileAttempt(
                "HOST-${plan.caseId}-ROOM",
                roomReportJson(plan, field, heardFrom, timing.planLeadNanos)
            )
            // The whole field, which is what the room screen reads to check a drawing against.
            // The per-peer files below are the same distances for the pairs this handset is an
            // end of; this is the only place the rest of them have ever had.
            val toStore = roomFieldToStore(field.separationMetres, hostId, overhead())
            if (saysSomethingAboutTheRoom(toStore)) {
                runCatching { StoredRoomField(filesDir).write(toStore) }
                events.write("room-field written, ${toStore.count { it.value != null }} pairs")
            } else {
                events.write("room-field kept: this round measured no distance between handsets")
            }
            // Read, not yet used. The room measured how far apart every pair fires at the same
            // instant it measured how far apart they stand, and if that number is good enough
            // it replaces a minute per handset of somebody walking to each one. Whether it is
            // good enough is a comparison against constants measured the long way, so the first
            // thing it has to do is be readable afterwards.
            for (entry in field.alignmentErrorMs) {
                val millis = entry.value ?: continue
                events.write(
                    "room-firing ${entry.key.first} ${entry.key.second} " +
                        String.format(Locale.US, "%.3f", millis) + "ms"
                )
            }
            for (entry in field.edgeFiringOffsetMs) {
                val millis = entry.value ?: continue
                events.write(
                    "room-firing-edge ${entry.key.first} ${entry.key.second} " +
                        String.format(Locale.US, "%.3f", millis) + "ms"
                )
            }
            // And what was actually handed out, which is a different list: a pair can be readable
            // and still be refused here, and a refusal that leaves no trace is a fix that looks
            // like a feature that was never built.
            for (entry in field.offerableOffsetMicros) {
                events.write(
                    "room-offer ${entry.key.first} ${entry.key.second} " + (entry.value?.let {
                        String.format(Locale.US, "%.3f", it / 1000.0) + "ms"
                    } ?: "refused: the repeats of this pair did not agree closely enough")
                )
            }
            // And the per-peer files, which is where every other arm writes a distance and
            // where the pair flow reads one. What may be kept is what [separationToStore] would
            // keep - a room always sweeps the thresholds, so the narrower rule and the wider one
            // are the same rule here.
            for (entry in field.separationMetres) {
                val peer = when (hostId) {
                    entry.key.first -> entry.key.second
                    entry.key.second -> entry.key.first
                    else -> continue
                }
                val metres = entry.value ?: continue
                runCatching {
                    if (overhead()) StoredListenerDistance(filesDir, peer).write(metres)
                    else StoredSeparation(filesDir, peer).write(metres)
                }
            }
            // Where the walk-through goes next, and which step the answer about to be shown came
            // from. The overhead round hands over to the round that measures the phones; the
            // round of the phones is the last step, so it leaves the walk-through where it is.
            val which = if (overhead()) 1 else 2
            handler.post {
                if (which == 1) roomStep = 2
                roomRedo = which
            }
            show(getString(
                R.string.pair_calibrate_room_done,
                plan.slotIds.size,
                field.separationMetres.count { it.value != null },
                field.separationMetres.size
            ))
            showRoom(plan.slotIds)
        } finally {
            hostPlanServer = null
            // Nobody is standing at this screen once it is gone, and a listener held past that
            // is a screen being written to that is not there.
            RoomCommands.listenForExcuses(null)
            planServer.stop()
            roomServer.stop()
            clockServer.stop()
            events.write("room-ports released")
            Log.i(LOG_TAG, "the room servers are down; the clock port is free again")
        }
    }

    /**
     * One handset's turn: wait to be asked, mint the plan, play, and combine the two halves.
     *
     * The servers are handed in rather than opened here, because they outlive a round. A sink that
     * presses start while this host is between handsets has to find the ports already listening,
     * and that is the difference between one press serving a room and one press serving a phone.
     *
     * Nothing is stored here. The correction belongs to the handset that applies it, and this one
     * does not - what this produces is the answer the sink is waiting for on the socket it
     * delivered on.
     */
    private fun serveOneSink(
        hostId: String,
        planServer: CalibrationPlanServer,
        resultServer: AlignmentResultServer
    ): RoundResult {
        // Two waits, and the difference is who has to act. The named handset has already been told
        // over the standing line and is on its way, so asking somebody to walk to it and press
        // something would be sending them on an errand that undoes itself. See [a button asserts
        // its own scope]: the words are the assertion, not the button.
        //
        // Said at all because this is the sixteen seconds of clock exchange, which makes no sound:
        // a screen that says nothing here is a screen that looks like it did not hear the press.
        show(
            getString(
                if (aimedAt() != null) R.string.pair_calibrate_waiting_aimed
                else R.string.pair_calibrate_waiting
            )
        )
        // Which handset this round is with. Set on the accept, because that is the only
        // moment it is known, and every file this run writes is named with it.
        var servedSink: String? = null
        val plan = planServer.awaitRequest(PLAN_WAIT_MILLIS) { request ->
            // The case names a directory RunStore will create, and it arrived over a socket.
            // Only the two this handset runs are honoured; anything else ends the run here
            // rather than at the run store.
            if (request.caseId !in setOf(CASE_MEASURE, CASE_VERIFY, CASE_SLOW_LINK, CASE_DISTANCE)) {
                throw IllegalArgumentException("not a case this handset runs: ${request.caseId}")
            }
            // The sink's name arrived over the same socket and names files on this side too.
            // Checked for shape here rather than trusted from where it came, on the same terms
            // as every other id that reaches a file name.
            if (!HostId.isValid(request.sinkId)) {
                throw IllegalArgumentException("not a handset name: ${request.sinkId}")
            }
            servedSink = request.sinkId
            // The arm is the sink's to name - it is the handset somebody pressed something on -
            // and the plan is where the host adopts it. Held on the field as well so that the
            // report this side files says which schedule actually ran.
            timing = timingFor(request.caseId)
            CalibrationPlan(
                caseId = request.caseId,
                hostId = hostId,
                // Far enough out to cover the warm-up and the gap the sink has yet to start.
                firstChirpAtHostNanos = System.nanoTime() + timing.planLeadNanos,
                staggerNanos = timing.staggerNanos,
                repeats = timing.repeats,
                intervalNanos = timing.intervalNanos
            )
        } ?: return when {
            // The stop button called this off, so what came back is the button working rather than
            // anything having gone wrong. It has already put its own sentence on the screen.
            stopping -> RoundResult.FAILED
            planServer.failureCode == CalibrationPlanServer.TIMEOUT -> {
                show(getString(R.string.pair_calibrate_failed, CalibrationPlanServer.TIMEOUT))
                RoundResult.NOBODY_ASKED
            }
            else -> {
                show(getString(R.string.pair_calibrate_failed, planServer.failureCode ?: "PLAN_LOST"))
                RoundResult.FAILED
            }
        }
        // Non-null by construction: awaitRequest answers a plan only once planFor has run.
        val sinkId = servedSink ?: run {
            show(getString(R.string.pair_calibrate_failed, "PLAN_UNSIGNED"))
            return RoundResult.FAILED
        }
        // The plan is out, and from here both sides act on their own clocks. The stop button
        // stays live and changes meaning rather than going grey: before this it refuses to hand
        // the schedule out, after it it says so over the standing channel the other handset never
        // left - see [StopOffer] and [RoomCommand.CALL_OFF].
        handler.post { state = state.copy(stopOffer = StopOffer.UNDER_WAY) }
        val runner = handsetPeerCalibrationRunner(
            runStore = RunStore(filesDir),
            caseId = plan.caseId,
            role = CalibrationRole.HOST,
            plan = plan,
            hostNanosNow = { System.nanoTime() },
            audioSource = audioSource(),
            edgeShares = AlignmentAnalysis.DISTANCE_EDGE_SHARES,
            calledOff = { stopping },
            keepsRecording = keepsRecordings(filesDir)
        )
        show(
            getString(R.string.pair_calibrate_running),
            whenItReaches(runner.timing().recordUntilHostNanos) { System.nanoTime() }
        )
        val run = runner.run()
        // Nothing of a called-off round is kept, on the same terms as the room's: no file, no
        // delivery, and above all no stored constant. The wait for the sink's half is skipped
        // with it - the sink stopped too, so waiting would be a minute spent on a socket nobody
        // is going to open.
        if (stopping) {
            events.write("pair-cancelled while chirping; nothing of this round is kept")
            return RoundResult.FAILED
        }
        // Written before anything is answered, so a refused run still leaves its evidence.
        // Named with the peer, not just the case: a case id names a directory this only ever
        // mkdirs, so a second sink measured on this host landed on the first one's file and
        // the directory's own mtime did not move to say so.
        File(RunStore(filesDir).prepareRun(plan.caseId), hostArtifact(sinkId)).writeText(run.json)
        fileAttempt("HOST-${plan.caseId}-$sinkId", run.json)
        Log.i(LOG_TAG, run.json)
        var outcome = getString(R.string.pair_calibrate_kept, run.refusal ?: "ONE_SIDED_RUN")
        resultServer.awaitResult(RESULT_TIMEOUT_MILLIS) { message ->
            // The delivery is signed, and this host waits on one socket that any handset in
            // the room still holding a plan can reach. Combining a stranger's readings would
            // not make a worse number, it would make a number about a pair that never ran.
            if (message.sinkId != sinkId) {
                outcome = getString(R.string.pair_calibrate_wrong_sink, shortName(message.sinkId))
                return@awaitResult CalibrationReply(null, null, null)
            }
            val combined = AlignmentPairing.combine(plan.caseId, run.readings, message)
            val metres = measuredSeparationMetres(combined.pairs)
            // Kept rather than only shown, and kept outside the verdict: the separation is the
            // half difference of the two recordings and the alignment is the half sum, so a run
            // that will not cluster still measured the room. Guarded, because a room screen's
            // check is not worth a failed calibration. Shown and kept are two questions: what
            // this run measured goes on the screen for the person who ran it, and only what it
            // can vouch for goes on disk for a check nobody will be watching.
            val keep = separationToStore(combined.pairs)
            if (keep != null) runCatching {
                if (overhead()) StoredListenerDistance(filesDir, sinkId).write(keep)
                else StoredSeparation(filesDir, sinkId).write(keep)
            }
            outcome = combined.verdict?.clusterMeanMs?.let { mean ->
                getString(R.string.pair_calibrate_host_done, mean, metres ?: 0.0)
            } ?: metres?.takeIf { plan.caseId == CASE_DISTANCE }?.let {
                // The distance arm has no verdict to report and is not failing when it has none.
                getString(R.string.pair_calibrate_distance_done, it)
            } ?: getString(
                R.string.pair_calibrate_kept,
                combined.failure?.name ?: "NO_VERDICT"
            )
            // Filed here rather than after: this is the only point at which both halves of
            // the run exist in one place, and before this the combination was never written
            // down at all - one sentence on a screen, then gone.
            fileAttempt(
                "HOST-${plan.caseId}-$sinkId-PAIRED",
                pairedReportJson(
                    caseId = plan.caseId,
                    hostId = plan.hostId,
                    sinkId = sinkId,
                    combined = combined,
                    hostReadings = run.readings,
                    sinkReadings = message.readings,
                    intervalFrames = chirpIntervalFrames(plan.intervalNanos),
                    planLeadNanos = timing.planLeadNanos
                )
            )
            CalibrationReply(
                // Read through what the sink says it applied, never through this handset's
                // idea of it: only the sink knows what it actually used.
                measuredOffsetMicros = if (!keepsCorrection(plan.caseId)) null
                else CalibrationUpdate.measured(
                    message.appliedOffsetMicros,
                    combined.verdict
                ),
                clusterMeanMicros = combined.verdict?.clusterMeanMs?.let { (it * 1000).toLong() },
                passed = combined.verdict?.passed
            )
        }
        // Kept on the screen rather than replacing what the last sink said. A host that
        // measures two handsets in a row has two answers, and the pair of them is the whole
        // point of measuring the second one.
        record(sinkId, outcome)
        return RoundResult.SERVED
    }

    /**
     * The sink's part: converge the clock, fetch the plan, play, deliver, and fold what comes back.
     *
     * The clock comes first because the plan's instants are in the host's clock and this handset
     * cannot act on one until it can convert. The other order would leave the plan expiring inside
     * a wait it caused itself.
     */
    /**
     * Measures as a sink, which this screen no longer does itself.
     *
     * The run moved to [SinkRound] when a handset with its screen off had to be able to join a
     * room round. Nothing in it needs a screen - a clock exchange, one chirp on a schedule, one
     * delivery - and it had one only because it grew inside this activity. That accident is what
     * made a phone lying face down answer a round with ASLEEP.
     *
     * What is left here is the wiring: this screen's intent into that run, and its words back
     * onto this screen.
     */
    private fun measureAsSink(verifying: Boolean, allowSlowLink: Boolean) {
        handsetSinkRound(
            context = this,
            request = SinkRoundRequest(
                verifying = verifying,
                allowSlowLink = allowSlowLink,
                distanceOnly = distanceOnly(),
                room = roomAsked(),
                audioSource = audioSource().name,
                keepFractionWhileFilling = keepFractionWhileFilling(),
                timingFor = ::timingFor
            ),
            radioHeld = { radioHeld },
            calledOff = { MeasuringNow.calledOff },
            report = object : SinkRoundReport {
                override fun say(line: RoundLine, untilLocalNanos: Long?) =
                    show(line.text(this@PeerCalibrateActivity), untilLocalNanos?.let(::elapsedAtLocalNanos))

                override fun calledOff() {
                    roundCalledOff = true
                }
            }
        ).run()
    }

    /**
     * The constant this handset carries for the peer it is paired with, or null if it carries none.
     *
     * Read on every recomposition rather than held: the run writes it from another thread, and a
     * remembered copy would leave the screen announcing the answer it had before it measured.
     */
    private fun storedCalibration(): Calibration? =
        PairedHost(filesDir).read()?.let { StoredCalibration(filesDir, it.hostId).read() }

    /** What a room round left for this peer when nobody had measured it, read the same way. */
    private fun approximateCalibration(): Long? =
        PairedHost(filesDir).read()?.let { StoredApproximateCalibration(filesDir, it.hostId).read() }

    /**
     * Drops this pair's constant, so the next run is adopted whole the way a first run is.
     *
     * Refused while a run is going, and that is not tidiness: the rule that decides whether a run
     * may move the constant reads the observation count, and clearing it mid-run would turn the
     * run in flight into a first run - adopted whether or not it passed.
     */
    /**
     * Ends the round in flight, on every handset in it.
     *
     * Only a host has anything to stop: a sink's run is one round with nothing after it, so the
     * button is not offered there. Two places have to hear the press, because a round has two
     * halves and neither one can speak for the other: handsets still waiting for a schedule are
     * turned away by the plan server, and handsets already chirping are told over the standing
     * channel, which nothing about a round ever made them leave.
     */
    private fun stopServing() {
        if (!running) return
        stopping = true
        // On the record, because until now this button left no trace at all: a round that ended
        // on its own and a round somebody ended could not be told apart afterwards, and the first
        // report of it doing nothing had nothing in the timeline to check it against.
        events.write("stop-pressed while " + state.stopOffer)
        show(
            getString(
                if (roomAsked()) R.string.pair_calibrate_room_called_off_here
                else R.string.pair_calibrate_stopping
            )
        )
        // Says so to the handsets already waiting, instead of closing their sockets under them -
        // which is the 09-13 message word for word. See [CalibrationPlanServer]. Both jobs now:
        // the pair used to close the socket alone, which left the one handset on the other end
        // of it reading a connection failure for a button somebody pressed on purpose.
        runCatching { hostPlanServer?.callOffRoom() }
        // And to everybody already chirping, who are past asking this host for anything. Said to
        // the whole standing room rather than to one named handset: a handset that is not in a
        // round has nothing to call off and does nothing with it, and a pair round started from
        // this screen's own button does not learn the other handset's name until it asks.
        runCatching { RoomCommands.send(RoomCommand.CALL_OFF) }
    }

    private fun forget() {
        if (running) return
        PairedHost(filesDir).read()?.let {
            StoredCalibration(filesDir, it.hostId).forget()
            // Both, because this button means "as if this pair had never been measured", and a
            // handset that quietly carried on correcting off a room round would not be that.
            StoredApproximateCalibration(filesDir, it.hostId).forget()
        }
        // Also what redraws the screen: the stored value is read from disk during composition,
        // and the message is the state change that sends it back for a fresh look.
        show(getString(R.string.pair_calibrate_forgotten))
    }

    /**
     * Keeps one more copy of what this attempt produced, under a name no later run can claim.
     *
     * A case id names a directory the run store never clears, so a second run of the same case
     * overwrites the first where it stands - in place, which is why nothing outside says so: the
     * directory's own mtime does not move either. See [PeerRunLog].
     *
     * Guarded rather than left to throw. This is a second copy of evidence, and a full disk
     * turning a finished measurement into a vanished app would cost more than the copy is worth.
     */
    /**
     * The file the host's own half of a run goes in, under the peer it ran with.
     *
     * [sinkId] has been through [HostId.isValid] by the time it reaches here, which is what makes
     * it safe in a file name: it arrived over a socket, and hexadecimal of a fixed length cannot
     * hold a path segment.
     */
    private fun hostArtifact(sinkId: String): String = "peer-calibration-$sinkId.json"

    /** Enough of a handset's name to tell two apart in a room, for a screen a person reads. */
    /**
     * What a handset is called on this screen: its number, the same one the room draws on it.
     *
     * Six characters of the real name was what this showed, against the room's four, and the
     * two were never reconciled - so one screen's handset and the other screen's handset looked
     * like different strings to anybody comparing them. The colour that completes this name
     * cannot reach here: pairing runs over its own channel, between two handsets, with no room
     * around them to have assigned one.
     */
    private fun shortName(sinkId: String): String = "${PeerBadge.numberOf(sinkId)}"

    /**
     * Adds one sink's answer to what the screen shows, replacing that sink's previous one.
     *
     * Kept apart by the whole name and shown by the short one. Matching on what is displayed
     * would fold two handsets sharing six hexadecimal characters into one line, which is a rare
     * accident with no symptom - the second measurement would simply appear to be the first's.
     */
    private fun record(sinkId: String, outcome: String) {
        handler.post {
            state = state.copy(
                // The line that described the work does not survive the work. [RoundResult] shows
                // `message` once the round stops, on the assumption that by then it holds the
                // answer - but nothing ever put the answer there, so a finished round printed
                // "校准中：两台都别碰" above the numbers it had just produced. Cleared here rather
                // than where the round ends, because here is where an answer exists to replace it,
                // and a round that ended in a refusal still needs its sentence on screen.
                message = null,
                until = null,
                outcomes = state.outcomes.filterNot { it.sinkId == sinkId } +
                    SinkOutcome(
                        sinkId = sinkId,
                        // What that handset said to call it, when it has ever stood by here.
                        // Four hexadecimal characters is what this was, and it is why "which
                        // handset dropped out" was built once and abandoned: the answer it could
                        // give was not one anybody could act on.
                        name = RoomCommands.nameOf(sinkId) ?: shortName(sinkId),
                        text = outcome
                    )
            )
        }
    }

    private fun fileAttempt(label: String, json: String) {
        runCatching { PeerRunLog(filesDir).write(label, json, System.currentTimeMillis()) }
            .onFailure { Log.e(LOG_TAG, "this attempt could not be filed under $label", it) }
    }

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
            CLOCK_INTERVAL_MILLIS, estimator.windowSize, estimator.bestCount, estimator.keepFractionWhileFilling,
            // Null because only a sink surveys the link, and that half is SinkRound's now. A host
            // holds the other end of the same exchanges and has never measured one here.
            timing.clockFillNanos, radioHeld, null, atStart, atEnd, exchanges
        )
    )

    /**
     * The room's own words for one refusal.
     *
     * Written here rather than sent over the wire, and that is the point of the enum: the handset
     * composing a sentence is the one that just refused to work, and a string arriving from it is
     * a string nobody checked on its way to a screen.
     */
    private fun reasonFor(excuse: RoomExcuse): String = getString(
        when (excuse) {
            RoomExcuse.NO_MICROPHONE -> R.string.excuse_no_microphone
            RoomExcuse.SLOW_LINK -> R.string.excuse_slow_link
            RoomExcuse.CLOCK_NOT_CONVERGED -> R.string.excuse_clock_not_converged
            RoomExcuse.BUSY -> R.string.excuse_busy
            RoomExcuse.ASLEEP -> R.string.excuse_asleep
        }
    )

    private fun show(text: String, until: Long? = null) {
        handler.post { state = state.copy(message = text, until = until) }
    }

    /**
     * Draws the room a round has just measured, off the same file the home screen draws from.
     *
     * Shown here because this is where somebody is standing when the lengths land, and until now
     * the only sign that anything had been measured was a sentence counting pairs. A person can
     * check a drawing against the room they are in; they cannot check "3 pairs of 3".
     *
     * Only on the handset that gathered the room. The others leave this screen three seconds
     * after a round ends - that is what stops them sitting on a result page holding the clock
     * port - so a drawing on those is one nobody gets to look at, and the field it would need is
     * the host's anyway.
     *
     * [peerIds] is who took part, which is the one thing this screen knows and the home screen
     * does not: over there the room is whoever is connected right now.
     */
    private fun showRoom(peerIds: List<String>) {
        val self = HostIdentity(filesDir).current()
        val ids = peerIds.distinct()
        val saved = StoredRoomDrawing(filesDir).read()
        val room = (saved?.room ?: RoomState()).copy(
            // Where each of them was last put, so a room measured twice does not jump about.
            // Anybody nobody has ever placed lands in the default arrangement, exactly as they
            // would on the home screen.
            icons = SpatialRoom.reconciled(
                emptyList(),
                ids,
                saved?.placements?.associateBy { it.peerId } ?: emptyMap()
            ),
            selfId = self,
            measuredMetres = measuredDistances(filesDir, self, ids),
            listenerMetres = StoredListenerDistance.all(filesDir),
            // A fresh measurement is a fresh reason to offer to move the drawing onto it, whatever
            // was done with the last one.
            fitted = false,
            // The same table the home screen draws from. Without it this drawing was grey, and
            // this is the screen where telling four identical handsets apart matters most: a
            // person standing over a room round is looking at four phones and a picture of four
            // phones, and has to match them up. Reported on a room of four, 2026-09-18.
            colours = RoomCommands.places()
        )
        handler.post {
            state = state.copy(room = room)
            keepTheDrawing()
        }
    }

    /** One change a finger made to the drawing, kept the moment it is made. */
    private fun changeRoom(change: (RoomState) -> RoomState) {
        val room = state.room ?: return
        state = state.copy(room = change(room))
        keepTheDrawing()
    }

    /**
     * Writes the drawing down, which is how the home screen ever hears about it.
     *
     * On every change rather than on leaving, unlike the home screen: what happens here is a
     * handful of drags and one press, not fifty publishes a second, and the screen this one hands
     * off to is started by somebody pressing back - there is no moment afterwards to write in.
     *
     * Merged with what is already on disk rather than replacing it: a round measures the handsets
     * that took part, and a handset that was switched off for it has a place somebody chose that
     * this round knows nothing about.
     */
    private fun keepTheDrawing() {
        val room = state.room ?: return
        val store = StoredRoomDrawing(filesDir)
        val placed = LinkedHashMap(store.read()?.placements?.associateBy { it.peerId } ?: emptyMap())
        for (icon in room.icons) placed[icon.peerId] = icon
        runCatching { store.write(placed.values.toList(), room) }
    }

    /**
     * An instant on whatever clock [now] reads, as a moment on this phone's own.
     *
     * The countdown is drawn against elapsedRealtime because that is the one clock a screen can
     * read without asking anybody, while the instants worth counting to are named in host time
     * for the chirps and in this phone's nanoTime for the clock fill. Both arrive here with the
     * clock that reads them, so neither has to be converted twice.
     *
     * Converted once, at the moment the step starts, rather than tracked: an offset estimate that
     * moves by a millisecond while somebody sits still is not something a count of whole seconds
     * can show, and re-reading it would only make the number flicker.
     */
    internal companion object {
        const val LOG_TAG = "SoundMeshPeerCalibrate"

        /**
         * How often the volume rows are re-read.
         *
         * Half a second, because the thing it is watching is a thumb on somebody's volume keys and
         * a row that lags behind the phone in their hand reads as a row that is not listening.
         */
        const val VOLUME_MILLIS = 500L

        /**
         * How much of an identity to show when a handset has never said what it is called.
         *
         * The last four rather than the whole thing, because the point of a name is that somebody
         * can say it out loud - see `RoomCommandServer.nameOf`, which falls back the same way.
         */
        const val SHORT_NAME_CHARACTERS = 4

        /** Intent extra naming the one handset a pair round is for. See [aimedAt]. */
        const val AIMED_AT_EXTRA = "aimed_at"

        /**
         * The plan's chirp interval in frames, which is the grid an emission is measured against.
         *
         * Derived from the plan rather than from [CHIRP_INTERVAL_NANOS]: the plan is what both
         * handsets actually played to, and a host reading its own constant would answer for a
         * schedule nobody followed if the two ever came apart.
         */
        fun chirpIntervalFrames(intervalNanos: Long): Int =
            (intervalNanos * ChirpGenerator.SAMPLE_RATE / 1_000_000_000L).toInt()

        /** Next after AlignmentResultServer's 45125. */
        const val PLAN_PORT = RoundPorts.PLAN

        /** How long the host holds the screen open waiting for somebody to pick up the other phone. */
        const val PLAN_WAIT_MILLIS = 300_000

        /**
         * How long a room waits for handsets to come back to their home screens before telling it.
         *
         * Sized for the trip back from this screen to that one, not for anything on the network:
         * a handset whose round was called off finishes and resumes the home screen in a couple of
         * hundred milliseconds, and this is a few of those.
         */
        const val ROOM_RETURN_GRACE_MILLIS = 2_000L

        /** Next after the plan's 45126, and held only while a room is being measured. */
        const val ROOM_PORT = RoundPorts.ROOM

        /**
         * How long a round started by the host stays on screen before it puts itself away.
         *
         * Long enough to read what it says and short enough that nobody is waiting on it. The
         * host reports the whole room anyway; this is only the one handset saying how it got on.
         */
        const val LINGER_MILLIS = 3_000L

        /**
         * How long the room waits between one handset asking and the next, before deciding
         * nobody else is coming.
         *
         * The fallback, and only that, since 2026-09-15: the host tells the room over the
         * standing line and knows how many it told, so a complete room stops waiting the moment
         * the last handset is in. What is left for this to cover is a handset that was told and
         * never arrived and never said why - its app killed, its process gone - and three seconds
         * is what that costs now.
         *
         * Eight until then, and eight was the right number for what it used to be: the gap
         * between two people pressing two buttons, back when a round was started by walking to
         * each phone. Nobody walks any more, so every round paid eight seconds of silence after
         * the last handset had already asked.
         */
        const val ROOM_SETTLE_MILLIS = 3_000

        /**
         * The whole gathering, from the first ask.
         *
         * Bounded by what a sink will wait for its plan - the handset that asks first waits out
         * everybody after it - and [CalibrationPlanServer.awaitRoom] checks that rather than
         * trusting it. Under CalibrationPlanClient's 30 s with room to spare.
         */
        const val ROOM_WINDOW_MILLIS = 25_000

        /**
         * How long the host waits for the room to deliver what it heard.
         *
         * The pair's own bound, for the same reason: a correlation pass takes seconds and this
         * is what stands between a handset that died mid-run and a host that never finishes. A
         * room's pass is wider than a pair's - one search per slot rather than three in all -
         * so the headroom over it is in RoomResultClient's reply timeout, not here.
         */
        const val ROOM_RESULT_TIMEOUT_MILLIS = 120_000

        /** The sink's correlation pass takes seconds; this bounds a sink that died mid-run. */
        const val RESULT_TIMEOUT_MILLIS = 120_000
    }
}

/**
 * Whether a calibration screen is in front of somebody on this handset right now.
 *
 * Read by [StandbyService], which holds this handset's standing line whether or not anybody is
 * looking at it and therefore has to know when this handset is already busy being told what to do
 * by a round. A volume moved under a chirp, or a session started over one, does not make a round
 * fail - it makes it produce a number, which is the worse of the two outcomes by a long way.
 *
 * File scope rather than on the activity's companion, which is full of constants and ports that
 * have no business being a busy flag's home.
 */
internal object MeasuringNow {
    /** A round on [PeerCalibrateActivity], which is the only kind there was until 09-15. */
    @Volatile
    var onScreen = false

    /**
     * A round [StandbyService] is driving with nobody looking at this handset.
     *
     * Kept apart from [onScreen] rather than folded into one flag, because the two are cleared by
     * different things: that one by an activity's onPause, this one by a thread finishing. One
     * flag written from both would have a screen closing behind a background round clear it.
     */
    @Volatile
    var inBackground = false

    /** What the standing line asks: is this handset already being told what to do by a round. */
    val busy: Boolean get() = onScreen || inBackground

    /**
     * The host has called off the round this handset is in. See [RoomCommand.CALL_OFF].
     *
     * Here rather than on whichever thing is running the round, because either of them can be:
     * a round joined from the standing line has no screen at all, and the screen's own sink arm
     * is still what the diagnostic arms use. Cleared when a round starts rather than when one
     * ends - a press landing as one round finishes must not end the next one before it begins.
     */
    @Volatile
    var calledOff = false
}
