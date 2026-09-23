package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.core.DiscoveryOutcome
import com.soundmesh.core.HostRepoint
import com.soundmesh.core.PairingCode
import com.soundmesh.core.PeerAdvertisement
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomOrder
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.RoomCommandClient
import com.soundmesh.product.RoomState
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

enum class SinkStage {
    IDLE, FINDING, NOT_FOUND,

    /** The host is known but its command port has not answered yet; dialled again every few seconds. */
    REACHING,

    /** On the host's list and waiting for it to play. */
    STANDING_BY,

    OPENING_SPEAKERS, SYNCING, PLAYING, HOST_SILENT, FAILED
}

data class SinkStatus(
    val stage: SinkStage,
    /** Why nothing was found, when [stage] is [SinkStage.NOT_FOUND]. */
    val failure: DiscoveryFailure?,
    val hostName: String?,
    val hostAddress: String?,
    val offsetMillis: Double?,
    val played: Int,
    val late: Int,
    /** Chunks a handset host's spatial rule changed; zero means its room never reached this machine. */
    val shaped: Int,
    val band: String?,
    val shares: String?,
    /**
     * What went wrong: why the session ended when [stage] is [SinkStage.FAILED], or why the last
     * play was not followed when this machine has gone back to standing by.
     */
    val problem: String?,
    /** This machine's id, whose badge number is how the host's roster names it. */
    val selfId: String,
    /** Its colour's place as the host last said, or null before a host has said one. */
    val selfPlace: Int?,
    /** SoundMesh's own volume here, 0 to 100, as the host last set it. */
    val volumePercent: Int,
    /** The host's room as its newest rule draws it, while following; null before a rule arrives. */
    val room: RoomState?
)

/**
 * This machine following a host until told to stop, the way the window drives it.
 *
 * **It stands by the way a handset does.** Once the host is found this holds its command port
 * open under this machine's own name - which is what puts it on the host's list - and says it is
 * still there every two seconds. The host saying "play" is what opens the speakers, settles the
 * clock and dials the audio; "stop" closes them again and goes back to waiting. Until 09-23 this
 * dialled the audio straight away and nothing else: it played along, but the host's screen said
 * nobody was there, and a handset host - which closes its audio port on every stop - left it
 * silent until somebody pressed stop and start here.
 *
 * The playing half is the command-line sink's steps and refusals, run on this session's thread
 * and reported as a stage. What it does not do: take part in a room measurement (this side has no
 * calibration) - written down in now.md as a thing for later.
 *
 * **A host it found is looked for again when it cannot be reached**, as a handset does: five
 * seconds with the line down and it spends a discovery window, and a host that turned up at another
 * address - a new lease, another network - is followed there. Until 09-23 this dialled the old
 * address for ever, and only stop and start here brought it back.
 */
