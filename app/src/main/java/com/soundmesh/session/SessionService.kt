package com.soundmesh.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import com.soundmesh.core.HostId
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.FileChunkSource
import java.io.File

/**
 * Keeps a session alive and holds the audio focus for it.
 *
 * Both jobs are the system's requirements rather than the product's. Android stops an app's
 * playback when it leaves the foreground unless a `mediaPlayback` service says otherwise, and the
 * design's section 11.2 names that as the reason a sink needs one at all - the host already had a
 * foreground service for capture, and the asymmetry was never a design decision. The audio focus
 * is here for the same reason it is not in the session: the focus belongs to the app, one session
 * at a time uses it, and the session's job is to be told.
 *
 * A permanent focus loss stops the session. A transient one does not: the session stays wired up
 * so that whatever comes back lands on the shared timeline instead of re-converging in silence.
 */
class SessionService : Service() {
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_HOST -> startSession(intent, host = true)
            ACTION_START_SINK -> startSession(intent, host = false)
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent, host: Boolean) {
        if (ACTIVE != null) return
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // Opening a source decodes, and connecting waits on another handset. Neither belongs on the
        // thread the system delivered this intent on.
        Thread({ open(intent, host) }, "SoundMeshSessionStart").start()
    }

    private fun open(intent: Intent, host: Boolean) {
        val session = try {
            if (host) openHost(intent) else openSink(intent)
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "could not start a session", error)
            failed(error)
            return
        }
        ACTIVE = session
        try {
            session.start()
        } catch (error: Throwable) {
            // Inside the try for the same reason opening is: start reaches the network, and a
            // handset whose peer is not ready yet is an ordinary Tuesday, not a reason to take the
            // process down. It did exactly that on hardware - one refused connection, one dead app.
            Log.e(LOG_TAG, "a session failed while starting", error)
            ACTIVE = null
            runCatching { session.stop() }
            failed(error)
            return
        }
        // Requested only once the session exists to be told the answer. The system replies
        // synchronously, and a reply that arrived first would have nowhere to go.
        session.onAudioFocusChanged(requestAudioFocus(session))
    }

    private fun openHost(intent: Intent): SyncSession {
        val name = intent.getStringExtra(EXTRA_SOURCE_FILE)
            ?: throw IllegalArgumentException("missing source file")
        if (!SAFE_SOURCE_FILE.matches(name)) throw IllegalArgumentException("unusable source file name")
        val source = FileChunkSource.open(File(getExternalFilesDir(null), name))
        return HostSession(source::readChunk)
    }

    private fun openSink(intent: Intent): SyncSession {
        val address = intent.getStringExtra(EXTRA_HOST_ADDRESS)
            ?: throw IllegalArgumentException("missing host address")
        val peerId = intent.getStringExtra(EXTRA_PEER_ID)
        if (!HostId.isValid(peerId)) throw IllegalArgumentException("unusable peer id")
        val port = intent.getIntExtra(EXTRA_CHUNK_PORT, 0)
        if (port !in 1..65535) throw IllegalArgumentException("unusable chunk port")
        return SinkSession(address, port, peerId!!, filesDir)
    }

    /**
     * Asks for the focus and reports whether it was granted.
     *
     * `setWillPauseWhenDucked(true)` on purpose: ducking would leave this handset playing quietly
     * against another handset playing loudly, which is the one failure a listener in the room
     * cannot mistake for anything else. Treating both interruptions the same way also means there
     * is one resumption path rather than two.
     */
    private fun requestAudioFocus(session: SyncSession): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change -> onFocusChange(session, change) }
            .build()
        audioFocusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun onFocusChange(session: SyncSession, change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> session.onAudioFocusChanged(true)
            // Permanent. Another app owns the output now, and nothing will hand it back.
            AudioManager.AUDIOFOCUS_LOSS -> {
                session.onAudioFocusChanged(false)
                stopSession()
            }
            else -> session.onAudioFocusChanged(false)
        }
    }

    private fun stopSession() {
        val session = ACTIVE
        ACTIVE = null
        Thread({
            runCatching { session?.stop() }
            releaseAudioFocus()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, "SoundMeshSessionStop").start()
    }

    private fun failed(error: Throwable) {
        FAILURE = error.javaClass.simpleName.ifEmpty { "SESSION_EXCEPTION" }
        releaseAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseAudioFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.session_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.session_notification))
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        ACTIVE?.let { runCatching { it.stop() } }
        ACTIVE = null
        releaseAudioFocus()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_HOST = "com.soundmesh.session.START_HOST"
        const val ACTION_START_SINK = "com.soundmesh.session.START_SINK"
        const val ACTION_STOP = "com.soundmesh.session.STOP"
        const val EXTRA_SOURCE_FILE = "source_file"
        const val EXTRA_HOST_ADDRESS = "host_address"
        const val EXTRA_CHUNK_PORT = "chunk_port"
        const val EXTRA_PEER_ID = "peer_id"

        /**
         * The running session, for whatever is showing its state.
         *
         * A static rather than a binding because there is only ever one, and because what reads it
         * asks a question - what state is this in - rather than holding a conversation.
         */
        @Volatile
        var ACTIVE: SyncSession? = null
            private set

        /** Why the last start attempt produced no session, or null if none has failed. */
        @Volatile
        var FAILURE: String? = null
            private set

        private const val LOG_TAG = "SoundMeshSession"
        private const val CHANNEL_ID = "soundmesh_session"
        private const val NOTIFICATION_ID = 1002

        // A bare file name. No path separator matches at all, and the first character must be
        // alphanumeric, so the name cannot itself walk out of the directory.
        private val SAFE_SOURCE_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}
