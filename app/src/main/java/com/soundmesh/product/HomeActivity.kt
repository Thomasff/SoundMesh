package com.soundmesh.product

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
import com.soundmesh.core.CalibrationRole
import com.soundmesh.probe.R
import com.soundmesh.session.CAPTURING_HOST_STREAM
import com.soundmesh.probe.sync.FolderSongs
import com.soundmesh.probe.sync.StreamingChunkSource
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.HostPairingCode
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.ScanActivity
import com.soundmesh.probe.sync.StoredRoomField
import com.soundmesh.probe.sync.StoredListenerDistance
import java.io.File
import com.soundmesh.probe.sync.StoredSeparation
import com.soundmesh.probe.sync.SyncActivity
import com.soundmesh.probe.sync.SyncProjectionService
import com.soundmesh.core.SpatialField
import com.soundmesh.session.HostSession
import com.soundmesh.session.SessionService
import com.soundmesh.session.SyncSession

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
    private var state by mutableStateOf(HomeState())

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
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            readSession()
            // Charge and heat move on the scale of minutes, so they are read every few seconds
            // rather than five times a second alongside the counters.
            if (ticks++ % HEALTH_EVERY_TICKS == 0) {
                state = state.copy(
                    health = DeviceHealth.read(this@HomeActivity),
                    // The volume keys are what move this, so the level changes under the screen
                    // rather than because of it, and has to be re-read to stay true.
                    hostOutputVolume = if (state.capturing) hostOutputVolume.read() else null
                )
            }
            handler.postDelayed(this, REFRESH_MILLIS)
        }
    }

    private val chooseSong = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) adopt(ChosenKind.SONG, uri)
    }

    /**
     * A folder, granted once and read again every time play is pressed.
     *
     * A tree rather than a multi-select of files, because what a person means by "play this album"
     * is the folder and not the twelve files that were in it this afternoon.
     */
    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) adopt(ChosenKind.FOLDER, uri)
    }

    // Asked for rather than required. A session runs either way; without it the ongoing
    // notification is invisible, which is a phone playing music with nothing on screen to say so.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
            state = state.copy(capturing = false, problem = R.string.capture_declined)
            return@registerForActivityResult
        }
        SyncProjectionService.pending = { projection ->
            state = if (projection == null) {
                state.copy(capturing = false, problem = R.string.capture_declined)
            } else {
                state.copy(capturing = true, problem = null, hostOutputVolume = hostOutputVolume.read())
                    .also { handler.post(::aimVolumeKeys) }
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
        // Meant to be put down on a table and looked at, like every other screen in this app.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface { HomeScreen(state, actions) }
            }
        }
    }

    private val actions = HomeActions(
        pickRole = { role -> state = state.copy(role = role, problem = null); readPairing() },
        chooseSong = { releaseProjection(); chooseSong.launch(arrayOf(AUDIO_MIME)) },
        chooseFolder = { releaseProjection(); chooseFolder.launch(null) },
        captureAudio = ::captureAudio,
        scan = { startActivity(Intent(this, ScanActivity::class.java)) },
        play = ::play,
        stop = { awaitingSession = false; startService(request(SessionService.ACTION_STOP)) },
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
        pairCalibrate = {
            startActivity(
                Intent(this, PeerCalibrateActivity::class.java)
                    .putExtra("role", state.role.takeIf { it != Role.NONE }?.name)
            )
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

    /**
     * Null while there is nothing to draw; throws for a room that could not exist.
     *
     * Split out so the net above covers the whole rule rather than only the drawing. It used to
     * cover the drawing alone, which was enough while the drawing held everything that could be
     * refused - and stopped being enough the moment the rule started naming handsets too.
     */
    private fun fieldOf(room: RoomState): SpatialField? {
        val layout = SpatialRoom.layoutOf(room.icons) ?: return null
        return SpatialField(
            room.mode,
            layout,
            pan = room.pan.toDouble().coerceIn(-1.0, 1.0),
            separation = room.separation.toDouble().coerceIn(0.0, 1.0),
            splitAxis = room.splitAxis,
            crossoverHz = room.crossoverHz.toDouble()
                .coerceIn(SpatialField.LOWEST_CROSSOVER_HZ, SpatialField.HIGHEST_CROSSOVER_HZ),
            otherHalfIds = room.otherHalfIds,
            // Zeroed rather than carried with a flag beside it: no scale is exactly what a room
            // that never measured its listener sends, so the switch off and the feature absent
            // are the same message on the wire and the same code on every handset.
            metresPerUnit = if (room.delayCompensation) room.metresPerUnit else 0.0
        )
    }

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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        askProjection.launch(projectionManager().createScreenCaptureIntent())
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
            pairingPayload = HostPairingCode.of(HostIdentity(filesDir).current(), SyncActivity.CHUNK_PORT),
            songName = chosen?.name,
            songUri = chosen?.uri,
            songIsFolder = chosen?.kind == ChosenKind.FOLDER
        )
    }

    private fun readSession() {
        val session = SessionService.ACTIVE
        if (session != null) awaitingSession = false
        val room = readRoom(session)
        state = state.copy(
            running = session != null,
            sessionState = session?.state(),
            playhead = session?.playhead(),
            nowPlaying = session?.nowPlaying(),
            paused = session?.paused() == true,
            failure = if (awaitingSession) SessionService.FAILURE else null,
            counters = SessionReadout.counters(session?.report()),
            room = room,
            // Two different places for one reading, because a host holds the whole table and a
            // sink is told only the line about itself. Both read null before a room exists,
            // which is the number on screen with no colour beside it.
            selfPlace = session?.badgePlace() ?: room?.colours?.get(state.selfId)
        )
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
        val host = session as? HostSession ?: return null
        val roster = host.roomPeerIds()
        val previous = state.room ?: RoomState(selfId = roster.firstOrNull())
        val icons = SpatialRoom.reconciled(previous.icons, roster)
        // The host is dropped first because it is in its own room and is not sent its own audio.
        val audio = host.audioPeerIds()
        val silent = roster.drop(1).filterNot { it in audio }.toSet()
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
            // A fresh reading is a fresh reason to offer, whatever was done with the last one.
            fitted = false,
            // And the scale goes with it: it came out of a fit of the previous numbers, and a
            // stale one delays the right handset by the wrong amount rather than not at all.
            metresPerUnit = 0.0,
            colours = host.roomPlaces(),
            silentIds = silent,
            otherHalfIds = SpatialRoom.reconciledOtherHalf(previous.otherHalfIds, icons.map { it.peerId })
        ).also(::publish)
    }

    override fun onResume() {
        super.onResume()
        // Re-read rather than kept: a scan happens in another activity, and this is where its
        // result arrives. The pairing code is re-encoded for the same reason ShowCodeActivity does
        // it - a handset that changed network is otherwise showing an address it no longer has.
        readPairing()
        rereadDistances()
        handler.post(refresh)
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

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private companion object {
        /** Fast enough that a state change reads as immediate, slow enough to cost nothing. */
        const val REFRESH_MILLIS = 200L
        /** Twenty ticks, so charge and heat refresh about every four seconds. */
        const val HEALTH_EVERY_TICKS = 20
        const val LOG_TAG = "SoundMeshHome"
        const val AUDIO_MIME = "audio/*"
    }
}

