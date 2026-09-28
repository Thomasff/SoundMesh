package com.soundmesh.product

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomOrder
import com.soundmesh.probe.R
import com.soundmesh.session.CAPTURING_HOST_STREAM
import com.soundmesh.probe.sync.FolderSongs
import com.soundmesh.probe.sync.StreamingChunkSource
import com.soundmesh.probe.sync.CaptureSilence
import com.soundmesh.probe.sync.Carried
import com.soundmesh.probe.sync.HandsetVolume
import com.soundmesh.probe.sync.HostBeacon
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.HostSearch
import com.soundmesh.probe.sync.percentOf
import com.soundmesh.probe.sync.RoomCommands
import com.soundmesh.probe.sync.HostPairingCode
import com.soundmesh.probe.sync.LocalAddress
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.ScanActivity
import com.soundmesh.probe.sync.handsetName
import com.soundmesh.probe.sync.StoredRoomField
import com.soundmesh.probe.sync.StoredListenerDistance
import com.soundmesh.probe.sync.StoredOutputLead
import java.io.File
import java.net.Inet4Address
import com.soundmesh.probe.sync.EventLog
import com.soundmesh.probe.sync.StoredSeparation
import com.soundmesh.probe.sync.SyncActivity
import com.soundmesh.probe.sync.SyncProjectionService
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialMode
import com.soundmesh.session.CAPTURING_HOST_USAGE
import com.soundmesh.session.HostSession
import com.soundmesh.session.SessionService
import com.soundmesh.session.SyncSession
import kotlin.math.roundToLong

/**
 * The product's home, and the only screen a user who never attaches a cable ever sees.
 *
 * Separate from [com.soundmesh.probe.MainActivity] rather than grown out of it. That screen is the
 * harness's front door - ADB starts it by name and reads what it prints - and every alignment
 * measurement on record was taken through it. This one owes nothing to that contract, and the two
 * can change without either having to think about the other.
 *
 * What it does is assemble intents. The session itself belongs to [SessionService], which is why
 * leaving this screen - or the app - does not stop the music: the service is a `mediaPlayback`
 * foreground service precisely so that it outlives whatever started it.
 */
class HomeActivity : ComponentActivity() {

    /** The language this app was told to be, put on before anything here reads a string. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.inChosenLanguage())
    }

    private var state by mutableStateOf(HomeState())

    /**
     * Whether the full-screen pairing code is up, replacing everything else this screen draws.
     *
     * Held here rather than as a row in the checklist: the person reading it is standing a metre
     * away pointing another phone's camera at this one, and a screen with anything else on it -
     * even a title bar - is smaller than it needs to be from there. See [routeOf]'s own comment
     * for why this lives beside the state instead of inside it.
     */
    private var showingCode by mutableStateOf(false)

    /** Whether the settings screen is up, replacing everything else this screen draws. */
    private var showingSettings by mutableStateOf(false)

    /**
     * Whether somebody pressed back out of the playing stage while the room went on playing.
     *
     * Beside the state rather than in it, for the reason [routeOf] gives. Put back down by
     * [readSession] the moment there is no session, so that the next room to start is looked at
     * rather than inheriting where the last one was left.
     */
    private var steppedBack by mutableStateOf(false)

    /**
     * Whether somebody is standing on the playing stage with nothing playing.
     *
     * Exactly one thing puts them there: changing the source from that stage, which stops the
     * room. Before this they were dropped back to the status board and had to scroll down and
     * press play again - and the switch they had just asked for was two stages away from where
     * they asked for it. Pressing back, or picking a role, is how somebody actually leaves.
     */
    private var holdingPlaying by mutableStateOf(false)

    /**
     * What is still to be put to somebody who pressed 进入播放, the one on screen first; empty
     * while nothing is being asked. Worked out once, at the press - see [beforePlaying].
     */
    private var asking by mutableStateOf(emptyList<BeforePlaying>())

    /**
     * Whether the last 继续 of [asking] goes on to the playing stage. False when the question was put
     * on picking host rather than on 进入播放: there it is said early, and going on means staying.
     */
    private var askingGoesOn by mutableStateOf(true)

    /** The handset whose line was pressed while the fine calibration note is up; null while it is not. */
    private var askingFine by mutableStateOf<String?>(null)

    /** Whether [CaptureHowTo] is up: set as a capture starts, unless somebody asked not to be told. */
    private var showingCaptureHowTo by mutableStateOf(false)

    /**
     * Which role [takeUpTheRoom] last acted on, so that a resume can tell itself from a change.
     *
     * Not a state field: nothing draws it. It exists because that method is called from both, and
     * one thing it does may only be done on a change - refusing to be the second host, which on a
     * resume asks "is anybody else a host" of a handset that has been one all evening.
     *
     * Null at first, so the first pass after this screen is built counts as a change however the
     * role got there. A role read back off disk is a role this process has not acted on yet.
     */
    private var roleTakenUp: Role? = null

    /**
     * Whether the first-launch permissions screen is up, replacing everything else this screen
     * draws.
     *
     * True until [Preferences] says this screen has been seen once - see [onCreate] - and false
     * from the moment [PermissionsScreen]'s continue button is pressed, whatever was granted. A
     * refusal never turns this back on: the screen exists to explain and ask early, not to gate.
     */
    private var showingPermissions by mutableStateOf(false)

    /** Which of [PermissionsScreen]'s four keys are granted right now - see [refreshPermissionsState]. */
    private var permissionsHeld by mutableStateOf(emptySet<String>())

    /** Which of [PermissionsScreen]'s four keys have been asked for once already. */
    private var permissionsAsked by mutableStateOf(emptySet<String>())

    /**
     * The theme somebody picked on the settings screen, held as composable state rather than read
     * from [Preferences] inside `setContent`.
     *
     * A read inside the composition only runs again on the next cold start, so picking "dark" in
     * settings would need the app killed and reopened before anything changed - which is not a
     * setting, it is a setting that takes effect next time. [onCreate] reads it from disk once;
     * the settings screen writes both the file and this field in the same tap.
     */
    private var themeChoice by mutableStateOf(ThemeChoice.SYSTEM)

    /**
     * The language somebody picked on the settings screen, held beside [themeChoice].
     *
     * Applied to the composition rather than by restarting this activity - see the provider in
     * [onCreate]. A restart is what most apps do for this, and it is what this one must not do:
     * settings is a place inside this screen rather than an activity of its own, so a restart
     * would answer a tap by throwing somebody out of the screen they tapped on. The strings on
     * every screen come from the composition, so re-providing the context is the whole change.
     *
     * [attachBaseContext] covers everything outside it - the handful of `getString` calls in code
     * here, the notifications the services post - and it runs once per component, which is why
     * this field exists as well rather than instead.
     */
    private var language by mutableStateOf(LanguageChoice.SYSTEM)

    /**
     * Whether the playing stage's diagnostic block is shown, held as composable state for the
     * same reason [themeChoice] is: a read from [Preferences] inside a composable does not
     * repaint when the settings switch is flipped, and used to reread the file on every
     * recomposition besides.
     */
    private var showDetails by mutableStateOf(false)

    /**
     * Whether this screen has asked for a session that has not appeared.
     *
     * Without it the service's last failure would stay on the screen forever: it is a static that
     * nothing clears, so a session that failed an hour ago and one that was never started read
     * identically. This says which.
     */
    private var awaitingSession = false
    private var ticks = 0

    /** Reads the output a capturing host is heard on. It cannot set it - see AccessibilityVolume. */
    private val hostOutputVolume by lazy { HostOutputVolume(getSystemService(AudioManager::class.java)) }

    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /**
     * This handset's own volume, and the only thing in the app that sets it.
     *
     * Every handset holds one, host and sink alike: a room volume is one number applied by each
     * of them to whichever stream it is actually playing on, not one stream named from the host.
     */
    private val handsetVolume by lazy {
        HandsetVolume(getSystemService(AudioManager::class.java), filesDir)
    }
    private val handler = Handler(Looper.getMainLooper())

    /** One timeline, shared with every other part of the app. See [EventLog]. */
    private val events: EventLog by lazy { EventLog(filesDir) }

    /** The last count written down, so the record holds the changes rather than every read. */
    private var wroteStandingBy = -1
    /**
     * Where each handset was last drawn, kept past its leaving.
     *
     * Not in [RoomState] because it is not a thing the screen draws: it is what the drawing is
     * rebuilt from when somebody comes back. Grows by one entry per handset ever seen, which for
     * a room of phones is a handful of strings.
     */
    private val whereTheyWere = HashMap<String, RoomIcon>()

    /** Which handsets were carrying the sides, kept past their leaving for the drawing's reason. */
    private val sidesTheyCarried = CarriedSides()

    /**
     * The room this phone was left with last time, read off disk once.
     *
     * Used only until a roster turns it into a real [RoomState]; after that the live one is the
     * one. Not null, because "nothing was ever saved" and "a saved room with nothing in it" want
     * the same thing from the screen here - the defaults - and [StoredRoomDrawing] has already
     * told the two apart by the time this is set.
     */
    private var restored = RoomState()

    /**
     * Whether somebody has dragged the room slider, which is what stops it following this phone.
     *
     * Its own flag rather than [HomeState.volumeChanged], which was doing both jobs and got them
     * both wrong: that one is read off a file that outlives the app, so after any session in
     * which a volume was set, the next start latched the slider to the value it had before a role
     * was picked - null - and the whole panel, restore button included, never appeared again.
     * In memory on purpose: a fresh start should follow this phone again.
     */
    private var roomVolumeSet = false

