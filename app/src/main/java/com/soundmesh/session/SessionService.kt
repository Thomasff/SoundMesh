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
import android.net.Uri
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.IBinder
import android.util.Log
import com.soundmesh.core.DriftController
import com.soundmesh.core.HostId
import com.soundmesh.core.PeerAdvertisement
import com.soundmesh.probe.PlaybackUsage
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.CaptureChunkSource
import com.soundmesh.probe.sync.FileChunkSource
import com.soundmesh.probe.sync.FolderSongs
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.StreamingChunkSource
import com.soundmesh.probe.sync.PeerDiscovery
import com.soundmesh.probe.sync.StoredOutputLead
import com.soundmesh.probe.sync.SyncActivity
import com.soundmesh.probe.sync.SyncProjectionService
import com.soundmesh.probe.sync.SyncRenderer
import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var advertisement: AutoCloseable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_HOST -> startSession(intent, host = true)
            ACTION_START_SINK -> startSession(intent, host = false)
            ACTION_STOP -> stopSession()
            // On the caller's thread, which is the main one, and deliberately: what it does is set
            // a field and empty two queues. The waiting is the decoder's, on its own thread.
            ACTION_SEEK -> runCatching {
                ACTIVE?.seekTo(intent.getLongExtra(EXTRA_SEEK_MICROS, 0L))
            }.onFailure { Log.e(LOG_TAG, "could not jump", it) }
            // The same shape and the same thread, because it is the same mechanism: a step
            // names a song where a drag names a place, and both end in one field and two
            // emptied queues. A zero step would ask the decoder to reopen the song it is on
            // for nothing, so it is not sent rather than being guarded further down.
            ACTION_STEP_SONG -> runCatching {
                intent.getIntExtra(EXTRA_SONG_STEP, 0).takeIf { it != 0 }?.let { ACTIVE?.stepSong(it) }
            }.onFailure { Log.e(LOG_TAG, "could not change song", it) }
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
        //
        // A capturing host asks for nothing and is told it may emit: see [takesAudioFocus] for
        // why asking is what silenced both handsets.
        val capturing = host && intent.getBooleanExtra(EXTRA_CAPTURE_SOURCE, false)
        session.onAudioFocusChanged(
            if (takesAudioFocus(host, capturing)) requestAudioFocus(session) else true
        )
        watchNetwork(session)
    }

    private fun openHost(intent: Intent): SyncSession {
        if (intent.getBooleanExtra(EXTRA_CAPTURE_SOURCE, false)) return openCapturingHost(intent)
        intent.getStringExtra(EXTRA_SOURCE_FOLDER)?.let {
            val folder = Uri.parse(it)
            // Listed here rather than carried in the intent. Two hundred addresses is a few tens
            // of kilobytes and two thousand is not, and an intent too large for a binder
            // transaction takes the process with it - a crash for owning a big music folder.
            // The names travel beside the addresses rather than being read back off them: a
            // provider's address for a file is not its name, and the order is decided here.
            val songs = FolderSongs.of(this, folder)
            return openStreamingHost(songs.map { Uri.parse(it.uri) }, intent, songs.map { it.name })
        }
        intent.getStringExtra(EXTRA_SOURCE_URI)?.let { return openStreamingHost(listOf(Uri.parse(it)), intent) }
        val name = intent.getStringExtra(EXTRA_SOURCE_FILE)
            ?: throw IllegalArgumentException("missing source file")
        if (!SAFE_SOURCE_FILE.matches(name)) throw IllegalArgumentException("unusable source file name")
        // The ruler, and only the ruler: a file pushed here by name, read as the 60 s prefix every
        // archived report was written against, looping. The product used to arrive here too, with
        // an extra asking for the whole song; it now arrives at the line above with an address
        // instead, so nothing a product feature wants can reach this line any more.
        val source = FileChunkSource.open(File(getExternalFilesDir(null), name))
        advertise()
        return HostSession(
            source::readChunk, deadbandFrames(intent), trimFrames(intent),
            spatialId = HostIdentity(filesDir).current()
        )
    }

    /**
     * The product's host: the song, decoded a piece at a time while it plays.
     *
     * Reading it whole first is what used to put a ceiling on how long a song could be, and the
     * ceiling was memory rather than taste - the whole of it plus its conversion had to fit in
     * what a phone gives one app. Here the decoder runs a fixed three seconds ahead and no
     * further, so length stops being anybody's business but the file's.
     *
     * The prefix path above is untouched and stays that way: it is what every archived alignment
     * measurement was made through, and what a measurement is made against should not change
     * because a product feature landed. It keeps its own extra and its own name check; this one
     * takes an address the system granted and validates nothing, because there is nothing here to
     * validate - the grant is the permission, and a URI this app was not given cannot be opened.
     */
    private fun openStreamingHost(
        songs: List<Uri>,
        intent: Intent,
        names: List<String> = emptyList()
    ): SyncSession {
        val source = StreamingChunkSource.open(this, songs)
        advertise()
        return HostSession(
            // Null is the song having ended, which the session plays out and then acts on.
            readChunk = source::readChunk,
            deadbandFrames = deadbandFrames(intent),
            trimFrames = trimFrames(intent),
            closeSource = source::close,
            lateChunks = source::lateChunks,
            skippedSongs = source::skippedSongs,
            seekSource = source::seekTo,
            stepSongSource = source::stepSong,
            sourcePlayhead = source::playhead,
            // The song is over, so the session is over: the service takes itself down exactly the
            // way the stop button does. Anything less leaves a foreground notification, a bound
            // set of sockets and an open AudioTrack behind a room that has gone quiet.
            onEnded = { stopSession() },
            spatialId = HostIdentity(filesDir).current(),
            songNames = names
        )
    }

    /**
     * A host whose audio is whatever another app on this handset is playing.
     *
     * This is the only answer the product has to "play what I picked in my own music app": there
     * is no file to hand over, and there is not going to be one. Off unless asked for, so a run
     * driven by run-session.mjs still opens the file path every archived report was written
     * against.
     *
     * The projection has to exist already. Consent is an activity's business, and the token it
     * returns may only be turned into a projection by a foreground service of type mediaProjection
     * - both of those happened before this intent was sent. Missing means consent was refused or
     * has since been revoked; a session that started anyway would play silence and say PLAYING.
     *
     * No package is named, so what is captured is every media output except this app's own. A
     * person picks their music app, not ours, and excluding ourselves is what stops the replay
     * from being captured back into itself.
     *
     * Accessibility rather than media, and not a preference: capture only makes sense with the
     * media volume at zero, and a media-usage output is silenced along with it.
     */
    private fun openCapturingHost(intent: Intent): SyncSession {
        val projection = SyncProjectionService.acquired
            ?: throw IllegalStateException("no media projection to capture with")
        val source = CaptureChunkSource.open(this, projection, null) {}
        // Null on a handset nobody has measured, and a run then plays as early as O65 did. Logged
        // rather than refused: the session is still worth having, and silence about it is what let
        // nineteen milliseconds hide behind "a little bit faster, but you can hardly tell".
        val lead = StoredOutputLead(filesDir, CAPTURING_HOST_USAGE).read()
        Log.i(LOG_TAG, "the $CAPTURING_HOST_USAGE output leads media by ${lead ?: "an unmeasured amount"}")
        advertise()
        return HostSession(
            readChunk = { source.readChunk() ?: throw IllegalStateException("capture ended") },
            deadbandFrames = deadbandFrames(intent),
            trimFrames = trimFrames(intent),
            playbackUsage = CAPTURING_HOST_USAGE,
            outputLeadNanos = (lead ?: 0L) * 1_000L,
            closeSource = source::close,
            spatialId = HostIdentity(filesDir).current()
        )
    }

    /**
     * Puts this handset on the network under the name its peers file it by.
     *
     * Not decorative, and not the harness path copied over: a sink that changes network looks for
     * its host again by that name, and a host that never said the name has nothing to be found by.
     * The two halves are one feature, and this is the half that is easy to leave out.
     *
     * A registration that fails is logged and not fatal. It costs the sinks their re-discovery,
     * not their session - they were handed an address to start with and it still works.
     */
    private fun advertise() {
        val hostId = HostIdentity(filesDir).current()
        runCatching { PeerDiscovery(this).register("$SERVICE_NAME_PREFIX-$hostId", SyncActivity.CHUNK_PORT, hostId) }
            .onSuccess { advertisement = it }
            .onFailure { Log.e(LOG_TAG, "could not advertise this host", it) }
    }

    /** The record names an address the handset no longer has. Withdraw it and say the new one. */
    private fun readvertise() {
        if (advertisement == null) return
        withdrawAdvertisement()
        advertise()
    }

    private fun withdrawAdvertisement() {
        val open = advertisement ?: return
        advertisement = null
        runCatching { open.close() }
    }

    private fun openSink(intent: Intent): SyncSession {
        val address = intent.getStringExtra(EXTRA_HOST_ADDRESS)
            ?: throw IllegalArgumentException("missing host address")
        val peerId = intent.getStringExtra(EXTRA_PEER_ID)
        if (!HostId.isValid(peerId)) throw IllegalArgumentException("unusable peer id")
        val port = intent.getIntExtra(EXTRA_CHUNK_PORT, 0)
        if (port !in 1..65535) throw IllegalArgumentException("unusable chunk port")
        return SinkSession(
            address, port, peerId!!, filesDir,
            deadbandFrames(intent), trimFrames(intent),
            resolveHost = { addressOf(peerId) },
            // Down the same path the stop button takes, so a session that ended itself writes its
            // report, gives back the audio focus and takes its notification away exactly as one
            // somebody stopped does. Before this, a host that stopped left this phone holding all
            // three until it was picked up.
            onHostGone = { stopSession() },
            // This handset's own name, not the host's. The same identity a peer files this phone's
            // calibration under, so an icon dragged in one session means the same phone in the next.
            spatialId = HostIdentity(filesDir).current()
        )
    }

    /**
     * Where the named host is on the network this handset is on now, or null.
     *
     * Discovery answers with whatever single host it found, and this refuses everything that is
     * not the one the session was started against. That is the identity check the discovery path
     * was always missing: without it, a handset that switched networks joins whichever session was
     * running on the new one, having been asked to follow a particular phone.
     *
     * Null covers both "nobody answered" and "somebody else did", because the caller does the same
     * thing with either - keeps the address it has and tries again later.
     */
    private fun addressOf(peerId: String): String? {
        val outcome = PeerDiscovery(this).discover(REDISCOVERY_WINDOW_MILLIS)
        // Logged rather than only counted, because the two ways of finding nothing need different
        // fixing and the session's own counters cannot tell them apart: it only records the
        // address changing, and a search that worked perfectly records nothing when the host is
        // where it always was.
        val peer = outcome.peer
        if (peer == null) {
            Log.i(LOG_TAG, "no host to follow: ${outcome.seen} seen, ${outcome.compatible} compatible")
            return null
        }
        if (PeerAdvertisement.hostIdOf(peer) != peerId) {
            Log.i(LOG_TAG, "the search answered with a host this session was not paired to")
            return null
        }
        Log.i(LOG_TAG, "the paired host answered the search")
        return peer.hostAddress
    }

    /**
     * Tells the session when the device changes network, or changes address on the one it is on.
     *
     * Registration itself delivers the current network, so the first reading is kept as the thing
     * later ones are compared against rather than reported as a change - otherwise every session
     * would spend a discovery window before its first connection, looking for a host that had not
     * moved.
     *
     * A lost network raises nothing on its own. There is nothing to search on until a replacement
     * arrives, and when one does it arrives through here.
     */
    private fun watchNetwork(session: SyncSession) {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val seen = AtomicReference<NetworkFootprint?>(null)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val now = footprint(network, properties)
                val before = seen.getAndSet(now)
                Log.i(LOG_TAG, "the network reported ${now.addresses.size} addresses, moved=${before?.movedTo(now)}")
                if (before == null || !before.movedTo(now)) return
                session.onNetworkChanged()
                readvertise()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback; Log.i(LOG_TAG, "watching the network") }
            .onFailure { Log.e(LOG_TAG, "could not watch the network", it) }
    }

    private fun footprint(network: Network, properties: LinkProperties) = NetworkFootprint(
        handle = network.networkHandle,
        addresses = properties.linkAddresses.mapNotNull { it.address.hostAddress }.toSet()
    )

    private fun stopWatchingNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    /**
     * The drift loop's deadband, or the default when nothing asked for another.
     *
     * Absent means default rather than zero, on the same terms every other extra in this project
     * uses: a session started without an opinion has to behave exactly as one started before the
     * extra existed, or an experiment's control arm is not the thing it is being compared against.
     */
    private fun deadbandFrames(intent: Intent): Int {
        val requested = intent.getIntExtra(EXTRA_DEADBAND_FRAMES, DriftController.DEFAULT_DEADBAND_FRAMES)
        return if (requested in 1..MAX_DEADBAND_FRAMES) requested else DriftController.DEFAULT_DEADBAND_FRAMES
    }

    /**
     * The renderer's trim band, on the same absent-means-default terms.
     *
     * Bounded below by one frame rather than zero: a band of zero is the arrangement O38 removed,
     * where every release off the grid by a single frame edited the waveform.
     */
    private fun trimFrames(intent: Intent): Int {
        val requested = intent.getIntExtra(EXTRA_TRIM_FRAMES, PRODUCT_TRIM_FRAMES)
        return if (requested in 1..MAX_TRIM_FRAMES) requested else PRODUCT_TRIM_FRAMES
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
            // Read before the stop, not after: stopping ends the renderer, and the counters this
            // whole file exists to surface are the renderer's. Written to a file because the run
            // that produced them is over by the time anyone asks, and a number nobody can read
            // afterwards is the same as a number nobody counted.
            runCatching { session?.report()?.let { File(filesDir, REPORT_FILE).writeText(it) } }
                .onFailure { Log.e(LOG_TAG, "could not write the session report", it) }
            runCatching { session?.stop() }
            releaseAudioFocus()
            stopWatchingNetwork()
            withdrawAdvertisement()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, "SoundMeshSessionStop").start()
    }

    private fun failed(error: Throwable) {
        FAILURE = error.javaClass.simpleName.ifEmpty { "SESSION_EXCEPTION" }
        releaseAudioFocus()
        stopWatchingNetwork()
        withdrawAdvertisement()
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
        stopWatchingNetwork()
        withdrawAdvertisement()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_HOST = "com.soundmesh.session.START_HOST"
        const val ACTION_START_SINK = "com.soundmesh.session.START_SINK"
        const val ACTION_STOP = "com.soundmesh.session.STOP"

        /**
         * Start playing from somewhere else in what is playing now.
         *
         * An action rather than a bound-service call for the same reason every other one is: the
         * screen that holds the slider and the session that holds the source are in different
         * lifetimes, and the session outlives the screen on purpose.
         */
        const val ACTION_SEEK = "com.soundmesh.session.SEEK"
        const val EXTRA_SEEK_MICROS = "seek_micros"
        const val ACTION_STEP_SONG = "com.soundmesh.session.STEP_SONG"
        const val EXTRA_SONG_STEP = "song_step"
        const val EXTRA_SOURCE_FILE = "source_file"

        /**
         * The song, as the address the system granted this app rather than a name under its own
         * directory. Its presence is what picks the streaming source, so the two file paths are
         * told apart by which extra arrived rather than by a flag beside one of them - a flag the
         * ruler's own path would then have had to keep answering.
         */
        const val EXTRA_SOURCE_URI = "source_uri"

        /**
         * A folder, as the tree address the listener granted. Its songs are read when the session
         * opens rather than when the folder was chosen, so a song added this afternoon plays
         * tonight without anybody choosing anything again.
         */
        const val EXTRA_SOURCE_FOLDER = "source_folder"
        const val EXTRA_HOST_ADDRESS = "host_address"
        const val EXTRA_CHUNK_PORT = "chunk_port"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_DEADBAND_FRAMES = "deadband_frames"
        const val EXTRA_TRIM_FRAMES = "trim_frames"
        const val EXTRA_CAPTURE_SOURCE = "capture_source"

        /**
         * Half a chunk. Past this the loop can no longer correct an error smaller than the chunk
         * period it is correcting within, which is a different design rather than a wider setting.
         */
        const val MAX_DEADBAND_FRAMES = 480

        /**
         * Half a chunk again. A band wider than half the chunk period would let a release sit
         * closer to the next chunk than to its own, which is a different scheme rather than a
         * wider band - and 480 frames is 10 ms, already twice the whole alignment gate.
         */
        const val MAX_TRIM_FRAMES = 480

        /**
         * How long a re-discovery listens for, after the network moved.
         *
         * The harness value. mDNS has no end - nothing says "that was all of them" - so the window
         * is the whole of what makes two answers distinguishable from one, and shortening it here
         * to shorten an outage would trade a correct refusal for a fast wrong host.
         */
        const val REDISCOVERY_WINDOW_MILLIS = 5_000

        /** Where the last session left its counters, under filesDir so run-as can read it. */
        const val REPORT_FILE = "session-report.json"

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

        /** One instance name per handset, so the record is stable across sessions. */
        private const val SERVICE_NAME_PREFIX = "SoundMesh"
        private const val NOTIFICATION_ID = 1002

        // A bare file name. No path separator matches at all, and the first character must be
        // alphanumeric, so the name cannot itself walk out of the directory.
        private val SAFE_SOURCE_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}

/**
 * Enough of a network to say whether the peer's address is still worth dialling.
 *
 * The handle catches a switch between two networks; the addresses catch a lease that changed under
 * one. Either alone misses half of section 11.2's row.
 */
internal data class NetworkFootprint(val handle: Long, val addresses: Set<String>) {
    /**
     * Whether this handset actually went somewhere, rather than merely learning more about where
     * it already was.
     *
     * Gaining an address is not a move. The system delivers link properties more than once while a
     * connection settles - IPv4 first, IPv6 a moment later is ordinary - and reading each of those
     * as a move would spend a discovery window on every session before its first connection.
     * Losing one is a move, because the address a peer was told about may be the one that went.
     */
    fun movedTo(now: NetworkFootprint): Boolean =
        handle != now.handle || !now.addresses.containsAll(addresses)
}