/**
 * Every distance this handset holds about a room, keyed by the two handsets it is between.
 *
 * Two files behind it, one fact each. A distance [self] is an end of comes from the per-peer
 * file, which every arm that measures a distance writes and which is therefore the freshest
 * thing there is about that pair. A distance between two other handsets can only have come from
 * a room measuring the lot in one window, and has nowhere else it could live.
 *
 * Both files can hold the same pair - a room measures the ones this handset is in no
 * differently - and neither records when it was written, so which one answers has to be
 * decided here rather than by whichever is read second. The per-peer file answers: it is the
 * one every arm that measures a distance writes, the room included, so for a pair they share
 * it cannot be the staler of the two. Which is why the room is read first and written over,
 * and not the other way round.
 *
 * File scope so it can be judged on what it produces. Inside the screen it would be reachable
 * only by standing in a room with three phones in it.
 */
internal fun measuredDistances(
    directory: File,
    self: String?,
    room: List<String>
): Map<Pair<String, String>, Double> {
    val distances = LinkedHashMap<Pair<String, String>, Double>()
    for (entry in StoredRoomField(directory).read()) distances[entry.key] = entry.value
    if (self == null) return distances
    for (peerId in room) {
        if (peerId == self) continue
        val metres = StoredSeparation(directory, peerId).read() ?: continue
        distances[self to peerId] = metres
        distances[peerId to self] = metres
    }
    return distances
}