    /**
     * What each handset was last told to be, by id, this one included.
     *
     * Kept because it is the only thing that can draw that handset's slider: what it reports is
     * where it is, and a thumb drawn from that snaps back to the old place the instant a finger
     * lets go of it. Dropped wholesale by the restore button, which is the one action that means
     * "nobody has told anybody anything".
     */
    private val toldToBe = HashMap<String, Told>()

    /**
     * What each handset's reading was on the pass before, by id.
     *
     * The one thing that tells a person pressing the volume keys on their own phone apart from a
     * stream that took a value and did nothing. Both are "not where it was told to be" on a single
     * reading; only the pair of readings says which.
     */
    private val lastSeenIndex = HashMap<String, Int>()

    /** Whether the room has already been told about the session that is up, so it is told once. */
    private var toldTheRoom = false

    /** How many were standing by when they were last told, so a handset arriving is an event. */
    private var toldThisMany = 0

    private val refresh = object : Runnable {
        override fun run() {
            readSession()
            // Every tick rather than with the health below: this is read so a line can go away
            // while somebody is turning media down, and four seconds late looks like it did not.
            state = state.copy(mediaIndex = if (state.capturing) audio.getStreamVolume(AudioManager.STREAM_MUSIC) else null)
            // Charge and heat move on the scale of minutes, so they are read every few seconds
            // rather than five times a second alongside the counters.
            if (ticks++ % HEALTH_EVERY_TICKS == 0) {
                // Whether this phone is on WiFi, whether it is serving a hotspot, and the code
                // that depends on both - see readNetwork for why this cannot wait for a resume.
                readNetwork()
                state = state.copy(
                    // Changed by a background search rather than by anything on this screen, so
                    // a resume is not when it happens - see StandbyService.lookForAHost. Read
                    // once per resume until 09-21, and on that evening a handset the host was
                    // already listing in its room went on saying it had no host until somebody
                    // left this screen and came back. Every four seconds is far inside what a
                    // search takes; five times a second against a file on disk is not needed and
                    // is what this tick exists to avoid.
                    paired = PairedHost(filesDir).read(),
                    health = DeviceHealth.read(this@HomeActivity),
                    // The volume keys are what move this, so the level changes under the screen
                    // rather than because of it, and has to be re-read to stay true.
                    hostOutputVolume = if (state.capturing) hostOutputVolume.read() else null,
                    // Follows this handset's own volume until somebody drags the slider, which is
                    // what makes it start where the person expects. After a drag the number is
                    // the room's, and following would fight whoever is holding it.
                    roomVolumePercent = roomVolumeShown(
                        isHost = state.role == Role.HOST,
                        dragged = roomVolumeSet,
                        shown = state.roomVolumePercent,
                        onThisPhone = { handsetVolume.read(state.capturing).percent }
                    ),
                    volumeChanged = handsetVolume.changed()
                )
            }
            handler.postDelayed(this, REFRESH_MILLIS)
        }
    }

