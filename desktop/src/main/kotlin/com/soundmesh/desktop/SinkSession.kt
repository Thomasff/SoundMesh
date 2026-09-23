package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.core.DiscoveryOutcome
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.HostIdentity
import java.io.File

enum class SinkStage { IDLE, FINDING, NOT_FOUND, OPENING_SPEAKERS, SYNCING, NO_CLOCK, PLAYING, HOST_SILENT, FAILED }

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
    /** What went wrong, when [stage] is [SinkStage.FAILED]. */
    val problem: String?
)

/**
 * This machine following a host until told to stop, the way the window drives it.
 *
 * The same steps as the command-line sink - look, open the output, settle the clock, dial - and
 * the same refusals, run on a thread of its own and reported as a stage instead of printed. It
 * does not stand by on a command port: it follows a host that is already playing, and a host
 * that goes quiet is said rather than waited out.
 */
class SinkSession(
    private val identityDirectory: File,
    private val openSpeakers: () -> Speakers = Speakers::open,
    private val discover: (Int) -> DiscoveryOutcome = PeerDiscovery::discover,
    private val clockWaitMillis: Long = CLOCK_WAIT_MILLIS,
    private val silentAfterNanos: Long = SILENT_AFTER_NANOS
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

    /** Starts following [address], or whichever single host discovery finds when it is null. */
    fun start(
        address: String? = null,
        chunkPort: Int = ChunkCodec.DEFAULT_PORT,
        clockPort: Int = ClockPacket.DEFAULT_PORT
    ) = synchronized(lock) {
        if (worker?.isAlive == true) return
        running = true
        failure = null
        problem = null
        hostName = null
        hostAddress = null
        stream = null
        worker = Thread({ follow(address, chunkPort, clockPort) }, "sink-follow").apply {
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

    private fun follow(address: String?, chunkPort: Int, clockPort: Int) {
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

            stage = SinkStage.OPENING_SPEAKERS
            val speakers = try {
                openSpeakers()
            } catch (e: Exception) {
                problem = e.message ?: e.toString()
                stage = SinkStage.FAILED
                return
            }
            speakers.use { play(it, host, port, clockPort) }
        } catch (_: InterruptedException) {
            // stop() - the stage is its to set.
        } catch (e: Throwable) {
            // Anything else would end this thread with the stage left at whatever step it was on,
            // and the window saying "working on it" for good.
            problem = e.toString()
            stage = SinkStage.FAILED
        }
    }

    private fun play(speakers: Speakers, host: String, chunkPort: Int, clockPort: Int) {
        // Named, so a handset host can tell this machine coming back from a second machine arriving.
        val sink = SinkStream(host, speakers.output, chunkPort, clockPort, HostIdentity(identityDirectory).current())
        stream = sink
        try {
            stage = SinkStage.SYNCING
            sink.startClock()
            if (!sink.awaitClock(clockWaitMillis)) {
                stage = SinkStage.NO_CLOCK
                return
            }
            try {
                sink.dial()
            } catch (e: java.io.IOException) {
                problem = e.message ?: e.toString()
                stage = SinkStage.FAILED
                return
            }
            watch(sink)
        } finally {
            sink.stop()
        }
    }

    private fun watch(sink: SinkStream) {
        // Counted from zero, not from a sentinel: until a first chunk arrives nothing has been
        // heard from the host, and that is not "playing" - the stage stays where dialling left it
        // until the audio is really coming, or the wait runs out and the host is said to be quiet.
        var seen = 0
        var changedAt = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val arrived = sink.arrived
            if (arrived != seen) {
                seen = arrived
                changedAt = now
            }
            stage = when {
                now - changedAt > silentAfterNanos -> SinkStage.HOST_SILENT
                arrived == 0 -> SinkStage.SYNCING
                else -> SinkStage.PLAYING
            }
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

        private const val WATCH_MILLIS = 200L
        private const val STOP_WAIT_MILLIS = 7_000L
    }
}
