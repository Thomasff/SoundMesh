package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.core.DiscoveryOutcome
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomOrder
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.RoomCommandClient
import java.io.File

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
    val band: String?,
    val shares: String?,
    /**
     * What went wrong: why the session ended when [stage] is [SinkStage.FAILED], or why the last
     * play was not followed when this machine has gone back to standing by.
     */
    val problem: String?
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
 * calibration), and look for the host again if it moves to another address - both written down
 * in now.md as things for later.
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
        commandPort: Int = COMMAND_PORT
    ) = synchronized(lock) {
        if (worker?.isAlive == true) return
        running = true
        failure = null
        problem = null
        hostName = null
        hostAddress = null
        stream = null
        playWanted = false
        worker = Thread({ follow(address, chunkPort, clockPort, commandPort) }, "sink-follow").apply {
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
            stage = stage,
            failure = failure,
            hostName = hostName,
            hostAddress = hostAddress,
            offsetMillis = stream?.offsetNanos()?.let { it / 1_000_000.0 },
            played = stream?.played ?: 0,
            late = stream?.droppedLate ?: 0,
            band = stream?.seamBand(),
            shares = stream?.seamShares(),
            problem = problem
        )
    }

    private fun follow(address: String?, chunkPort: Int, clockPort: Int, commandPort: Int) {
        try {
            val (host, port) = if (address != null) {
                hostName = address
                address to chunkPort
            } else {
                stage = SinkStage.FINDING
                val outcome = discover(DISCOVERY_WINDOW_MILLIS)
                val peer = outcome.peer ?: run {
                    failure = outcome.failure
                    stage = SinkStage.NOT_FOUND
                    return
                }
                hostName = peer.name
                peer.hostAddress to peer.port
            }
            hostAddress = host
            if (!running) return
            standBy(host, port, clockPort, commandPort)
        } catch (_: InterruptedException) {
            // stop() - the stage is its to set.
        } catch (e: Throwable) {
            // Anything else would end this thread with the stage left at whatever step it was on,
            // and the window saying "working on it" for good.
            problem = e.toString()
            stage = SinkStage.FAILED
        }
    }

    private fun standBy(host: String, chunkPort: Int, clockPort: Int, commandPort: Int) {
        // The same name the audio leg dials under, so the host sees one machine and not two.
        val selfId = HostIdentity(identityDirectory).current()
        val line = RoomCommandClient(host, commandPort, selfId, carrying = null, called = called, onCommand = ::obey)
        line.start()
        try {
            // From now rather than zero: nanoTime's origin is arbitrary and may be negative, and the
            // line says it is there on connecting anyway.
            var saidHereAt = System.nanoTime()
            fun beat() {
                val now = System.nanoTime()
                if (now - saidHereAt >= SAY_HERE_EVERY_NANOS && line.sayHere()) saidHereAt = now
            }
            while (running) {
                if (playWanted) {
                    play(host, chunkPort, clockPort, ::beat)
                } else {
                    stage = if (line.connected) SinkStage.STANDING_BY else SinkStage.REACHING
                    beat()
                    Thread.sleep(WATCH_MILLIS)
                }
            }
        } finally {
            line.close()
        }
    }

    private fun obey(order: RoomOrder) {
        when (order.command) {
            RoomCommand.PLAY -> {
                playsSaid++
                playWanted = true
            }
            RoomCommand.STOP -> playWanted = false
            // Measuring needs the calibration this side does not have, and volume is the handset's
            // own system volume; neither is this machine's to act on.
            else -> Unit
        }
    }

    /** One stretch of following, from "play" until "stop" - or until it could not be followed. */
    private fun play(host: String, chunkPort: Int, clockPort: Int, beat: () -> Unit) {
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
            val sink = SinkStream(host, it.output, chunkPort, clockPort, HostIdentity(identityDirectory).current())
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
                watch(sink, playing, beat)
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

    private fun watch(sink: SinkStream, playing: Int, beat: () -> Unit) {
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

        /** Why a play was dropped when the host's clock never answered; the window says it in its own words. */
        const val NO_CLOCK_PROBLEM = "the host's clock never answered"

        private const val WATCH_MILLIS = 200L
        private const val STOP_WAIT_MILLIS = 7_000L
    }
}