    /**
     * The picker's answer, and the moment the old source is put down.
     *
     * Put down here rather than before the picker opens, which is where it used to be. Opening the
     * picker is not choosing anything: somebody who backs out of a folder, or looks at what is in
     * one and changes their mind, meant for nothing to happen - and what happened was the room
     * went quiet and the screen fell back two stages. Reported 2026-09-15. A cancel now costs
     * exactly nothing, and the music it was playing is still playing.
     */
    private val chooseSong = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            putDownWhatIsPlaying()
            adopt(ChosenKind.SONG, uri)
        }
    }

    /**
     * A folder, granted once and read again every time play is pressed.
     *
     * A tree rather than a multi-select of files, because what a person means by "play this album"
     * is the folder and not the twelve files that were in it this afternoon.
     */
    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            putDownWhatIsPlaying()
            adopt(ChosenKind.FOLDER, uri)
        }
    }

    // Asked for rather than required. A session runs either way; without it the ongoing
    // notification is invisible, which is a phone playing music with nothing on screen to say so.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * The permissions screen's own three dialog launchers, separate from [askRecordAudio] and
     * [askNotifications] above.
     *
     * Those two are asked for at the moment something is actually about to need them, and granting
     * mic access from there goes straight on into the capture consent dialog. A tap on this
     * screen's own row asks for exactly the one permission and nothing after it, so it needs its
     * own launcher rather than reusing one that has a next step wired to it.
     */
    private val askPermMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissionsState() }
    private val askPermNotify =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissionsState() }
    private val askPermCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissionsState() }

    // Required, unlike notifications: playback capture is an AudioRecord, and without this it does
    // not open at all. Asked for before the consent dialog so a refusal here costs one tap.
    private val askRecordAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askProjection.launch(projectionManager().createScreenCaptureIntent())
        else state = state.copy(problem = R.string.capture_no_permission)
    }

    /**
     * The system's own capture consent, and then the service that turns its answer into a
     * projection.
     *
     * Two steps rather than one because the platform refuses `getMediaProjection` to anything that
     * is not already a foreground service of type mediaProjection - the same rule that
     * [SyncProjectionService] exists for. Nothing here touches the dialog; it is the user's.
     */
    private val askProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            // Declined, so nothing has been given up: whatever this room was playing a moment ago
            // it is still playing, and saying capturing = false here would be this screen telling
            // itself something changed when nothing did.
            state = state.copy(problem = R.string.capture_declined)
            return@registerForActivityResult
        }
        // Consent given, which is the moment the old source stops being the one in use.
        putDownWhatIsPlaying()
        SyncProjectionService.pending = { projection ->
            state = if (projection == null) {
                state.copy(capturing = false, problem = R.string.capture_declined)
            } else {
                state.copy(capturing = true, problem = null, hostOutputVolume = hostOutputVolume.read())
                    .also { handler.post { followTheMode() } }
                    .also { handler.post(::aimVolumeKeys) }
                    .also {
                        if (Preferences(filesDir).read(CAPTURE_HOW_TO_KEY) != "off") {
                            handler.post { showingCaptureHowTo = true }
                        }
                    }
            }
        }
        startService(
            Intent(this, SyncProjectionService::class.java)
                .setAction(SyncProjectionService.ACTION_ACQUIRE)
                .putExtra(SyncProjectionService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(SyncProjectionService.EXTRA_RESULT_DATA, data)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything draws: every other thing this screen shows can be measured again, and
        // this one cannot - it is a person's opinion about which phone is on which side of the
        // sofa, and the phones cannot be asked.
        readTheDrawing()
        // Read once, here, rather than inside the composition - see themeChoice and showDetails.
        val prefs = Preferences(filesDir)
        themeChoice = themeChoiceOf(prefs.read("theme"))
        language = languageChoiceOf(prefs.read(LANGUAGE_KEY))
        // Off on every fresh open, since 2026-09-28: it is a switch for one evening of
        // troubleshooting, not a way to use the app. Only a fresh open - a screen rebuilt for a
        // rotation or a change of language keeps what was chosen minutes ago. Written rather than
        // only held, because wantsDetails reads the file for what it keeps.
        if (savedInstanceState == null) prefs.write("details", "off")
        showDetails = prefs.read("details") == "on"
        // Asked once, on the very first launch this screen is ever created for - a fresh install
        // or a fresh onCreate after the process was killed both read null the same way, which is
        // the only two cases that should show this screen at all.
        showingPermissions = prefs.read("seen_permissions") == null
        refreshPermissionsState()
        // Meant to be put down on a table and looked at, like every other screen in this app.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Android 15 does this to every app whether it asks or not, and older versions do not -
        // so without this line the same build has the screen edge under the clock on one handset
        // and stopping short of it on another, and the lit edge is a ring on one and a bracket
        // with the top missing on the other. The content is unaffected: the column inside
        // HomeScreen already carries safeDrawingPadding, which is what made Android 15 bearable.
        enableEdgeToEdge()
        setContent {
            SoundMeshTheme(themeChoice, language) {
                Surface {
                    // Its own branch rather than a block inside HomeScreen: the whole point of
                    // each is that nothing else is on screen while it is up. Checked first among
                    // the four: a first launch shows this and nothing else, whatever else would
                    // otherwise be up.
                    when {
                        showingPermissions -> {
                            // Back leaves this screen only when there is a screen behind it. On a
                            // first launch there is not, and swallowing the gesture there would
                            // trap somebody on the one screen that is meant to be tapped past.
                            BackHandler(enabled = showingSettings) { showingPermissions = false }
                            PermissionsScreen(
                                held = permissionsHeld,
                                askedBefore = permissionsAsked,
                                onAsk = ::askPermission,
                                onDone = {
                                    Preferences(filesDir).write("seen_permissions", "yes")
                                    showingPermissions = false
                                },
                                // Settings is behind it, so the button goes back rather than on.
                                doneLabel =
                                    if (showingSettings) R.string.perm_done else R.string.perm_continue
                            )
                        }
                        showingSettings -> {
                            BackHandler { showingSettings = false }
                            SettingsScreen(
                                prefs = Preferences(filesDir),
                                themeChoice = themeChoice,
                                language = language,
                                showDetails = showDetails,
                                onBack = { showingSettings = false },
                                onThemeChanged = { choice -> themeChoice = choice },
                                onLanguageChanged = { choice -> language = choice },
                                onDetailsChanged = { on -> showDetails = on },
                                selfCalibrated = state.selfCalibrated,
                                onSelfCalibrate = {
                                    startActivity(
                                        Intent(this@HomeActivity, CalibrateActivity::class.java)
                                    )
                                },
                                onPermissions = { showingPermissions = true }
                            )
                        }
                        showingCode -> PairCodeScreen(state.pairingOffer) { showingCode = false }
                        else -> {
                            // One step per press, up the three stages, rather than out of the app.
                            // Until this existed the back gesture left the room playing with the
                            // launcher on screen, from any stage, which is what a person does by
                            // reflex the first time they want to change the song.
                            val route = routeOf(state, steppedBack, holdingPlaying)
                            BackHandler(enabled = route != HomeRoute.WELCOME) { stepBack(route) }
                            HomeScreen(state, actions, showDetails, steppedBack, holdingPlaying) {
                                stepBack(route)
                            }
                            asking.firstOrNull()?.let { AskBeforePlaying(it) }
                            askingFine?.let { peerId ->
                                SayUntilTicked(
                                    text = stringResource(R.string.fine_calibration_note),
                                    quietLabel = stringResource(R.string.capture_how_to_quiet),
                                    cancel = stringResource(R.string.before_play_cancel),
                                    goOn = stringResource(R.string.before_play_go),
                                    onCancel = { quiet -> answerFine(quiet, open = null) },
                                    onGoOn = { quiet -> answerFine(quiet, open = peerId) }
                                )
                            }
                            if (showingCaptureHowTo) {
                                CaptureHowTo { quiet ->
                                    showingCaptureHowTo = false
                                    if (quiet) Preferences(filesDir).write(CAPTURE_HOW_TO_KEY, "off")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val actions = HomeActions(
        pickRole = { role ->
            holdingPlaying = false
            // Before the role changes, because everything that could stop the room reads the
            // role - see [leaveTheRoomPlaying].
            if (role != Role.HOST && state.running) leaveTheRoomPlaying()
            // Nobody has dragged a room slider that there is no room for. Cleared here rather
            // than left to expire because the latch is a field and the number it latches onto is
            // in [state], where stepping off the host role blanks it - see [roomVolumeShown] for
            // the missing slider the two of them made together, twice.
            if (role != Role.HOST) roomVolumeSet = false
            state = state.copy(role = role, problem = null)
            readPairing()
            takeUpTheRoom()
            // Said as the role is picked, not only at 进入播放: the output lead is measured on this
            // handset alone, and somebody who is about to set a room up is standing at it now. On
            // the pick and nowhere else - a resume is not somebody choosing to be the host.
            if (role == Role.HOST && state.selfCalibrated == null) {
                askingGoesOn = false
                asking = listOf(BeforePlaying.OwnLead)
            }
        },
        chooseSong = { chooseSong.launch(arrayOf(AUDIO_MIME)) },
        chooseFolder = { chooseFolder.launch(null) },
        captureAudio = ::captureAudio,
        scan = { startActivity(Intent(this, ScanActivity::class.java)) },
        play = ::play,
        stop = ::stopSession,
        calibrate = { startActivity(Intent(this, CalibrateActivity::class.java)) },
        // Told to the service rather than to the session directly: the session outlives this
        // screen on purpose, and reaching into it from here would be the one place that assumed
        // otherwise.
        setPaused = { wanted ->
            startService(request(SessionService.ACTION_SET_PAUSED).putExtra(SessionService.EXTRA_PAUSED, wanted))
        },
        stepSong = { by ->
            startService(request(SessionService.ACTION_STEP_SONG).putExtra(SessionService.EXTRA_SONG_STEP, by))
        },
        seek = { micros ->
            startService(request(SessionService.ACTION_SEEK).putExtra(SessionService.EXTRA_SEEK_MICROS, micros))
        },
        // The role travels with the intent: the pair calibration is directional, and this screen
        // is where the person already said which direction this phone is being.
        setRoomVolume = ::setRoomVolume,
        setHandsetVolume = ::setHandsetVolume,
        restoreVolume = ::restoreVolume,
        silenceMedia = ::silenceMedia,
        allowBackground = ::askToRunInBackground,
        pairCalibrate = {
            startActivity(
                Intent(this, PeerCalibrateActivity::class.java)
                    .putExtra("role", state.role.takeIf { it != Role.NONE }?.name)
            )
        },
        // What pressing a device's line does: the note on fine calibration first, unless that
        // handset has been through it or somebody ticked 不再提示 - see saysFineCalibrationFirst.
        calibratePeer = { peerId ->
            val carrying = state.standing.firstOrNull { it.peerId == peerId }?.carrying ?: Carried.UNSAID
            val ticked = Preferences(filesDir).read(FINE_CALIBRATION_NOTE_KEY) == "off"
            if (saysFineCalibrationFirst(carrying, ticked)) askingFine = peerId else openFineCalibration(peerId)
        },
        openSettings = { showingSettings = true },
        backToPlaying = { steppedBack = false },
        // The same stand-in a source pick already uses: nothing is playing, and the person is on
        // the playing stage anyway. See routeOf's `holding`.
        // Unless there is something to ask first - a host only, since only a host's press starts
        // a room. Each question is answered before the next is put, and the last 继续 goes on.
        enterPlaying = {
            askingGoesOn = true
            asking = if (state.role != Role.HOST) emptyList() else beforePlaying(
                ownLeadMissing = state.selfCalibrated == null,
                peers = state.standing.map { PeerCarrying(it.peerId, it.name, it.carrying) }
            )
            if (asking.isEmpty()) goOnToPlaying()
        },
        showPairCode = { showingCode = true },
        setCodeNetwork = { by ->
            Preferences(filesDir).write(Preferences.CODE_NETWORK, by.name)
            // Before the state is read back, so the screen that repaints is already describing a
            // room nobody is standing in - see HomeActions.setCodeNetwork for why they are let go.
            RoomCommands.letEverybodyGo()
            events.write("pairing code switched to ${by.name}, everybody standing by was let go")
            readPairing()
        },
        goto = { destination, job ->
            when (destination) {
                ReadyGoto.SONG -> chooseSong.launch(arrayOf(AUDIO_MIME))
                ReadyGoto.SELF_CALIBRATE -> startActivity(Intent(this, CalibrateActivity::class.java))
                // job picks which of PeerCalibrateScreen's three blocks renders - see PeerJob.kt.
                // Missing or unrecognised means PAIR, which is also what job being null here means.
                ReadyGoto.PAIR_CALIBRATE -> startActivity(
                    Intent(this, PeerCalibrateActivity::class.java)
                        .putExtra("role", state.role.takeIf { it != Role.NONE }?.name)
                        .putExtra(PEER_JOB_EXTRA, job?.name)
                )
                ReadyGoto.ALLOW_BACKGROUND -> askToRunInBackground()
                ReadyGoto.SCAN -> startActivity(Intent(this, ScanActivity::class.java))
                ReadyGoto.SHOW_CODE -> { showingCode = true }
            }
        },
        room = RoomActions(
            moveIcon = { moved -> updateRoom { withIconMoved(it, moved) } },
            // The round itself is on the calibration screen with every other round; this is a
            // way in from where its answer is drawn, because that is where somebody notices it
            // is missing. The role travels for the reason it does on pairCalibrate: only a host
            // can be the handset that is held.
            measureListener = {
                startActivity(
                    Intent(this, PeerCalibrateActivity::class.java)
                        .putExtra("role", CalibrationRole.HOST.name)
                )
            },
            // Nothing happens if it refuses; the offer is only on screen while it would not.
            fitToMeasured = {
                updateRoom { room ->
                    fitOffer(room)?.let {
                        room.copy(icons = it.icons, metresPerUnit = it.metresPerUnit, fitted = true)
                    } ?: room
                }
            },
            setDelayCompensation = { on -> updateRoom { it.copy(delayCompensation = on) } },
            pickMode = { mode -> updateRoom { it.copy(mode = mode) } },
            setPan = { pan -> updateRoom { it.copy(pan = pan) } },
            setSeparation = { apart -> updateRoom { it.copy(separation = apart) } },
            setEnvelopment = { keep -> updateRoom { it.copy(envelopment = keep) } },
            setPeriodSeconds = { seconds -> updateRoom { it.copy(periodSeconds = seconds) } },
            setRetreat = { back -> updateRoom { it.copy(retreat = back) } },
            setReverb = { room -> updateRoom { it.copy(reverb = room) } },
            pickAxis = { axis -> updateRoom { it.copy(splitAxis = axis) } },
            setCrossoverHz = { hz -> updateRoom { it.copy(crossoverHz = hz) } },
            togglePart = { peerId ->
                updateRoom {
                    it.copy(
                        otherHalfIds = if (peerId in it.otherHalfIds) it.otherHalfIds - peerId else it.otherHalfIds + peerId
                    )
                }
            }
        )
    )

    /**
     * Changes the drawing and tells the room about it in one step.
     *
     * Published on every touch rather than on letting go, because a rule is a function of the host
     * instant and takes effect on the next chunk: the room follows a finger. There is no cost to
     * publishing often - the control channel holds one rule and a new one replaces what is waiting
     * rather than queueing behind it.
     */
    private fun updateRoom(change: (RoomState) -> RoomState) {
        val room = change(state.room ?: return)
        state = state.copy(room = room)
        publish(room)
    }

    /**
     * Hands the room's drawing to the host, or says why it could not.
     *
     * A layout refuses to be built out of a room it cannot draw - two handsets sharing a name, a
     * handset sitting on the listener - and those refusals are worth keeping. What they are not
     * worth is the app: this runs off a five-a-second refresh on the main thread, so a roster that
     * arrived wrong took the whole process down and the listener saw the host vanish. Caught here
     * rather than softened there, because the next bad roster should still be findable.
     */
    private fun publish(room: RoomState) {
        val host = SessionService.ACTIVE as? HostSession ?: return
        val field = runCatching { fieldOf(room) }
            .onFailure { Log.e(LOG_TAG, "this room cannot be published, so it was not", it) }
            .getOrNull() ?: return
        host.publishSpatialField(field)
    }

    /** Null while there is nothing to draw; throws for a room that could not exist. */
    private fun fieldOf(room: RoomState): SpatialField? = ruleOf(room)

    /**
     * Points this screen's volume keys at whichever output the host is being heard on.
     *
     * The app cannot set the accessibility stream itself - `setStreamVolume` on it neither throws
     * nor moves anything - but the handset's own keys do move it, and they were measured doing so:
     * across four passes alternating the two outputs with the keys held down, media went 3 -> 15
     * and accessibility 4 -> 15. The keys follow whatever is playing, which is exactly why nobody
     * can reach this stream during a session: the app being captured holds the media stream the
     * whole time and wins them. Pointing them here, while this screen is in front, is the one
     * control that answers a listener saying the host sounds quiet.
     *
     * Back to the default when nothing is being captured, so an ordinary session's keys still do
     * the ordinary thing.
     */
    private fun aimVolumeKeys() {
        volumeControlStream =
            if (state.capturing) CAPTURING_HOST_STREAM else AudioManager.USE_DEFAULT_STREAM_TYPE
    }

    /**
     * Holds on to what was picked, then starts playing it to nobody to find out whether it can be
     * played at all.
     *
     * It used to copy the file into this app's own directory first, because the service took a
     * bare name under that directory and the sources took a File - the same path the harness that
     * every alignment measurement was taken through still uses. A folder of two hundred songs is
     * what ended that: the product hands over the address the system granted, and the ruler keeps
     * its file and its name check untouched.
     *
     * The permission has to be persisted here and nowhere else. Without it the grant lasts as long
     * as this process does, so the song somebody chose last night is one this app may no longer
     * open - a refusal at play time, for a file that is still sitting where they left it.
     *
     * Asking here rather than at play time is the other half: a refusal found when the service
     * fails to start would put the reason three layers away from the moment a person chose the
     * file. The check answers as soon as there is one chunk, where it used to convert the entire
     * song first.
     *
     * **It is a weaker check than it was, and deliberately.** Reading the whole song proved the
     * whole song decodes; opening a stream proves the container, the track, the codec and the
     * output format, and stops there. A file that breaks in its second half now fails while it is
     * playing rather than while it is being chosen. What that bought is a song of any length at
     * all, and a wait of well under a second instead of fifteen.
     */
    private fun adopt(kind: ChosenKind, uri: Uri) {
        state = state.copy(checking = true, problem = null, songName = null)
        Thread({
            val chosen = ChosenSource(getExternalFilesDir(null) ?: filesDir)
            val outcome = runCatching {
                // Not fatal, and it is the one call here that can refuse for reasons that have
                // nothing to do with the song: a provider that granted read but not a persistable
                // read throws. Without it the song plays now and is gone after a restart, which is
                // a far smaller loss than refusing a file that decodes perfectly well.
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure { Log.i(LOG_TAG, "this song's permission cannot be held past today", it) }
                val started = System.nanoTime()
                // Opened exactly the way the session will open it, and closed again: the codes it
                // throws are the ones the screen already knows how to say.
                StreamingChunkSource.open(this, songsOf(kind, uri)).close()
                Log.i(LOG_TAG, "what was chosen opened in ${(System.nanoTime() - started) / 1_000_000} ms")
                if (kind == ChosenKind.FOLDER) folderName(uri) else displayName(uri)
            }
            handler.post {
                outcome
                    .onSuccess { name ->
                        chosen.remember(kind, uri.toString(), name)
                        state = state.copy(
                            checking = false,
                            songName = name,
                            songUri = uri.toString(),
                            songIsFolder = kind == ChosenKind.FOLDER,
                            problem = null
                        )
                    }
                    .onFailure { error ->
                        Log.i(LOG_TAG, "the chosen song was refused", error)
                        chosen.forget()
                        state = state.copy(checking = false, songName = null, songUri = null, problem = SourceRejection.of(error.message))
                    }
            }
        }, "SoundMeshChooseSong").start()
    }

    /**
     * Asks for what capturing needs, in the order the platform wants it.
     *
     * Both permissions are asked for here rather than at play time, and for the same reason the
     * chosen song is decoded at pick time: a refusal that surfaced when the session failed to start
     * would put the reason three layers away from the moment a person asked for it.
     */
    private fun captureAudio() {
        state = state.copy(problem = null)
        // Nothing is put down here. Asking is not choosing, and the consent dialog is a dialog
        // somebody can decline - see the picker callbacks above for the same rule and the evening
        // it was reported on. What is playing is put down where consent is actually given.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        askProjection.launch(projectionManager().createScreenCaptureIntent())
    }

    /**
     * Stops the room and hands the projection back, which is what choosing something else means.
     *
     * The same as pressing stop, and pressed for them - on the same terms as [captureAudio], which
     * got this first and alone. Until this was here, opening the picker put the media stream back
     * and left the room singing the last song for as long as somebody took over choosing a file:
     * half the switch happened and half of it did not, which is the shape of it that is hardest to
     * read from the outside.
     */
    private fun putDownWhatIsPlaying() {
        // Whoever was watching the room play stays where they were watching it from. Asked of the
        // stage they are on rather than of what is playing, because the second switch in a row is
        // made from a stage that has already been put down once: reading `running` there answered
        // "nothing is playing" and sent them back to the board. Reported 2026-09-15.
        holdingPlaying = stillWatching(state, steppedBack, holdingPlaying)
        // Stopped before the projection goes, so a running capture is not read from a source that
        // has already been handed back.
        if (state.running) stopSession()
        releaseProjection()
    }

    /**
     * Stops what this handset is hosting because it has just stopped being the host.
     *
     * Somebody playing to a room and then picking 当从机 used to leave the whole session running:
     * this handset went on playing, every standing phone went on being fed, and the record saying
     * a host lives here went on being answered - by a handset that was now a sink. The next phone
     * to take the role could not command any of it, because none of it had ever been given up.
     *
     * The room is told here rather than left to [announceSession], which is the tick that notices
     * a session has gone. Two things stop it from saying anything by then: the role is no longer
     * HOST, which is the first line of that method, and [takeUpTheRoom] is about to shut the
     * command channel the message would have travelled on. The order is the whole fix.
     */
    private fun leaveTheRoomPlaying() {
        RoomCommands.send(RoomCommand.STOP)
        toldTheRoom = false
        stopSession()
    }

    /** Ends the session, which is also what tells the room to stop - see [announceSession]. */
    private fun stopSession() {
        awaitingSession = false
        startService(request(SessionService.ACTION_STOP))
    }

    private fun projectionManager(): MediaProjectionManager =
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /**
     * Hands the projection back, because holding one is visible and costs something.
     *
     * A held projection keeps a foreground service and the system's own recording indicator alive.
     * Choosing a file instead is the moment that says it is no longer wanted; a stopped session is
     * not, since pressing play again is the likeliest next thing and Android would otherwise ask
     * for consent all over again.
     */
    private fun releaseProjection() {
        if (!state.capturing) return
        state = state.copy(capturing = false, hostOutputVolume = null)
        followTheMode()
        aimVolumeKeys()
        startService(Intent(this, SyncProjectionService::class.java).setAction(SyncProjectionService.ACTION_RELEASE))
    }

    /** What the picker's provider calls the file, or a fallback rather than an empty line. */
    /**
     * What the session will be handed, read now so that choosing a folder can fail here.
     *
     * The session reads the folder again for itself when play is pressed - this listing is for the
     * check, not for the playing, and a folder that changed in between is the truth about the
     * folder rather than a disagreement worth preventing.
     */
    private fun songsOf(kind: ChosenKind, uri: Uri): List<Uri> =
        if (kind == ChosenKind.SONG) listOf(uri)
        else FolderSongs.of(this, uri).map { Uri.parse(it.uri) }

    /**
     * A folder's own name, which is not a column any provider offers on a tree.
     *
     * The document id it is built from is a provider's own string - "primary:Music/夜曲" on the
     * usual one - so the last segment is the folder as the listener sees it, and anything else is
     * shown whole rather than guessed at.
     */
    private fun folderName(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        val name = id?.substringAfterLast('/')?.substringAfterLast(':')
        return name?.takeIf { it.isNotBlank() } ?: getString(R.string.song_unnamed)
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: getString(R.string.song_unnamed)
    }

    private fun play() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val intent = when (state.role) {
            Role.HOST -> if (state.capturing) {
                request(SessionService.ACTION_START_HOST)
                    .putExtra(SessionService.EXTRA_CAPTURE_SOURCE, true)
            } else {
                request(SessionService.ACTION_START_HOST)
                    .putExtra(
                        if (state.songIsFolder) SessionService.EXTRA_SOURCE_FOLDER
                        else SessionService.EXTRA_SOURCE_URI,
                        state.songUri ?: return
                    )
                    // Only the single song needs it: a folder is listed by the service, which
                    // reads every name in it there. Harmless beside a folder and not worth a
                    // branch to leave out.
                    .putExtra(SessionService.EXTRA_SOURCE_NAME, state.songName ?: "")
            }
            Role.SINK -> state.paired?.let { code ->
                request(SessionService.ACTION_START_SINK)
                    .putExtra(SessionService.EXTRA_HOST_ADDRESS, code.address)
                    .putExtra(SessionService.EXTRA_CHUNK_PORT, code.chunkPort)
                    .putExtra(SessionService.EXTRA_PEER_ID, code.hostId)
            } ?: return
            Role.NONE -> return
        }
        awaitingSession = true
        // No tuning extras. A product session takes the service's own defaults, and the trim band
        // among them is PRODUCT_TRIM_FRAMES - the value that removed the hitching a listener could
        // hear. Passing an experiment's numbers here would silently undo that.
        startForegroundService(intent)
    }

    private fun request(action: String): Intent =
        Intent(this, SessionService::class.java).setAction(action)

    /** What the scanner left behind, and what a peer would have to scan to reach this handset. */
    private fun readPairing() {
        val store = ChosenSource(getExternalFilesDir(null) ?: filesDir)
        // Every resume, because a permission can be taken away between two of them - and because
        // this is also the first resume after an upgrade, which is when the old copy is still
        // sitting in this app's directory being as large as somebody's song.
        store.discardTheOldCopy()
        val held = runCatching { contentResolver.persistedUriPermissions.map { it.uri.toString() } }
            .getOrDefault(emptyList())
        val chosen = stillPermitted(store.chosen(), held)
        state = state.copy(
            paired = PairedHost(filesDir).read(),
            selfId = HostIdentity(filesDir).current(),
            // Re-read on every resume, because the way it changes is somebody leaving this
            // screen for the system one and coming back.
            backgroundAllowed = backgroundAllowed(),
            pairingOffer = HostPairingCode.offer(
                this,
                HostIdentity(filesDir).current(),
                SyncActivity.CHUNK_PORT,
                codeNetwork()
            ),
            codeChoices = HostPairingCode.choices(this).map { it.by }.distinct(),
            songName = chosen?.name,
            songUri = chosen?.uri,
            songIsFolder = chosen?.kind == ChosenKind.FOLDER,
            // Re-read for the same reason: CalibrateActivity is where this is measured, and
            // coming back from it is where the answer changes. Stored in microseconds - see
            // StoredOutputLead - and this field is milliseconds, the unit selfLead's line prints.
            selfCalibrated = StoredOutputLead(filesDir, CAPTURING_HOST_USAGE).read()
                ?.takeIf { it != 0L }?.let { it / 1000.0 }
        )
    }

    /**
     * One step up the three stages, which is what both the gesture and the arrow do.
     *
     * Says nothing to the session on purpose: back out of a playing room and the music carries on,
     * this screen simply stops being the one in front of it.
     */
    private fun stepBack(route: HomeRoute) {
        when (route) {
            HomeRoute.PLAYING -> {
                steppedBack = true
                holdingPlaying = false
            }
            HomeRoute.READY -> actions.pickRole(Role.NONE)
            HomeRoute.WELCOME -> Unit
        }
    }

    /**
     * The pair screen aimed at one handset. Opens the screen and starts nothing. It used to carry
     * `auto`, which meant a chip on a roster line began a minute of chirps in a room nobody had been
     * asked to quieten - and the instructions for that minute were on the screen it had already
     * started behind. `serve_many` is gone with it: a round is between this host and the handset
     * whose line was pressed, and waiting afterwards for a second one to volunteer was a queue
     * nothing on screen described.
     */
    private fun openFineCalibration(peerId: String) {
        startActivity(
            Intent(this, PeerCalibrateActivity::class.java)
                .putExtra("role", CalibrationRole.HOST.name)
                .putExtra(PEER_JOB_EXTRA, PeerJob.PAIR.name)
                .putExtra(PeerCalibrateActivity.AIMED_AT_EXTRA, peerId)
        )
    }

    /** The fine calibration note answered: the tick kept whichever way, then [open] if going on. */
    private fun answerFine(quiet: Boolean, open: String?) {
        askingFine = null
        if (quiet) Preferences(filesDir).write(FINE_CALIBRATION_NOTE_KEY, "off")
        open?.let(::openFineCalibration)
    }

    /** Onto the playing stage with nothing playing - what 进入播放 does once nothing is asked. */
    private fun goOnToPlaying() {
        steppedBack = false
        holdingPlaying = true
    }

    /**
     * The first of [asking]. 去校准 drops the rest: whoever went to fix one thing comes back to the
     * board and presses 进入播放 again, which asks afresh about whatever is still wrong then. For the
     * uncalibrated handsets it goes to the room round, which lines up every one of them at once.
     */
    @Composable
    private fun AskBeforePlaying(ask: BeforePlaying) {
        AskBeforeGoing(
            text = when (ask) {
                BeforePlaying.NobodyJoined -> stringResource(R.string.before_play_nobody)
                BeforePlaying.OwnLead -> stringResource(R.string.before_play_own_lead)
                is BeforePlaying.Uncalibrated -> stringResource(
                    R.string.before_play_uncalibrated,
                    ask.names.joinToString(stringResource(R.string.room_volume_name_join))
                )
            },
            cancel = stringResource(R.string.before_play_cancel),
            // Nothing to go and calibrate when nobody has joined: that is fixed on the other phones.
            fix = if (ask == BeforePlaying.NobodyJoined) null else stringResource(R.string.before_play_calibrate),
            goOn = stringResource(R.string.before_play_go),
            onCancel = { asking = emptyList() },
            onFix = {
                asking = emptyList()
                when (ask) {
                    BeforePlaying.NobodyJoined -> Unit
                    BeforePlaying.OwnLead -> actions.goto(ReadyGoto.SELF_CALIBRATE, null)
                    is BeforePlaying.Uncalibrated -> actions.goto(ReadyGoto.PAIR_CALIBRATE, PeerJob.ROOM)
                }
            },
            onGoOn = {
                asking = asking.drop(1)
                if (asking.isEmpty() && askingGoesOn) goOnToPlaying()
            }
        )
    }

    /** Which network somebody said the code is for, or null if nobody has. See [HostPairingCode]. */
    private fun codeNetwork(): LocalAddress.ReachedBy? =
        Preferences(filesDir).read(Preferences.CODE_NETWORK)
            ?.let { saved -> LocalAddress.ReachedBy.entries.firstOrNull { it.name == saved } }

    private fun readSession() {
        val session = SessionService.ACTIVE
        if (session != null) {
            awaitingSession = false
            // The session itself is what holds the stage now, so the stand-in is given back.
            holdingPlaying = false
        }
        // Put back down here rather than when play is pressed, because a sink joins a room it
        // never pressed anything to start. With no session there is nothing to have stepped back
        // out of, so this is the one moment it can be cleared without guessing.
        if (session == null) steppedBack = false
        val room = readRoom(session)
        // Worked out here rather than inside readRoom because it is the one reading that changes
        // when nothing else has: the wander moves on its own, and readRoom deliberately hands the
        // same RoomState back when the roster has not changed. Host only - the instant is read off
        // this handset's own clock, which is the host clock on the host and an unknown offset away
        // from it on a sink, and a strip of confidently wrong numbers is worse than no strip.
        val readings = if (showDetails && room != null && state.role == Role.HOST) {
            fieldOf(room)?.let { roomReadings(it, System.nanoTime()) }.orEmpty()
        } else emptyList()
        state = state.copy(
            roomReadings = readings,
            running = session != null,
            sessionState = session?.state(),
            playhead = session?.playhead(),
            nowPlaying = session?.nowPlaying(),
            paused = session?.paused() == true,
            failure = if (awaitingSession) SessionService.FAILURE else null,
            // Between the tap and the session being up. The service refuses a second start on its
            // own - see SessionService.startSession - and this is the half a person can see: the
            // button goes quiet rather than looking like it did nothing.
            starting = awaitingSession && SessionService.FAILURE == null,
            counters = SessionReadout.counters(session?.report()),
            // Only ever what handsets said about themselves - see RoomCommandServer.notExemptNames.
            // A host cannot read another phone's battery settings, and every screen this feeds
            // names a phone, so a guess here would send somebody to the wrong handset's settings.
            blockedPeerNames = RoomCommands.notExemptNames(),
            room = room,
            // Three places for one reading, tried in the order they become true. A session knows
            // it, and before there is a session the standing channel does: the host settles the
            // table there and every sink is told it. The third is a sink's copy of the same
            // table. Null only where no host has met this handset yet, which is the number on
            // screen with no colour beside it.
            selfPlace = session?.badgePlace()
                ?: RoomCommands.places()[state.selfId]
                ?: StandbyService.ACTIVE?.place(),
            standingBy = RoomCommands.standingBy().also { standing ->
                if (state.role == Role.HOST && standing != wroteStandingBy) {
                    wroteStandingBy = standing
                    events.write(
                    "standing by: $standing, ${RoomCommands.uncalibrated()} uncalibrated, " +
                        "${RoomCommands.approximate()} on a room round"
                )
                }
            },
            roomVolumes = volumeRows(),
            standing = rosterOf(
                peerIds = RoomCommands.standingPeerIds(),
                name = RoomCommands::nameOf,
                carrying = RoomCommands.carrying(),
                quiet = RoomCommands.quietPeerIds().toSet(),
                excuses = RoomCommands.excuses()
            ),
            uncalibrated = RoomCommands.uncalibrated(),
            approximate = RoomCommands.approximate(),
            calledHere = handsetName(this),
            onStandby = StandbyService.ACTIVE?.connected == true,
            // Only while a capture is actually running. Silence from a source that is not open
            // is not a reading, and a stale one on screen is worse than none.
            captureSilentSeconds =
                if (session == null || !state.capturing) null
                else (CaptureSilence.silentNanos() / 1_000_000_000L).toInt()
        )
        announceSession(session != null)
    }

    /**
     * Tells everybody standing by to start when this host own session comes up, and to stop when
     * it goes away.
     *
     * On the session appearing rather than on the button being pressed, and that is the whole of
     * it: a sink starts by dialling this host chunk port, and a sink told to play a moment before
     * that port was bound gets a refused connection and a failure on its screen. The session
     * appearing is the first instant the answer would be yes.
     */
    private fun announceSession(running: Boolean) {
        if (state.role != Role.HOST) return
        val joined = state.standingBy > toldThisMany
        toldThisMany = state.standingBy
        // Said again to a handset that has only just arrived, and only while the room is playing.
        // Nothing is replayed on the channel itself - see RoomCommandServer - so this is the host
        // deciding to repeat itself rather than the channel remembering, which is the difference
        // that matters: it happens while somebody is looking at a room that is playing. What it
        // is for is the handset that just finished measuring and put itself back on this screen.
        if (running && toldTheRoom && joined) {
            RoomCommands.send(RoomCommand.PLAY)
            return
        }
        if (running == toldTheRoom) return
        toldTheRoom = running
        RoomCommands.send(if (running) RoomCommand.PLAY else RoomCommand.STOP)
    }

    /**
     * The drawing brought up to date with who is in the room, keeping every icon already dragged.
     *
     * Read here rather than pushed because the roster has no event to push: a sink joins by
     * opening a socket, and this loop is already asking the session how it is several times a
     * second. Rebuilding the drawing from scratch each pass would throw away the listener's
     * arrangement while they were still looking at it, which is what [SpatialRoom.reconciled] is.
     *
     * A room whose membership changed is published straight away, so a handset that just joined
     * starts playing its own corner rather than the whole room flat until somebody moves a control.
     */
    private fun readRoom(session: SyncSession?): RoomState? {
        // A session ending is not the room changing. The drawing used to live exactly as long as
        // the session did, so a song finishing threw away every icon that had been dragged and
        // the fit that had been taken - and the next press of play started from the default
        // arrangement with nothing on screen saying anything had been lost. It is kept here
        // instead, for as long as this phone is being the host at all.
        val host = session as? HostSession
            ?: return keptRoom()
        val roster = host.roomPeerIds()
        val previous = state.room ?: restored.copy(selfId = roster.firstOrNull())
        // Where each handset was last seen, including ones not in the room just now. A sink
        // reconnects a few seconds after its host, so without this every restart is a roster
        // that grew - and a handset that grew back into the room would be given a default
        // position, which is the same lost drag by a slower route.
        whereTheyWere.putAll(previous.icons.associateBy { it.peerId })
        val icons = SpatialRoom.reconciled(previous.icons, roster, whereTheyWere)
        // Both channels that can say a handset stopped, read into one set: being sent no audio,
        // and having stopped asking this host for the time. The second is the only one that moves
        // when a phone leaves the network rather than closing its connections.
        val silent = whoStopped(roster, host.audioPeerIds(), host.quietPeerIds()).toSet()
        if (icons.map { it.peerId } == previous.icons.map { it.peerId }) {
            // A handset stopping does not change the roster, so this cannot share the early
            // return above. Not published either: nothing about the rule changed, and a handset
            // that comes back has to find the room it left.
            return if (silent == previous.silentIds) previous else previous.copy(silentIds = silent)
        }
        // Read here and not every pass: these come off disk, and this loop runs five times a
        // second. The roster changing is the only thing that can bring a new one into play.
        val measured = measuredDistances(filesDir, roster.firstOrNull(), icons.map { it.peerId })
        return previous.copy(
            icons = icons,
            measuredMetres = measured,
            listenerMetres = StoredListenerDistance.all(filesDir),
            // A new handset is a fresh reason to offer, whatever was done with the last one.
            // The scale is deliberately **not** cleared here: it says how many metres one unit of
            // this drawing is, which somebody joining does not change. What does change it is a
            // fresh measurement, and rereadDistances is where that lands.
            fitted = false,
            colours = host.roomPlaces(),
            silentIds = silent,
            otherHalfIds = sidesTheyCarried.reconciled(
                previous.otherHalfIds,
                before = previous.icons.map { it.peerId },
                after = icons.map { it.peerId }
            )
        ).also(::publish)
    }

    /**
     * The drawing between sessions: everybody standing by, with the ones that are not hollowed out.
     *
     * The icons are the ones the last session left behind - that is deliberate, and why this is
     * kept at all. What was not deliberate is that nothing ever changed them: a handset switched
     * off while nobody was playing stayed solid for as long as anybody looked at it, on a screen
     * that said zero standing by two lines further down. Reported on 2026-09-14.
     *
     * The other half of the same fault, reported the same evening: it could hollow handsets out
     * but never add one. A phone that opened its app while nobody was playing showed up in the
     * volume list immediately - that is built from the standing channel - and was absent from the
     * drawing until somebody pressed play, because the drawing had no roster outside a session.
     * Two components on one screen, two ideas of who was in the room.
     */
    private fun keptRoom(): RoomState? {
        if (state.role != Role.HOST) return null
        // Built off the saved drawing when this host has not played yet, rather than off nothing.
        // A null room here used to mean an empty map until somebody pressed play, even with a
        // drawing sitting on disk and handsets standing by.
        val kept = state.room ?: restored.copy(selfId = state.selfId)
        val standing = RoomCommands.standingPeerIds()
        val roster = betweenSessionsRoster(kept.selfId ?: state.selfId, kept.icons.map { it.peerId }, standing)
        val icons = SpatialRoom.reconciled(kept.icons, roster, whereTheyWere)
        val silent = whoIsNotStandingBy(roster, standing).toSet()
        // Beside silentIds rather than only in the rebuild below, and for the same reason it is:
        // the colours move on events the icon roster does not. A host holds one before any sink
        // has arrived, and a handset that leaves gives one back - neither adds or removes an icon,
        // and the early return below is taken on exactly those passes.
        val places = RoomCommands.places()
        if (icons.map { it.peerId } == kept.icons.map { it.peerId }) {
            if (silent == kept.silentIds && places == kept.colours) return kept
            return kept.copy(silentIds = silent, colours = places)
        }
        // Off disk, and only when the roster actually changed - this runs five times a second.
        // The same three reads the session path does for the same reason, so that a handset which
        // arrived between songs is drawn against the same distances as one that arrived during a
        // song, rather than against whatever the last session happened to leave.
        return kept.copy(
            selfId = kept.selfId ?: state.selfId,
            icons = icons,
            measuredMetres = measuredDistances(filesDir, roster.firstOrNull(), icons.map { it.peerId }),
            listenerMetres = StoredListenerDistance.all(filesDir),
            fitted = false,
            colours = places,
            silentIds = silent,
            otherHalfIds = sidesTheyCarried.reconciled(
                kept.otherHalfIds,
                before = kept.icons.map { it.peerId },
                after = icons.map { it.peerId }
            )
        )
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        // Re-read rather than kept: a scan happens in another activity, and this is where its
        // result arrives. The pairing code is re-encoded for the same reason ShowCodeActivity does
        // it - a handset that changed network is otherwise showing an address it no longer has.
        readPairing()
        rereadDistances()
        readTheDrawing()
        readNetwork()
        takeUpTheRoom()
        // Where a return from the system's own Settings page - the SETTINGS route above, or the
        // background-battery ask - is noticed: neither has a callback of its own on this side.
        refreshPermissionsState()
        handler.post(refresh)
    }

    /**
     * Whether this handset is on WiFi at all.
     *
     * Re-read on the slow tick as well as on every resume, and the tick is the half that matters:
     * the way somebody turns a hotspot on is by pulling the shade down over this screen, which is
     * not a resume at all. Until 2026-09-15 this screen would sit there saying the hotspot was off
     * while the phone was serving one, and only a trip to another screen and back would fix it.
     *
     * The question this answers is "is this phone on WiFi", not "what is this phone's SSID" - the
     * two used to be the same read, `WifiManager.connectionInfo.ssid`, and that one has not been
     * able to answer on a real device since Android 10: it needs ACCESS_FINE_LOCATION *and* live
     * location services, neither of which this app asks for just to print a network name. Asked of
     * [ConnectivityManager] instead, which needs no permission at all - see [NetworkCapabilities].
     * The name itself was shown beside it until 2026-09-18 and is not read at all any more: it was
     * unreadable on every handset here, so the line it fed said "读不到网络名称" and nothing else.
     */
    private fun readNetwork() {
        val onWifi = runCatching {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        state = state.copy(
            onWifi = onWifi,
            localNet = if (!onWifi) null else
                localNetOf(getSystemService(ConnectivityManager::class.java)),
            // The code is part of the same answer: which address a peer would have to reach is
            // the network question said a second way, and the two going out of step is how a
            // handset ends up scanning a code for a network nobody is on any more.
            codeChoices = HostPairingCode.choices(this).map { it.by }.distinct(),
            pairingOffer = HostPairingCode.offer(
                this,
                HostIdentity(filesDir).current(),
                SyncActivity.CHUNK_PORT,
                codeNetwork()
            )
        )
    }

    /**
     * Opens whichever end of the standing channel this handset is, and closes the other.
     *
     * Called on every resume and on every change of role, because both change the answer and
     * neither has an event of its own that the other would see.
     */
    private fun takeUpTheRoom() {
        events.write("role ${state.role}")
        // Whether this call is the role changing or just the screen coming back. Everything below
        // is safe to redo on a resume; refusing the role is not - see [refuseToBeTheSecondHost].
        val changed = roleTakenUp != state.role
        roleTakenUp = state.role
        when (state.role) {
            // Left open when this screen goes away, unlike the sink end. The host tells the room
            // to go and measure from inside the calibration screen - see RoomCommands - and this
            // screen is paused by then.
            Role.HOST -> {
                stopStandingBy()
                RoomCommands.serve(HostIdentity(filesDir).current())
                // Said on the network from here rather than from the session, which is the whole
                // of what makes a sink able to find this handset before anybody presses play -
                // see HostBeacon. Standing by is where a room spends nearly all of its time.
                HostBeacon.hold(this, HostIdentity(filesDir).current(), HostBeacon.Holder.ROLE)
                // A host points at nobody. Kept until 2026-09-18, which was harmless while a
                // code had to be scanned and is not now: the standby service skips looking for
                // a host whenever one is remembered, so this handset going back to being a sink
                // would dial whoever it was pointed at weeks ago and never see the room it is
                // standing in. Picking this role is the newest thing a person has said about
                // where this handset belongs, so it is the one that wins.
                PairedHost(filesDir).forget()
                if (changed) refuseToBeTheSecondHost()
                // Said out loud because the count going down has no other trace at all: on
                // 09-13 a room went from three standing to none and the only evidence was the
                // number itself, which cannot say whether they left or were dropped.
                val log = EventLog(filesDir)
                RoomCommands.listenForDepartures { peerId, why ->
                    log.write("standing-left $peerId: $why")
                }
                // Written down because a row that does not move has two possible reasons - the
                // report never arrived, or it arrived saying the same thing - and from the screen
                // they are the same picture. Two rounds of this bug were spent guessing between
                // them.
                RoomCommands.listenForVolumes { peerId, said ->
                    log.write("standing-volume $peerId: ${said.index}/${said.max} on ${said.stream}")
                }
            }
            // Handed to a service of its own, which is what lets it outlive this screen: a
            // handset lying face down on a table is the ordinary way a room of them is used, and
            // with the line on this screen those handsets were simply not in the room.
            Role.SINK -> {
                RoomCommands.stop()
                HostBeacon.release(HostBeacon.Holder.ROLE)
                // Started with nothing to dial, which it never used to be. Looking for the host is
                // the service's job now - it is the thing that is still running when the host is
                // finally switched on, and that is usually minutes after somebody set this handset
                // down. See StandbyService.lookForAHost.
                startForegroundService(Intent(this, StandbyService::class.java))
            }
            Role.NONE -> {
                RoomCommands.stop()
                HostBeacon.release(HostBeacon.Holder.ROLE)
                stopStandingBy()
            }
        }
    }

    /**
     * Sends this handset back to the role screen if the network already has a host.
     *
     * One host is not a preference. Two of them is two timelines, two spatial fields and two sets
     * of volumes over one set of phones, and every sink in the room silently belongs to whichever
     * one it happened to find - so this is refused where it is chosen rather than reported later,
     * when there is nothing left to do about it.
     *
     * Only what it can see. A network that does not carry multicast between its clients answers
     * exactly as an empty one does, so the refusal is true whenever it fires and its silence
     * promises nothing - which is also why the code on this screen never goes away.
     *
     * Only where the role is taken, which is the half that was wrong until 2026-09-19. It used to
     * run on every resume, so it was not "this handset may not become the second host" but
     * "whichever handset last came back to this screen loses" - and what somebody hit was two
     * phones settled as host and sink, the host walking back into this screen, and the host being
     * thrown out to the role picker. See [takeUpTheRoom] for where that is decided.
     *
     * Not while something is playing: by then this handset has a room, and a handset that took the
     * role a minute ago is the one that should give way.
     */
    private fun refuseToBeTheSecondHost() {
        val myId = HostIdentity(filesDir).current()
        Thread({
            val other = runCatching {
                HostSearch.anotherHost(this, myId, HostSearch.WINDOW_MILLIS)
            }.getOrNull()
            // Asked of the handset rather than of the record, and it is the whole of the fix for
            // what a person hits by pressing these two roles back and forth: the record of a
            // handset that has just stopped being a host goes on being answered for seconds, and
            // other devices' caches hold it longer still. Stepping down for one of those leaves a
            // room with no host at all, and the stale record is gone by the time anybody looks for
            // the reason. See [RoomCommands.stillServing].
            if (other != null && !RoomCommands.stillServing(other)) {
                events.write("staying host: $other answered with a record but is not serving")
                return@Thread
            }
            // Only where nothing answered, and asked at all because on a hotspot nothing ever
            // will: a handset serving its own hotspot is not found by the handsets on it
            // (2026-09-22), so this rule held in exactly the networks that never needed it. That
            // handset is the default gateway, which is the one address nobody has to look for.
            //
            // Nothing asks whether that answer is live, unlike the record above. What answers is
            // started and stopped by the same holder that says the record - see HostBeacon - so
            // it cannot outlive the role it speaks for, and a record can and for a minute does.
            val host = other ?: HostSearch.anotherHostAtTheGateway(this, myId)?.also {
                // Said here because which of the two paths found the host is the one thing a
                // timeline cannot work out afterwards, and on 2026-09-21 a round that looked like
                // this path working turned out to be the records working.
                events.write("nothing answered, but $it is hosting at the gateway")
            } ?: return@Thread
            runOnUiThread {
                if (state.role != Role.HOST || state.running) return@runOnUiThread
                // Nor if this handset already has a room, which is now belt and braces rather
                // than the guard it was: the check no longer runs on a resume at all.
                if (RoomCommands.standingBy() > 0) return@runOnUiThread
                events.write("stepping down as host: $host is already one")
                actions.pickRole(Role.NONE)
                state = state.copy(problem = R.string.role_host_taken)
            }
        }, "SoundMeshHostCheck").start()
    }

    /**
     * Whether this handset lets this app go on running with nobody looking at it.
     *
     * The standard bit, and on 2026-09-14 it was the one that mattered: a handset with this
     * off had its standing service killed within seconds of the home button, which reached the
     * host as a handset that had left the room and reached the listener as one phone silent.
     */
    private fun backgroundAllowed(): Boolean = runCatching {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(true)

    /**
     * Asks for it, in the one place the platform lets an app ask.
     *
     * This covers the standard rule only. The vendor lists beside it - the three switches
     * under an EMUI app launch manager, and every equivalent - have no API at all: nothing can
     * read them, ask for them, or link to them, so a handset that still stops after this has to
     * be dealt with by hand. Falling back to the list rather than failing silently, because
     * some builds refuse the direct request, and a button that answers nothing is one people
     * learn not to press.
     */
    private fun askToRunInBackground() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        if (runCatching { startActivity(direct) }.isSuccess) return
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /**
     * What [permissionsHeld] and [permissionsAsked] actually are right now, read off the system
     * and off [Preferences].
     *
     * Called from [onCreate], from [onResume] - the point this screen comes back from either the
     * system's own permission dialog or its Settings page - and from each of the three dialog
     * launchers above, so [PermissionsScreen] is never left showing a button for something that
     * was just granted or just asked for.
     */
    private fun refreshPermissionsState() {
        val prefs = Preferences(filesDir)
        permissionsHeld = buildSet {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            // Below Tiramisu there is no such permission to hold - notifications simply post -
            // so the row reads as already granted rather than offering a button for nothing.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (backgroundAllowed()) add(PERMISSION_BACKGROUND)
        }
        permissionsAsked = PERMISSIONS_SCREEN_KEYS.filterTo(mutableSetOf()) { prefs.read("asked_$it") != null }
    }

    /**
     * What a tap on one of [PermissionsScreen]'s rows does - [askRoute]'s decision, acted on.
     *
     * The write to [Preferences] happens the instant the dialog is launched, not in the launcher's
     * callback: Android never says which refusal was "don't ask again", so what is recorded is
     * that this screen asked, not what was answered - see [askRoute]'s own doc.
     */
    private fun askPermission(permission: String) {
        val prefs = Preferences(filesDir)
        val granted = permission in permissionsHeld
        val askedBefore = permission in permissionsAsked
        when (askRoute(granted, askedBefore)) {
            AskRoute.NOTHING -> {}
            AskRoute.DIALOG -> {
                prefs.write("asked_$permission", "yes")
                permissionsAsked = permissionsAsked + permission
                when (permission) {
                    Manifest.permission.RECORD_AUDIO -> askPermMic.launch(permission)
                    Manifest.permission.POST_NOTIFICATIONS -> askPermNotify.launch(permission)
                    Manifest.permission.CAMERA -> askPermCamera.launch(permission)
                    PERMISSION_BACKGROUND -> askToRunInBackground()
                }
            }
            AskRoute.SETTINGS -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun stopStandingBy() {
        stopService(Intent(this, StandbyService::class.java))
    }

    /**
     * Sets this handset's own volume and says up the line what it actually came to.
     *
     * Read back rather than echoed. setStreamVolume has been seen on these handsets to take a
     * value and move nothing, and under do-not-disturb it throws instead - so a host shown what
     * it asked for would be shown a room in agreement that is not one.
     */
    private fun applyVolume(percent: Int) {
        val now = handsetVolume.set(percent, state.capturing)
        events.write("volume set to $percent%: ${now.index}/${now.max} on ${now.stream}")
        state = state.copy(volumeChanged = handsetVolume.changed())
    }

    private fun putVolumeBack() {
        // Back to following this phone as well, because "as if this app had never touched it"
        // includes the number on the slider.
        roomVolumeSet = false
        handsetVolume.restore()
        val now = handsetVolume.read(state.capturing)
        events.write("volume put back: ${now.index}/${now.max} on ${now.stream}")
        state = state.copy(volumeChanged = handsetVolume.changed(), roomVolumePercent = now.percent)
    }

    /**
     * Moves the volume with the mode, so switching modes is one press rather than two.
     *
     * Changing mode changes which stream this handset plays on, and both directions need it. Into
     * the capturing mode: the app being captured is heard on media live while the room plays the
     * same thing a second later, so media is silenced - and until this existed that
     * only happened on the next drag of the slider, which is a listener being told to go and
     * touch something to finish a switch they already made. Out of it: the stream about to be
     * played on is the one that was silenced, so leaving without this is a phone at zero.
     *
     * Only this handset. The room is where it was; nothing here changed for anybody else.
     */
    private fun followTheMode() {
        val now = handsetVolume.moveTo(state.capturing, state.roomVolumePercent)
        events.write(
            "volume follows the mode: ${now.index}/${now.max} on ${now.stream}" +
                (state.roomVolumePercent?.let { ", room at $it%" } ?: ", no room volume set")
        )
        state = state.copy(volumeChanged = handsetVolume.changed())
    }

    /**
     * Media back to zero, from the button under the line that says this host hears it twice.
     *
     * The line goes when media is read back at zero, not when the button is pressed: read here
     * rather than left to the next tick so it goes at once, and a stream that refused the set
     * leaves the line and the button standing - which is what is true.
     */
    private fun silenceMedia() {
        handsetVolume.silenceMedia()
        val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        events.write("media silenced from the heard-twice line: now $index")
        state = state.copy(volumeChanged = handsetVolume.changed(), mediaIndex = index)
    }

    /**
     * What each handset in the room actually landed on, this one first.
     *
     * This handset's own row is read from its streams here and now; everybody else's is what they
     * said after setting theirs. Neither is what the host asked for, which is the point: a row
     * that disagrees with the slider is the only way a stream that refused to move can be seen.
     */
    private fun volumeRows(): List<VolumeRow> {
        if (state.role != Role.HOST) return emptyList()
        val self = HostIdentity(filesDir).current()
        val mine = handsetVolume.read(state.capturing)
        val now = System.currentTimeMillis()
        return listOf(rowFor(self, handsetName(this), mine.index, mine.max, mine.stream, now)) +
            RoomCommands.volumes().map { (peerId, said) ->
                rowFor(
                    peerId,
                    RoomCommands.nameOf(peerId) ?: peerId.takeLast(SHORT_NAME_CHARACTERS),
                    said.index,
                    said.max,
                    said.stream,
                    RoomCommands.volumeSaidAt(peerId)
                )
            }
    }

    /**
     * One row, and the one place that notices a handset moving without having been asked.
     *
     * The noticing has to be here rather than beside the sending, because the whole point is that
     * nothing was sent: somebody picked that phone up and pressed its keys. This loop is the only
     * thing watching.
     */
    private fun rowFor(
        peerId: String,
        name: String,
        index: Int,
        max: Int,
        stream: String,
        // Null for a handset that has never said. This handset's own row reads its streams here
        // and now, so for it the answer is always this instant.
        saidAt: Long?
    ): VolumeRow {
        val before = lastSeenIndex.put(peerId, index)
        // Then the host's instruction is no longer the newest word about that handset, and the
        // thumb goes back to following it.
        if (somebodyElseMovedIt(toldToBe[peerId]?.percent, before, index, max)) toldToBe.remove(peerId)
        val told = toldToBe[peerId]
        return VolumeRow(
            peerId,
            name,
            percentOf(index, max),
            index,
            max,
            stream,
            told?.percent,
            volumeComplaint(
                told?.percent,
                index,
                max,
                told?.at ?: 0L,
                saidAt,
                System.currentTimeMillis()
            )
        )
    }

    /**
     * Tells the whole room, this handset included, what volume to be.
     *
     * The host is in the room rather than driving it from outside: whoever drags this wants the
     * music quieter, and the phone in their hand is the loudest one there.
     */
    private fun setRoomVolume(percent: Int) {
        roomVolumeSet = true
        state = state.copy(roomVolumePercent = percent)
        // Everybody, including the ones singled out a moment ago: the room slider levels the room.
        val told = Told(percent, System.currentTimeMillis())
        for (row in state.roomVolumes) toldToBe[row.peerId] = told
        toldToBe[HostIdentity(filesDir).current()] = told
        RoomCommands.send(RoomOrder(RoomCommand.SET_VOLUME, percent))
        applyVolume(percent)
    }

    /**
     * One handset on its own, for the one standing next to a wall.
     *
     * Nothing is remembered about it having been singled out. The next drag of the room slider
     * levels everybody including this one, which is what was asked for and is also the only
     * version of this anybody can reason about: a room where some handsets quietly opt out of
     * the room volume is a room whose slider means nothing in particular.
     */
    private fun setHandsetVolume(peerId: String, percent: Int) {
        toldToBe[peerId] = Told(percent, System.currentTimeMillis())
        if (peerId == HostIdentity(filesDir).current()) return applyVolume(percent)
        val reached = RoomCommands.sendTo(peerId, RoomOrder(RoomCommand.SET_VOLUME, percent))
        events.write("volume for one handset: $peerId to $percent%" + if (reached) "" else ", no line to it")
    }

    private fun restoreVolume() {
        toldToBe.clear()
        RoomCommands.send(RoomCommand.RESTORE_VOLUME)
        putVolumeBack()
    }

    /**
     * The room's measured distances re-read, because a calibration runs in another activity and
     * coming back here is where its result arrives.
     *
     * Not on the refresh loop, which runs five times a second against a file on disk, and not on
     * the roster changing either - the roster is exactly what does not change when the same three
     * phones measure themselves and come back. Left there, a run would produce numbers no screen
     * ever showed until somebody left the room.
     *
     * A reading that came back the same is dropped rather than stored, so that returning to this
     * screen does not re-offer a fit the person has already taken.
     */
    private fun rereadDistances() {
        val room = state.room ?: return
        val measured = measuredDistances(
            filesDir,
            room.icons.firstOrNull()?.peerId,
            room.icons.map { it.peerId }
        )
        val listener = StoredListenerDistance.all(filesDir)
        if (measured == room.measuredMetres && listener == room.listenerMetres) return
        state = state.copy(
            room = room.copy(
                measuredMetres = measured,
                listenerMetres = listener,
                fitted = false,
                metresPerUnit = 0.0
            )
        )
    }

    /**
     * The standing line is deliberately left alone here: it belongs to [StandbyService] now, and
     * outliving this screen is the whole point of having moved it.
     */
    override fun onPause() {
        inFront = false
        handler.removeCallbacks(refresh)
        keepTheDrawing()
        super.onPause()
    }

    /**
     * Writes down the room as it stands, because this is the last moment that is certain to run.
     *
     * On leaving rather than on every change: a drag publishes on every touch, and a file written
     * fifty times a second to survive something that happens once is a cost paid continuously for
     * a benefit that is not. What this does not cover is the process going down without ever
     * pausing - a crash, or a force-stop - and that is the trade being made.
     */
    /**
     * Reads the drawing back off disk, which is where the calibration screen leaves it.
     *
     * On every resume and not only on starting, because the screen this one hands off to draws
     * the same room and lets somebody arrange it there - see [MeasuredRoom]. Without this, a
     * person would place the phones where the measuring happens, walk back to where the music
     * plays, and find the old arrangement, with nothing on either screen saying which of the two
     * was being played.
     */
    private fun readTheDrawing() {
        val saved = StoredRoomDrawing(filesDir).read() ?: return
        whereTheyWere.putAll(saved.placements.associateBy { it.peerId })
        sidesTheyCarried.remember(saved.room.otherHalfIds)
        restored = saved.room
        state.room?.let { state = state.copy(room = it.readBack(saved)) }
    }

    private fun keepTheDrawing() {
        val room = state.room ?: return
        // Everywhere anybody has been put, with the drawing on screen winning: the memory is
        // brought up to date once per refresh, so a drag in the last fifth of a second is only
        // here.
        val placed = LinkedHashMap(whereTheyWere)
        for (icon in room.icons) placed[icon.peerId] = icon
        val sides = sidesTheyCarried.toKeep(room.otherHalfIds, room.icons.map { it.peerId })
        StoredRoomDrawing(filesDir).write(placed.values.toList(), room.copy(otherHalfIds = sides))
    }

    companion object {
        /**
         * Whether this screen is in front, which is whether this app may start an activity at all.
         *
         * Read by [StandbyService], which can obey everything else from the background and has to
         * refuse exactly one thing. A flag rather than asking the system, because what is being
         * asked is "would startActivity work", and the only honest answer to that is the one this
         * screen already knows about itself.
         */
        @Volatile
        var inFront = false
            private set

        /** Fast enough that a state change reads as immediate, slow enough to cost nothing. */
        private const val REFRESH_MILLIS = 200L
        /** Twenty ticks, so charge and heat refresh about every four seconds. */
        private const val HEALTH_EVERY_TICKS = 20
        private const val LOG_TAG = "SoundMeshHome"
        /** As many of an id as every screen in this app has always printed. */
        private const val SHORT_NAME_CHARACTERS = 4
        private const val AUDIO_MIME = "audio/*"
        /** [Preferences] key, "off" once somebody ticked 不再提示 on [CaptureHowTo]. */
        private const val CAPTURE_HOW_TO_KEY = "capture_how_to"
        /** [Preferences] key, "off" once somebody ticked 不再提示 on the fine calibration note. */
        private const val FINE_CALIBRATION_NOTE_KEY = "fine_calibration_note"

        /** Every key [PermissionsScreen] draws a row for - see [refreshPermissionsState]. */
        private val PERMISSIONS_SCREEN_KEYS = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
            PERMISSION_BACKGROUND
        )
    }
}


/**
 * One instruction to one handset: what it was told to be, and when.
 *
 * The instant is half of it. Without it there is no way to ask "has it said anything since", which
 * is the question that separates a handset refusing to move from one that is quietly obeying and
 * not answering - and those two want opposite things said about them on screen.
 */
internal data class Told(val percent: Int, val at: Long)

/**
 * This handset's own IPv4 address on the active network, with the prefix that names it.
 *
 * Asked of [ConnectivityManager] rather than WifiManager: the same read, no permission, and the
 * one place the prefix length is carried. The prefix is the half that matters - the same pair of
 * addresses is one network on a /16 and two on a /24, and a campus or office network is where
 * both of those actually happen.
 *
 * IPv4 only, and null rather than an approximation when there is none: everything downstream is a
 * sentence blaming a network, so having nothing to say is better than saying it about the wrong
 * one.
 */
internal fun localNetOf(connectivity: ConnectivityManager): IpSubnet? = runCatching {
    connectivity.getLinkProperties(connectivity.activeNetwork)
        ?.linkAddresses
        ?.firstOrNull { it.address is Inet4Address }
        ?.let { IpSubnet(it.address.hostAddress ?: return@let null, it.prefixLength) }
}.getOrNull()
