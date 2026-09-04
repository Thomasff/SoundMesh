package com.soundmesh.product

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.FileChunkSource
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.HostPairingCode
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.ScanActivity
import com.soundmesh.probe.sync.SyncActivity
import com.soundmesh.probe.sync.SyncProjectionService
import com.soundmesh.session.SessionService

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
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            readSession()
            // Charge and heat move on the scale of minutes, so they are read every few seconds
            // rather than five times a second alongside the counters.
            if (ticks++ % HEALTH_EVERY_TICKS == 0) state = state.copy(health = DeviceHealth.read(this@HomeActivity))
            handler.postDelayed(this, REFRESH_MILLIS)
        }
    }

    private val chooseSong = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) adopt(uri)
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
                state.copy(capturing = true, problem = null)
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
        captureAudio = ::captureAudio,
        scan = { startActivity(Intent(this, ScanActivity::class.java)) },
        play = ::play,
        stop = { awaitingSession = false; startService(request(SessionService.ACTION_STOP)) }
    )

    /**
     * Copies what was picked into this app's own directory, then decodes it once to find out
     * whether it can be played at all.
     *
     * Copying rather than handing the service a content Uri: the service takes a bare file name
     * under the external files directory and [FileChunkSource] takes a File, and both of them are
     * shared with the harness that every alignment measurement was taken through. One copy of a few
     * megabytes buys the product the same already-measured path instead of a second one.
     *
     * Decoding here rather than at play time is the other half: a refusal found when the service
     * fails to start would put the reason three layers away from the moment a person chose the
     * file. It also runs the whole conversion, which is the slow part and the reason this thread
     * exists - what it cost is logged, because the wait is a person's to sit through.
     */
    private fun adopt(uri: Uri) {
        state = state.copy(checking = true, problem = null, songName = null)
        Thread({
            val chosen = ChosenSource(getExternalFilesDir(null) ?: filesDir)
            val outcome = runCatching {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "no stream" }
                    chosen.file().outputStream().use { input.copyTo(it) }
                }
                val started = System.nanoTime()
                // The whole song, the same way the session will read it: what this is verifying is
                // that this file plays, and a check that only ever read the first minute would
                // pass a song that is refused for its length or breaks in its second half.
                FileChunkSource.openWhole(chosen.file())
                Log.i(LOG_TAG, "the chosen song was read in ${(System.nanoTime() - started) / 1_000_000} ms")
                displayName(uri)
            }
            handler.post {
                outcome
                    .onSuccess { name ->
                        chosen.remember(name)
                        state = state.copy(checking = false, songName = name, problem = null)
                    }
                    .onFailure { error ->
                        Log.i(LOG_TAG, "the chosen song was refused", error)
                        chosen.forget()
                        state = state.copy(checking = false, songName = null, problem = SourceRejection.of(error.message))
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
        state = state.copy(capturing = false)
        startService(Intent(this, SyncProjectionService::class.java).setAction(SyncProjectionService.ACTION_RELEASE))
    }

    /** What the picker's provider calls the file, or a fallback rather than an empty line. */
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
                    .putExtra(SessionService.EXTRA_SOURCE_FILE, ChosenSource.FILE_NAME)
                    .putExtra(SessionService.EXTRA_WHOLE_SOURCE, true)
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
        state = state.copy(
            paired = PairedHost(filesDir).read(),
            pairingPayload = HostPairingCode.of(HostIdentity(filesDir).current(), SyncActivity.CHUNK_PORT),
            songName = ChosenSource(getExternalFilesDir(null) ?: filesDir).name()
        )
    }

    private fun readSession() {
        val session = SessionService.ACTIVE
        if (session != null) awaitingSession = false
        state = state.copy(
            running = session != null,
            sessionState = session?.state(),
            failure = if (awaitingSession) SessionService.FAILURE else null,
            counters = SessionReadout.counters(session?.report())
        )
    }

    override fun onResume() {
        super.onResume()
        // Re-read rather than kept: a scan happens in another activity, and this is where its
        // result arrives. The pairing code is re-encoded for the same reason ShowCodeActivity does
        // it - a handset that changed network is otherwise showing an address it no longer has.
        readPairing()
        handler.post(refresh)
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
