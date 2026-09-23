package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomOrder
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.ChunkServer
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.RoomCommandServer
import java.io.File
import java.net.BindException

/** The three ports a handset dials, together so a test can move all of them. */
data class HostPorts(
    val chunk: Int = ChunkCodec.DEFAULT_PORT,
    val clock: Int = ClockPacket.DEFAULT_PORT,
    val command: Int = COMMAND_PORT
)

enum class HostPort { COMMAND, CLOCK, AUDIO }

/** What stopped the host doing what it was asked, in terms the window can say. */
sealed interface HostProblem {
    data class PortTaken(val port: HostPort, val number: Int) : HostProblem
    data class FileUnreadable(val detail: String) : HostProblem
    data class SpeakersUnavailable(val detail: String) : HostProblem
}

data class HostStatus(
    val open: Boolean,
    val playing: Boolean,
    val file: String?,
    /** Handsets standing by on the command port, by the name each gave. */
    val phones: List<String>,
    val sinksOnAudio: Int,
    val droppedChunks: Int,
    val localBand: String?,
    val localShares: String?,
    val problem: HostProblem?
)

/**
 * This machine as the host of a room, the way the window drives it.
 *
 * Split the way the handset host is split: the **room** belongs to the role and the **playing**
 * belongs to the button. Choosing host opens the command port a standing-by handset waits on,
 * the clock and the audio ports, and only then puts the record on the network - so nothing that
 * finds it can dial a port not yet open. Play and stop come and go inside that without touching
 * a port.
 *
 * The handset opens its clock and audio ports per play instead. Here they live as long as the
 * role does, because stop-then-play is the pair of clicks a person makes most, and the two
 * servers under them have no guard for being reopened while a thread is still in accept. Not
 * reopening them is simpler than proving the reopen safe.
 *
 * Not the command-line [main] in Host.kt, which stays what the on-device queue runs: a counted
 * run that waits for a sink and prints as it goes.
 */
