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
import com.soundmesh.probe.sync.CalibrationRunner
import com.soundmesh.probe.sync.HandsetRoundSpeaker
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

    /** The page on screen and what this visit has measured - see [PeerCalibrateState.page]. */
    private var roomPage by mutableStateOf(1)
    private var stepOneDone by mutableStateOf(false)
    private var stepTwoDone by mutableStateOf(false)
    private var roomRan: Int? by mutableStateOf(null)

    /**
     * Whether a handset other than this one was given a volume of its own here. Its own disk
     * holds what it was at before, but only a restore sent from here puts it back, and whether one
     * is sent was judged on this handset's streams alone.
     */
    private var singledOut = false

    /**
     * Whether the run [start] is about to make, or is making, is a sound check rather than a round.
     * Set by the button and cleared where the run's answer is read, on the main thread both times.
     */
    @Volatile private var checkingSound = false

    /** What the sound check in flight heard, handed from the run's thread to the main one. */
    @Volatile private var checked: SoundCheck? = null

    /** Whether a pair round has been served on this visit, which is what 完成 waits for. */
    private var pairDone by mutableStateOf(false)

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
     * The host round in flight, or null when none is serving.
     *
     * Held here only so the stop button can reach it. The run thread spends most of a round
     * parked in that round's plan server's accept(), and calling it off is what wakes it -
     * without which the button would take up to five minutes to have any visible effect, which is
     * indistinguishable from a button that does not work.
     */
    @Volatile private var hostRoundInFlight: HostRound? = null

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
        else {
            // Or the next press of a measuring button would run the check it was waiting for.
            checkingSound = false
            state = state.copy(message = getString(R.string.pair_calibrate_no_permission))
        }
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
                            colours = colours,
                            page = roomPage,
                            stepOneDone = stepOneDone,
                            stepTwoDone = stepTwoDone,
                            ran = roomRan,
                            pairDone = pairDone
                        ),
                        // Unrecognised or absent means PAIR - see peerJobOf - which is what every
                        // ADB-driven `am start` of this activity has always meant with no extra.
                        job = peerJobOf(intent.getStringExtra(PEER_JOB_EXTRA)),
                        actions = PeerCalibrateActions(
                            calibrate = { begin(verifying = false, allowSlowLink = false) },
                            soundCheck = {
                                if (!running) {
                                    checkingSound = true
                                    checked = null
                                    begin(verifying = false, allowSlowLink = false)
                                }
                            },
                            skipStep = {
                                roomStep = 2
                                stepOneDone = true
                                roomPage = 3
                            },
                            back = { finish() },
                            setRoomVolume = { percent -> setRoomVolume(percent) },
                            setHandsetVolume = { peerId, percent -> setHandsetVolume(peerId, percent) },
                            // Arriving on a step's page makes that step the one to do, so a page
                            // come back to has its start button rather than a quiet box.
                            toPage = { page ->
                                roomPage = page
                                roomStep = if (page == 3) 2 else 1
                            },
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
        // Which step this round is, and the page it is watched on: a round started from outside
        // this screen - `am start` with `auto` - lands on its own page like one started here.
        // A sound check is neither: it stays on the page it was pressed on and measures no step.
        if (roomAsked() && !checkingSound) {
            val step = if (overhead()) 1 else 2
            roomRan = step
            if (role() == CalibrationRole.HOST) roomPage = step + 1
        }
        state = state.copy(
            running = true,
            // No way to stop a sound check: it is one chirp each and over in seconds.
            stopOffer = if (checkingSound) StopOffer.NONE else stopOfferFor(role()),
            message = getString(R.string.pair_calibrate_waiting),
            checking = checkingSound
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
                            if (checkingSound) checkSound()
                            else if (roomAsked()) measureAsRoom() else measureAsHost()
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
                if (checkingSound) soundChecked(checked)
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
            if (role() == CalibrationRole.HOST) {
                state = state.copy(volumes = roomVolumes(), alone = RoomCommands.standingPeerIds().isEmpty())
            }
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
        // A check says what was heard at the old level, which is no longer anybody's.
        state = state.copy(volumes = roomVolumes(), unheard = emptyMap())
    }

    /** One handset on its own: this one on its own streams, anybody else over the standing line. */
    private fun setHandsetVolume(peerId: String, percent: Int) {
        if (peerId == HostIdentity(filesDir).current()) {
            handsetVolume.set(percent, capturing = false)
        } else {
            singledOut = true
            val reached = RoomCommands.sendTo(peerId, RoomOrder(RoomCommand.SET_VOLUME, percent))
            events.write("volume for one handset in a round: $peerId to $percent%" + if (reached) "" else ", no line to it")
        }
        state = state.copy(volumes = roomVolumes(), unheard = state.unheard - peerId)
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
        if (!restoresOnLeaving(volumeChangedBefore, handsetVolume.changed() || singledOut)) return
        RoomCommands.send(RoomCommand.RESTORE_VOLUME)
        handsetVolume.restore()
        events.write("room volume put back on leaving the calibration")
    }

    /**
     * The host's part of a pair: core's [HostRound] since 09-24, which a desktop host runs as well.
     * This screen hands it the handset's audio and words.
     */
    private fun measureAsHost() {
        val round = hostRound().also { hostRoundInFlight = it }
        val result = try {
            round.pair(aimedAt())
        } finally {
            hostRoundInFlight = null
        }
        if (result == RoundResult.SERVED) handler.post { pairDone = true }
    }

    /** A sound check of the whole room, or of the one device this pair screen is about. */
    private fun checkSound() {
        val round = hostRound().also { hostRoundInFlight = it }
        checked = try {
            round.soundCheck(if (peerJobOf(intent.getStringExtra(PEER_JOB_EXTRA)) == PeerJob.PAIR) aimedAt() else null)
        } finally {
            hostRoundInFlight = null
        }
    }

    /**
     * What the check came to, as one line per device that was not heard, on that device's row.
     *
     * Every device on the rows is judged, not only the ones that took part: one that was told and
     * never came made no sound, so it was not heard either. One that said why not says that
     * instead. A check that never got as far as a plan leaves its last line, which says why.
     */
    private fun soundChecked(check: SoundCheck?) {
        checkingSound = false
        val failed = if (check == null) state.message else null
        val judged = if (peerJobOf(intent.getStringExtra(PEER_JOB_EXTRA)) == PeerJob.PAIR) {
            listOfNotNull(HostIdentity(filesDir).current(), aimedAt())
        } else {
            state.volumes.map { it.peerId }
        }
        val unheard = if (check == null) emptyMap() else unheardLines(
            check,
            judged,
            excuse = { RoundLine.Excused(it).text(this) },
            notHeard = getString(R.string.sound_check_unheard)
        )
        events.write("sound-check shown: ${unheard.keys.joinToString(" ").ifEmpty { "nobody unheard" }}")
        // The round's own lines are about gathering a room nobody asked to measure; the answer is
        // on the rows, so they go.
        state = state.copy(
            checking = false,
            message = null,
            outcomes = emptyList(),
            unheard = unheard,
            checkFailed = failed
        )
    }

    /**
     * The room's part, likewise [HostRound]'s. What stays here is the walk-through: which step
     * the round was, and which one is next.
     */
    private fun measureAsRoom() {
        val round = hostRound().also { hostRoundInFlight = it }
        val measured = try {
            round.room()
        } finally {
            hostRoundInFlight = null
        }
        if (!measured) return
        // Where the walk-through goes next, and which step the answer now on screen came
        // from. The overhead round hands over to the round that measures the phones; the
        // round of the phones is the last step, so it leaves the walk-through where it is.
        val which = if (overhead()) 1 else 2
        handler.post {
            if (which == 1) roomStep = 2
            // What lights the page's forward button. The page itself stays: the answer is read
            // here, and going on is the person's press, not this.
            if (which == 1) stepOneDone = true else stepTwoDone = true
            roomRedo = which
        }
    }

    /** One round on the host's side, on this handset's audio and in this screen's words. */
    private fun hostRound(): HostRound = HostRound(
        filesDir = filesDir,
        hostId = HostIdentity(filesDir).current(),
        commands = HandsetHostCommands,
        place = if (overhead()) HostPlace.OVERHEAD else HostPlace.PLAYING,
        timingFor = ::timingFor,
        calledOff = { stopping },
        report = object : HostRoundReport {
            override fun say(line: RoundLine, untilLocalNanos: Long?) =
                show(line.text(this@PeerCalibrateActivity), untilLocalNanos?.let(::elapsedAtLocalNanos))

            override fun heard(peerId: String, line: RoundLine) =
                record(peerId, line.text(this@PeerCalibrateActivity))

            override fun forgetHeard() {
                handler.post { state = state.copy(outcomes = emptyList()) }
            }

            override fun underWay() {
                handler.post { state = state.copy(stopOffer = StopOffer.UNDER_WAY) }
            }

            override fun measured(peerIds: List<String>) = showRoom(peerIds)
        },
        keepsRecording = keepsRecordings(filesDir),
        audioSource = audioSource().name,
        log = { Log.i(LOG_TAG, it) },
        recorder = { runStore, caseId, hostNanosNow, source ->
            CalibrationRunner(runStore, caseId, CalibrationAudioSource.parse(source), hostNanosNow)
        },
        speaker = { offsetNanosNow, hostNanosNow -> HandsetRoundSpeaker(offsetNanosNow, hostNanosNow) }
    )

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
        // To the handsets still waiting for a plan and to the ones already chirping alike. See
        // [HostRound.callOff] for why it takes both.
        runCatching { hostRoundInFlight?.callOff() }
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
         * How long a round started by the host stays on screen before it puts itself away.
         *
         * Long enough to read what it says and short enough that nobody is waiting on it. The
         * host reports the whole room anyway; this is only the one handset saying how it got on.
         */
        const val LINGER_MILLIS = 3_000L

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

/** The handset's standing line, as the host round asks for it. See [RoomCommands]. */
internal object HandsetHostCommands : HostCommands {
    override fun send(command: RoomCommand): Int = RoomCommands.send(command)

    override fun sendTo(peerId: String, order: RoomOrder): Boolean = RoomCommands.sendTo(peerId, order)

    override fun standingBy(): Int = RoomCommands.standingBy()

    override fun forgetExcuses() = RoomCommands.forgetExcuses()

    override fun listenForExcuses(listener: ((String, RoomExcuse) -> Unit)?) = RoomCommands.listenForExcuses(listener)
}