class SinkSession(
    private val identityDirectory: File,
    private val openSpeakers: () -> Speakers = Speakers::open,
    private val discover: (Int) -> DiscoveryOutcome = PeerDiscovery::discover,
    private val clockWaitMillis: Long = CLOCK_WAIT_MILLIS,
    private val silentAfterNanos: Long = SILENT_AFTER_NANOS,
    /** What to call this machine on the host's list. */
    private val called: String? = System.getenv("COMPUTERNAME")
) {
    private val lock = Any()
    private var worker: Thread? = null

    @Volatile private var running = false
    @Volatile private var stage = SinkStage.IDLE
    @Volatile private var failure: DiscoveryFailure? = null
    @Volatile private var hostName: String? = null
    @Volatile private var hostAddress: String? = null
    @Volatile private var problem: String? = null
    @Volatile private var stream: SinkStream? = null
    @Volatile private var line: RoomCommandClient? = null

    // Read once: status is asked twice a second, and the name on disk does not change under it.
    private val selfId by lazy { HostIdentity(identityDirectory).current() }

    // Across starts and stops, as a handset's system volume is: a room turned down stays down.
    private val volume = SoftwareVolume()

    // What the host last said, set on the command line's thread and acted on by the worker's. A
    // count of plays rather than a flag, because a handset host says "play" again to anybody who
    // stands by mid-song, and a repeat must not restart a stream that is fine - while a play that
    // arrives after the sound has gone must.
    @Volatile private var playWanted = false
    @Volatile private var playsSaid = 0

    /** Starts following [address], or whichever single host discovery finds when it is null. */
    fun start(
        address: String? = null,
        chunkPort: Int = ChunkCodec.DEFAULT_PORT,
        clockPort: Int = ClockPacket.DEFAULT_PORT,
        commandPort: Int = COMMAND_PORT,
        spatialPort: Int = SinkStream.SPATIAL_PORT
    ) = synchronized(lock) {
        if (worker?.isAlive == true) return
        running = true
        failure = null
        problem = null
        hostName = null
        hostAddress = null
        stream = null
        // The last host's colours are its own; the next one settles them again.
        line = null
        playWanted = false
        worker = Thread({ follow(address, chunkPort, clockPort, commandPort, spatialPort) }, "sink-follow").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() = synchronized(lock) {
        running = false
        worker?.interrupt()
        // Discovery is the one step that cannot be interrupted, and it is five seconds long.
        worker?.join(STOP_WAIT_MILLIS)
        worker = null
        stage = SinkStage.IDLE
    }

    fun status(): SinkStatus {
        val stream = stream
        return SinkStatus(
            selfId = selfId,
            selfPlace = line?.places?.get(selfId),
            volumePercent = volume.percent,
            room = stream?.rule?.let { drawnRoomOf(it, stream.badges, selfId) },
            stage = stage,
            failure = failure,
            hostName = hostName,
            hostAddress = hostAddress,
            offsetMillis = stream?.offsetNanos()?.let { it / 1_000_000.0 },
            played = stream?.played ?: 0,
            late = stream?.droppedLate ?: 0,
            shaped = stream?.shaped ?: 0,
            band = stream?.seamBand(),
            shares = stream?.seamShares(),
            problem = problem
        )
    }

    private fun follow(address: String?, chunkPort: Int, clockPort: Int, commandPort: Int, spatialPort: Int) {
        try {
            var host: String
            var port: Int
            // Only a host that was found can be looked for again: one given by address has no
            // identity to recognise it by somewhere else.
            var stored: PairingCode? = null
            if (address != null) {
                hostName = address
                host = address
                port = chunkPort
            } else {
                stage = SinkStage.FINDING
                val outcome = discover(DISCOVERY_WINDOW_MILLIS)
                val peer = outcome.peer ?: run {
                    failure = outcome.failure
                    stage = SinkStage.NOT_FOUND
                    return
                }
                hostName = peer.name
                stored = PairingCode(PeerAdvertisement.hostIdOf(peer), peer.hostAddress, peer.port)
                host = peer.hostAddress
                port = peer.port
            }
            while (running) {
                hostAddress = host
                val moved = standBy(host, port, clockPort, commandPort, spatialPort, stored) ?: return
                stored = moved
                host = moved.address
                port = moved.chunkPort
            }
        } catch (_: InterruptedException) {
            // stop() - the stage is its to set.
        } catch (e: Throwable) {
            // Anything else would end this thread with the stage left at whatever step it was on,
            // and the window saying "working on it" for good.
            problem = e.toString()
            stage = SinkStage.FAILED
        }
    }

    /**
     * Holds the host's command line until stopped - null - or until the host turns up somewhere
     * else, when it returns where. [stored] is what that is judged against; without one this only
     * ever holds the line.
     */
    private fun standBy(
        host: String,
        chunkPort: Int,
        clockPort: Int,
        commandPort: Int,
        spatialPort: Int,
        stored: PairingCode?
    ): PairingCode? {
        // The same name the audio leg dials under ([selfId]), so the host sees one machine and not two.
        val line = RoomCommandClient(host, commandPort, selfId, carrying = null, called = called, onCommand = ::obey)
        this.line = line
        line.start()
        try {
            // From now rather than zero: nanoTime's origin is arbitrary and may be negative, and the
            // line says it is there on connecting anyway.
            var saidHereAt = System.nanoTime()
            var lastWent = true
            // What the host was last told this machine's volume is, or null when it has not been
            // told on this line - a line that drops and comes back is a host to tell again.
            var saidVolume: Int? = null
            fun beat() {
                if (!line.connected) {
                    saidVolume = null
                } else if (saidVolume != volume.percent) {
                    val percent = volume.percent
                    // Recorded only when it went out, for the reason RoomCommandClient.sayVolume gives.
                    if (line.sayVolume(percent, SoftwareVolume.FULL, VOLUME_STREAM)) saidVolume = percent
                }
                val now = System.nanoTime()
                if (now - saidHereAt < SAY_HERE_EVERY_NANOS) return
                lastWent = line.sayHere()
                if (lastWent) saidHereAt = now
            }
            // Down means not carrying, as the handset reads it (StandbyService.carrying): a line
            // whose far end left the network stays open, so "connected" alone would never start
            // this clock in the one case it exists for.
            var downSince: Long? = null
            fun lost(): Boolean {
                val now = System.nanoTime()
                if (line.connected && lastWent) {
                    downSince = null
                    return false
                }
                val since = downSince ?: now.also { downSince = it }
                return stored != null && now - since >= STALE_AFTER_NANOS
            }
            var lookedAt: Long? = null
            while (running) {
                if (playWanted) {
                    play(host, chunkPort, clockPort, spatialPort, ::beat, ::lost)
                } else {
                    stage = if (line.connected) SinkStage.STANDING_BY else SinkStage.REACHING
                    beat()
                    val now = System.nanoTime()
                    if (lost() && lookedAt.let { it == null || now - it >= LOOK_GAP_NANOS }) {
                        lookedAt = now
                        // The other half of the same fault, as on the handset: an address that is
                        // still right behind a socket that is dead is fixed by dialling again.
                        line.dialAgain()
                        lookAgain(stored!!, commandPort, selfId)?.let { return it }
                    }
                    Thread.sleep(WATCH_MILLIS)
                }
            }
            return null
        } finally {
            line.close()
        }
    }

    /**
     * One look for a host this machine cannot reach, decided the way a handset decides it
     * ([HostRepoint.ofUnreachable]): the same host somewhere else is followed, a different one only
     * when the stored one is nowhere and exactly one other is here. Null for staying put.
     */
    private fun lookAgain(stored: PairingCode, commandPort: Int, selfId: String): PairingCode? {
        stage = SinkStage.FINDING
        // Its own record out first, for HostSearch.lookAgain's reason: a machine that has just
        // stopped hosting goes on being answered for, and would count as the one other host.
        val others = discover(DISCOVERY_WINDOW_MILLIS).hosts.filter { PeerAdvertisement.hostIdOf(it) != selfId }
        val moved = HostRepoint.ofUnreachable(stored, others) { serving(it.address, commandPort) }.host
            ?: return null
        hostName = others.firstOrNull { PeerAdvertisement.hostIdOf(it) == moved.hostId }?.name ?: moved.address
        return moved
    }

    /** RoomCommands.stillServing, asked on this session's command port rather than the fixed one. */
    private fun serving(address: String, commandPort: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(address, commandPort), SERVING_TIMEOUT_MILLIS) }
        true
    }.getOrDefault(false)

    private fun obey(order: RoomOrder) {
        when (order.command) {
            RoomCommand.PLAY -> {
                playsSaid++
                playWanted = true
            }
            RoomCommand.STOP -> playWanted = false
            // SoundMesh's own sound only, not the computer's system volume - see [SoftwareVolume].
            // Said back to the host from the stand-by loop, as a handset says its own.
            RoomCommand.SET_VOLUME -> order.value?.let { volume.set(it) }
            RoomCommand.RESTORE_VOLUME -> volume.restore()
            // Measuring needs the calibration this side does not have.
            else -> Unit
        }
    }

    /** One stretch of following, from "play" until "stop" - or until it could not be followed. */
    private fun play(host: String, chunkPort: Int, clockPort: Int, spatialPort: Int, beat: () -> Unit, lost: () -> Boolean) {
        val playing = playsSaid
        problem = null
        stage = SinkStage.OPENING_SPEAKERS
        val speakers = try {
            openSpeakers()
        } catch (e: Exception) {
            return giveUp(e.message ?: e.toString())
        }
        speakers.use {
            // Named, so a handset host can tell this machine coming back from a second machine arriving.
            val sink = SinkStream(host, GainOutput(it.output, volume), chunkPort, clockPort, HostIdentity(identityDirectory).current(), spatialPort)
            stream = sink
            try {
                stage = SinkStage.SYNCING
                sink.startClock()
                if (!sink.awaitClock(clockWaitMillis) { running && playWanted }) {
                    if (running && playWanted) giveUp(NO_CLOCK_PROBLEM)
                    return
                }
                try {
                    sink.dial()
                } catch (e: java.io.IOException) {
                    return giveUp(e.message ?: e.toString())
                }
                watch(sink, playing, beat, lost)
            } finally {
                sink.stop()
            }
        }
    }

    /** Back to standing by, with the reason kept for the screen until the next play. */
    private fun giveUp(why: String) {
        problem = why
        playWanted = false
    }

    private fun watch(sink: SinkStream, playing: Int, beat: () -> Unit, lost: () -> Boolean) {
        // Counted from zero, not from a sentinel: until a first chunk arrives nothing has been
        // heard from the host, and that is not "playing" - the stage stays where dialling left it
        // until the audio is really coming, or the wait runs out and the host is said to be quiet.
        var seen = 0
        var changedAt = System.nanoTime()
        while (running && playWanted) {
            val now = System.nanoTime()
            val arrived = sink.arrived
            if (arrived != seen) {
                seen = arrived
                changedAt = now
            }
            val silent = now - changedAt > silentAfterNanos
            // A play said since this stretch began, while nothing is arriving: the host has started
            // again on an audio socket this stream is no longer on. Start over rather than wait.
            if (silent && playsSaid != playing) return
            // Nothing arriving and no line to the host either: it has gone, or gone somewhere else.
            // Back to standing by, which is where it is looked for.
            if (silent && lost()) {
                playWanted = false
                return
            }
            stage = when {
                silent -> SinkStage.HOST_SILENT
                arrived == 0 -> SinkStage.SYNCING
                else -> SinkStage.PLAYING
            }
            beat()
            Thread.sleep(WATCH_MILLIS)
        }
    }

    companion object {
        /** The handset sink's window, for the reason it gives: mDNS never says that was all of them. */
        const val DISCOVERY_WINDOW_MILLIS = 5_000

        /** The command-line sink's wait, which has been enough on every link measured so far. */
        const val CLOCK_WAIT_MILLIS = 30_000L

        /**
         * How long without a chunk before the host is said to have gone quiet. The handset sink
         * calls its link down at 800 ms; a person reading a window needs it to not flicker.
         */
        const val SILENT_AFTER_NANOS = 3_000_000_000L

        /**
         * The handset's cadence (StandbyService.SAY_HERE_EVERY_MILLIS): four of these fit in the
         * window a host waits before calling a standing sink quiet.
         */
        private const val SAY_HERE_EVERY_NANOS = 2_000_000_000L

        /** The handset's wait (HostSearch.STALE_AFTER_MILLIS) before a line that is down sends it looking. */
        private const val STALE_AFTER_NANOS = 5_000_000_000L

        /** The handset's gap between looks (HostSearch.GAP_MILLIS): each one is a whole discovery window. */
        private const val LOOK_GAP_NANOS = 10_000_000_000L

        /** RoomCommands' own wait: a host accepts or refuses at once, and longer is a machine that left. */
        private const val SERVING_TIMEOUT_MILLIS = 900

        /**
         * What this machine calls the volume it reports, where a handset names its audio stream.
         * Nothing reads it but a person looking at a log.
         */
        private const val VOLUME_STREAM = "soundmesh"

        /** Why a play was dropped when the host's clock never answered; the window says it in its own words. */
        const val NO_CLOCK_PROBLEM = "the host's clock never answered"

        private const val WATCH_MILLIS = 200L
        private const val STOP_WAIT_MILLIS = 7_000L
    }
}
