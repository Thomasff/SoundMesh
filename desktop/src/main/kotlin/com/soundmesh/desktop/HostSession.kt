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
    data class AdvertiseFailed(val detail: String) : HostProblem
    data class PlayFailed(val detail: String) : HostProblem
}

/** One device standing by on the host's command port, as the roster shows it. */
data class RoomPhone(
    val peerId: String,
    /** The name it gave, or the end of its id when it gave none. */
    val name: String,
    /** Its colour's place in the palette, or null before one is settled. */
    val place: Int?,
    /**
     * It has stopped saying it is there. Not gone: a handset stops its heartbeat a minute after its
     * screen goes off and still follows the next play - see `RoomCommandServer.quietPeerIds`.
     */
    val quiet: Boolean,
    /**
     * The room is playing and this one is not taking the audio, or has gone quiet - the handset
     * host's `whoStopped`. Not said for the first [HostSession.STOPPED_GRACE_MILLIS] of a play,
     * while every device is still dialling in.
     */
    val stopped: Boolean
)

data class HostStatus(
    val open: Boolean,
    val playing: Boolean,
    /** The last play ran to the end of its file and stopped the room itself, rather than being stopped. */
    val ended: Boolean,
    val file: String?,
    /** This machine's own id and colour place, for the first row of the roster. */
    val selfId: String?,
    val selfPlace: Int?,
    /** Devices standing by on the command port, in the order they joined. */
    val phones: List<RoomPhone>,
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
    private val retellMillis: Long = RETELL_MILLIS,
    /**
     * What puts the record on the network. A parameter so a test can make it fail without
     * putting anything on the network - the one way to reach what [open] does when it does.
     */
    private val advertiser: (serviceName: String, port: Int, hostId: String) -> AutoCloseable = PeerDiscovery::register,
    private val stoppedGraceMillis: Long = STOPPED_GRACE_MILLIS
) {
    private val lock = Any()

    private var commandServer: RoomCommandServer? = null
    private var clockServer: ClockSyncServer? = null
    private var chunkServer: ChunkServer? = null
    private var record: AutoCloseable? = null
    private var teller: Thread? = null
    private var selfId: String? = null

    @Volatile private var problem: HostProblem? = null

    @Volatile private var playing = false
    @Volatile private var ended = false
    @Volatile private var stream: HostStream? = null
    @Volatile private var streamSinceNanos = 0L
    @Volatile private var file: String? = null
    private var player: Thread? = null

    /**
     * Which press of play the running player belongs to. A stop gives up waiting for a player
     * after [JOIN_MILLIS] - reading a large file or opening the speakers can take longer - and a
     * play after that starts another. The old one, when it gets there, finds it is no longer the
     * current press and stops rather than streaming beside the new one on the shared [playing].
     */
    @Volatile private var generation = 0

    // Handsets already told the room is playing, by id rather than by count: one leaving as
    // another arrives leaves the count where it was, and the one that arrived would never hear it.
    private val told = HashSet<String>()

    // Until when a stop is said again, and when it was last said. See [echoStop].
    private var stopEchoUntilNanos = 0L
    private var stopSaidAtNanos = 0L

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

        // A handset that opens a second line under the same name replaces its first, and its id
        // never leaves [RoomCommandServer.standingPeerIds] - so [tell] would go on counting it as
        // told while the handset, back from out of range with its session long over, stands by
        // waiting to hear it. The channel says so here, for a replaced line and for a failed
        // write alike. Called outside the channel's own lock, and [tell] holds that one only for
        // a moment inside [RoomCommandServer.sendTo], so taking ours here cannot deadlock.
        command.onLeft = { peerId, _ -> synchronized(lock) { told.remove(peerId) } }

        // After all three, so nothing that finds the record can dial a port not yet open. A
        // record that will not go on leaves nothing open: nobody could find this host, and one
        // that stayed open would stream to nobody while the button said it was the host.
        val advertised = if (!advertise) null else try {
            advertiser("$SERVICE_NAME_PREFIX-$hostId", ports.chunk, hostId)
        } catch (e: Exception) {
            for (undo in bound.asReversed()) undo()
            problem = HostProblem.AdvertiseFailed(e.message ?: e.toString())
            return
        }
        commandServer = command
        clockServer = clock
        chunkServer = chunks
        record = advertised
        selfId = hostId
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
        val places = command?.places().orEmpty()
        val quiet = command?.quietPeerIds().orEmpty().toSet()
        // Only once the stream has run a while: before it exists nobody has been told to dial the
        // audio, and for the first seconds after every device is still settling its clock - which
        // would draw the whole room as dropped at the start of every song.
        val settled = playing && stream != null && System.nanoTime() - streamSinceNanos >= stoppedGraceMillis * 1_000_000L
        val onAudio = if (settled) chunkServer?.peerIds().orEmpty().toSet() else null
        HostStatus(
            open = command != null,
            playing = playing,
            ended = ended,
            file = file,
            selfId = selfId,
            selfPlace = selfId?.let { places[it] },
            phones = command?.standingPeerIds().orEmpty().map { peerId ->
                RoomPhone(
                    peerId = peerId,
                    name = command?.nameOf(peerId) ?: peerId.takeLast(4),
                    place = places[peerId],
                    quiet = peerId in quiet,
                    stopped = onAudio != null && (peerId !in onAudio || peerId in quiet)
                )
            },
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
        ended = false
        stopEchoUntilNanos = 0L
        val mine = ++generation
        player = Thread({ playOn(chunks, file, alsoHere, mine) }, "host-play").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Tells every handset to stop, then ends the stream - and goes on saying stop for a while,
     * see [echoStop].
     *
     * The player is waited for only so long. One still opening its file or its speakers when the
     * wait runs out is let go of, and [generation] is what stops it streaming once it gets there.
     */
    fun stopPlaying() = synchronized(lock) {
        if (!playing && player == null) return
        sayStop()
        player?.join(JOIN_MILLIS)
        player = null
        stream = null
    }

    /** The room's half of a stop: every handset told, and told again for a while. Under [lock]. */
    private fun sayStop() {
        playing = false
        commandServer?.send(RoomCommand.STOP)
        val now = System.nanoTime()
        stopSaidAtNanos = now
        stopEchoUntilNanos = now + STOP_ECHO_NANOS
        told.clear()
    }

    /**
     * The song ran out on its own: the same stop a person asks for, said by this thread, which is
     * why it leaves [player] rather than joining it. Nothing if a stop or a newer play got here first.
     */
    private fun endOfSong(mine: Int) = synchronized(lock) {
        if (generation != mine || !playing) return
        sayStop()
        ended = true
        player = null
        stream = null
    }

    private fun playOn(chunks: ChunkServer, file: File, alsoHere: Boolean, mine: Int) {
        // Everything this thread says about the room is said only while it is still the current
        // press of play: after that, [playing] and [problem] belong to the next one.
        fun current() = generation == mine
        try {
            val source = try {
                FilePcmSource.open(file)
            } catch (e: Exception) {
                if (current()) {
                    problem = HostProblem.FileUnreadable(e.message ?: e.toString())
                    playing = false
                }
                return
            }
            val speakers = if (!alsoHere) null else try {
                openSpeakers()
            } catch (e: Exception) {
                if (current()) {
                    problem = HostProblem.SpeakersUnavailable(e.message ?: e.toString())
                    playing = false
                }
                return
            }
            try {
                val hostStream = HostStream(ports.chunk, source::fill, localOutput = speakers?.output, chunkServer = chunks)
                if (current()) {
                    streamSinceNanos = System.nanoTime()
                    stream = hostStream
                }
                // Once through and no more, the way the handset host plays a song: one with no end
                // is one a person can only stop. The loop in the source stays for the command line,
                // whose runs listen for the seam.
                val songChunks = source.frameCount / ChunkCodec.FRAMES_PER_CHUNK
                var sent = 0
                val lastPlayAt = hostStream.streamWhile { playing && current() && sent++ < songChunks }
                if (lastPlayAt != null && playing && current()) {
                    // Every chunk is stamped a lead into its own future, so the song is over only
                    // once the last one has been heard - the handset host's endOfSong. Stopping at
                    // the last send would cut the final second and a half off every song.
                    val over = lastPlayAt + HostStream.CHUNK_NANOS
                    while (playing && current() && System.nanoTime() - over < 0) Thread.sleep(END_POLL_MILLIS)
                    if (playing && current()) endOfSong(mine)
                }
            } finally {
                // Straight away rather than after a tail: stop is a person asking for quiet, and the
                // handsets are stopping at the same moment.
                speakers?.close()
            }
        } catch (e: Throwable) {
            // Anything else - an OutOfMemoryError reading a very large file is the likely one -
            // would otherwise end this thread with the button still saying stop and nothing on
            // screen saying why.
            if (current()) {
                playing = false
                problem = HostProblem.PlayFailed(e.toString())
            }
        }
    }

    private fun tellWhileOpen() {
        while (!Thread.currentThread().isInterrupted) {
            tell()
            echoStop()
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

    /**
     * Says "stop" again, every [STOP_ECHO_EVERY_NANOS] for [STOP_ECHO_NANOS] after a stop, until
     * the next play.
     *
     * Once is not enough, for two reasons neither of which a lock on this end can fix. The
     * channel writes every command on a thread of its own, so the lock orders the threads being
     * started, not the writes: a "play" and the "stop" right after it can reach a handset in
     * either order. And a handset told to play ignores a stop until its session is up, about a
     * second later. Either way a quick stop is lost, and the handset opens a session on a stream
     * that has ended and plays silence until it decides the host has gone.
     *
     * Repeating it is harmless: a handset standing by obeys "stop" only while it has a session,
     * so to one that never started, or has already stopped, it says nothing. Under the same lock
     * as [tell], so it never goes out once a new play has cleared it.
     */
    private fun echoStop() = synchronized(lock) {
        val command = commandServer ?: return
        if (playing || stopEchoUntilNanos == 0L) return
        val now = System.nanoTime()
        if (now - stopEchoUntilNanos >= 0) {
            stopEchoUntilNanos = 0L
            return
        }
        if (now - stopSaidAtNanos < STOP_ECHO_EVERY_NANOS) return
        command.send(RoomCommand.STOP)
        stopSaidAtNanos = now
    }

    companion object {
        /**
         * How often the roster is checked for a handset that has not been told the room is
         * playing - the handset host checks on its own screen's refresh, about this often.
         */
        const val RETELL_MILLIS = 200L

        /**
         * How long after a play starts before a device not taking the audio is said to have
         * stopped. A standing handset hears "play", settles its clock - two seconds for its first
         * estimate - and only then dials; five leaves room for a slow one. Picked, not measured.
         */
        const val STOPPED_GRACE_MILLIS = 5_000L

        /** Long enough for a stream sleeping to its next chunk to notice it was told to stop. */
        private const val JOIN_MILLIS = 2_000L

        /** How often the wait for the last chunk to be heard looks to see whether stop was pressed. */
        private const val END_POLL_MILLIS = 20L

        /**
         * How long a stop is said again for. Past the second or so a handset takes to open a
         * session after being told to play, with room for a slow one.
         */
        private const val STOP_ECHO_NANOS = 4_000_000_000L

        /** How often, within that. */
        private const val STOP_ECHO_EVERY_NANOS = 500_000_000L
    }
}
