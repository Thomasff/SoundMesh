package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
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

    @Volatile private var problem: HostProblem? = null

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
    }

    /** Withdraws the record first, the mirror of [open], then gives every port back. */
    fun close() {
        synchronized(lock) {
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
            playing = false,
            file = null,
            phones = command?.standingPeerIds()?.map { command.nameOf(it) ?: it.takeLast(4) }.orEmpty(),
            sinksOnAudio = chunkServer?.clientCount() ?: 0,
            droppedChunks = chunkServer?.droppedChunks() ?: 0,
            localBand = null,
            localShares = null,
            problem = problem
        )
    }

    companion object {
        /**
         * How often the roster is checked for a handset that has not been told the room is
         * playing - the handset host checks on its own screen's refresh, about this often.
         */
        const val RETELL_MILLIS = 200L
    }
}