class HostSession(
    private val identityDirectory: File,
    private val ports: HostPorts = HostPorts(),
    /** Off in tests: a record a test puts on the network is a host every handset in the room sees. */
    private val advertise: Boolean = true,
    private val openSpeakers: () -> Speakers = Speakers::open,
    private val retellMillis: Long = RETELL_MILLIS
) {
    private val lock = Any()

    private var commandServer: RoomCommandServer? = null
    private var clockServer: ClockSyncServer? = null
    private var chunkServer: ChunkServer? = null
    private var record: AutoCloseable? = null
    private var teller: Thread? = null

    @Volatile private var problem: HostProblem? = null

    @Volatile private var playing = false
    @Volatile private var stream: HostStream? = null
    @Volatile private var file: String? = null
    private var player: Thread? = null

    // Handsets already told the room is playing, by id rather than by count: one leaving as
    // another arrives leaves the count where it was, and the one that arrived would never hear it.
    private val told = HashSet<String>()

    /** Binds the three ports and advertises, or says which port was taken and holds none of them. */
    fun open() = synchronized(lock) {
        if (commandServer != null) return
        problem = null
        val hostId = HostIdentity(identityDirectory).current()
        val command = RoomCommandServer(ports.command, hostId)
        val clock = ClockSyncServer(ports.clock)
        val chunks = ChunkServer(ports.chunk)

        val bound = ArrayList<() -> Unit>()
        fun bind(port: HostPort, number: Int, start: () -> Unit, stop: () -> Unit): Boolean =
            try {
                start()
                bound.add(stop)
                true
            } catch (_: BindException) {
                for (undo in bound.asReversed()) undo()
                problem = HostProblem.PortTaken(port, number)
                false
            }
        if (!bind(HostPort.COMMAND, ports.command, command::start, command::stop)) return
        if (!bind(HostPort.CLOCK, ports.clock, clock::start, clock::stop)) return
        if (!bind(HostPort.AUDIO, ports.chunk, chunks::start, chunks::stop)) return

        commandServer = command
        clockServer = clock
        chunkServer = chunks
        // After all three, so nothing that finds the record can dial a port not yet open.
        if (advertise) record = PeerDiscovery.register("$SERVICE_NAME_PREFIX-$hostId", ports.chunk, hostId)
        teller = Thread({ tellWhileOpen() }, "host-tell").apply {
            isDaemon = true
            start()
        }
    }

    /** Withdraws the record first, the mirror of [open], then gives every port back. */
    fun close() {
        stopPlaying()
        synchronized(lock) {
            teller?.interrupt()
            teller = null
            runCatching { record?.close() }
            record = null
            chunkServer?.stop()
            clockServer?.stop()
            commandServer?.stop()
            chunkServer = null
            clockServer = null
            commandServer = null
        }
    }

    fun status(): HostStatus = synchronized(lock) {
        val command = commandServer
        HostStatus(
            open = command != null,
            playing = playing,
            file = file,
            phones = command?.standingPeerIds()?.map { command.nameOf(it) ?: it.takeLast(4) }.orEmpty(),
            sinksOnAudio = chunkServer?.clientCount() ?: 0,
            droppedChunks = chunkServer?.droppedChunks() ?: 0,
            localBand = stream?.localSeamBand(),
            localShares = stream?.localSeamShares(),
            problem = problem
        )
    }

    /**
     * Starts the room playing [file], on a thread of its own because opening the speakers must
     * not happen on the window's. The handsets are told once the stream exists - see [tell].
     */
    fun play(file: File, alsoHere: Boolean) = synchronized(lock) {
        val chunks = chunkServer ?: return
        if (player?.isAlive == true) return
        problem = null
        this.file = file.name
        playing = true
        player = Thread({ playOn(chunks, file, alsoHere) }, "host-play").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Tells every handset to stop, then ends the stream.
     *
     * Under the same lock as [tell], so a handset cannot be told to stop and then, a moment
     * later, told to play by a round that had already read the room as playing.
     */
    fun stopPlaying() = synchronized(lock) {
        if (!playing && player == null) return
        playing = false
        commandServer?.send(RoomCommand.STOP)
        told.clear()
        player?.join(JOIN_MILLIS)
        player = null
        stream = null
    }

    private fun playOn(chunks: ChunkServer, file: File, alsoHere: Boolean) {
        val source = try {
            WavPcmSource.open(file)
        } catch (e: Exception) {
            problem = HostProblem.FileUnreadable(e.message ?: e.toString())
            playing = false
            return
        }
        val speakers = if (!alsoHere) null else try {
            openSpeakers()
        } catch (e: Exception) {
            problem = HostProblem.SpeakersUnavailable(e.message ?: e.toString())
            playing = false
            return
        }
        try {
            val hostStream = HostStream(ports.chunk, source::fill, localOutput = speakers?.output, chunkServer = chunks)
            stream = hostStream
            hostStream.streamWhile { playing }
        } finally {
            // Straight away rather than after a tail: stop is a person asking for quiet, and the
            // handsets are stopping at the same moment.
            speakers?.close()
        }
    }

    private fun tellWhileOpen() {
        while (!Thread.currentThread().isInterrupted) {
            tell()
            try {
                Thread.sleep(retellMillis)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * Says "play" to every handset standing by that has not heard it since the room started.
     *
     * The channel replays nothing - a command is something that happened at an instant - so a
     * handset that arrives while the room is playing hears it only because the host says it
     * again, which is what the handset host does from its own screen. Only once the stream exists,
     * so the audio port a told handset dials is already being fed. Counted as told only when the
     * send went out: a handset whose write failed is asked again next round rather than never.
     */
    private fun tell() = synchronized(lock) {
        val command = commandServer ?: return
        if (!playing || stream == null) return
        val standing = command.standingPeerIds()
        told.retainAll(standing.toSet())
        for (peerId in standing) {
            if (peerId !in told && command.sendTo(peerId, RoomOrder(RoomCommand.PLAY))) told.add(peerId)
        }
    }

    companion object {
        /**
         * How often the roster is checked for a handset that has not been told the room is
         * playing - the handset host checks on its own screen's refresh, about this often.
         */
        const val RETELL_MILLIS = 200L

        /** Long enough for a stream sleeping to its next chunk to notice it was told to stop. */
        private const val JOIN_MILLIS = 2_000L
    }
}
