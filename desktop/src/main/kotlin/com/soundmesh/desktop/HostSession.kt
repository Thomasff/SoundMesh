package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomOrder
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.ChunkServer
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.RoomCommandServer
import com.soundmesh.probe.sync.SpatialFieldServer
import com.soundmesh.product.EffectKind
import com.soundmesh.product.RoomIcon
import com.soundmesh.core.SplitAxis
import com.soundmesh.product.RoomState
import com.soundmesh.product.SourceSpot
import com.soundmesh.product.SpatialRoom
import com.soundmesh.product.ruleOf
import com.soundmesh.product.withEffect
import java.io.File
import java.net.BindException

/** The four ports a handset dials, together so a test can move all of them. */
data class HostPorts(
    val chunk: Int = ChunkCodec.DEFAULT_PORT,
    val clock: Int = ClockPacket.DEFAULT_PORT,
    val command: Int = COMMAND_PORT,
    val spatial: Int = SinkStream.SPATIAL_PORT
)

enum class HostPort { COMMAND, CLOCK, AUDIO, SPATIAL }

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
    val stopped: Boolean,
    /** Its volume as it last said, in per cent, or null before it has said. */
    val volumePercent: Int?,
    /**
     * What it was last told to be on its own, or null since the room slider or a restore. Apart
     * from [volumePercent] for the handset host's reason (VolumeRow.asked): drawn from the report
     * alone, a slider let go of jumps back to where the device was until the report arrives.
     */
    val askedPercent: Int?
)

data class HostStatus(
    val open: Boolean,
    val playing: Boolean,
    /** The last play ran to the end of its file and stopped the room itself, rather than being stopped. */
    val ended: Boolean,
    val file: String?,
    /** Which song of how many, and how far in the room is hearing it; null when not playing. */
    val playhead: Playhead?,
    val paused: Boolean,
    /** Songs in the list that could not be read and were passed over, each with why. */
    val skipped: List<String>,
    /** This machine's own id and colour place, for the first row of the roster. */
    val selfId: String?,
    val selfPlace: Int?,
    /** Devices standing by on the command port, in the order they joined. */
    val phones: List<RoomPhone>,
    /** This machine's own volume, which is SoundMesh's only - see [SoftwareVolume]. */
    val volumePercent: Int,
    /** What the room slider was last set to, or null since a restore. */
    val roomVolumePercent: Int?,
    /** Something was set that a restore would put back. */
    val volumeTouched: Boolean,
    /** The room's effect and knobs, and who is drawn where - what the spatial rule is built from. */
    val room: RoomState,
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
 * the clock, the audio and the spatial ports, and only then puts the record on the network - so
 * nothing that finds it can dial a port not yet open. Play and stop come and go inside that
 * without touching a port.
 *
 * The handset opens its clock, audio and spatial ports per play instead. Here they live as long as the
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
    private var spatialServer: SpatialFieldServer? = null
    private var record: AutoCloseable? = null
    private var teller: Thread? = null
    private var selfId: String? = null

    @Volatile private var problem: HostProblem? = null

    @Volatile private var playing = false
    @Volatile private var ended = false
    @Volatile private var stream: HostStream? = null
    @Volatile private var streamSinceNanos = 0L
    @Volatile private var file: String? = null
    @Volatile private var songs: SongList? = null
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

    // This machine's own volume, and what the room and single devices were last told. See [setRoomVolume].
    private val volume = SoftwareVolume()
    private var roomVolume: Int? = null
    private val asked = HashMap<String, Int>()

    // The room as the window has set it, and where each device was last drawn. See [refreshRoom].
    private var room = RoomState()
    private val whereTheyWere = HashMap<String, RoomIcon>()

    // This machine's own part of the room while it plays aloud, or null - see [playOn].
    @Volatile private var localSpatial: SinkSpatial? = null
    private var playingHere = false

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
        val spatial = SpatialFieldServer(ports.spatial, hostId, command.places())

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
        // With the role rather than per play, as the handset opens it per session: for the same
        // reason as the other two, see the class comment.
        if (!bind(HostPort.SPATIAL, ports.spatial, spatial::start, spatial::stop)) return

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
        spatialServer = spatial
        record = advertised
        selfId = hostId
        refreshRoom()
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
            spatialServer?.stop()
            chunkServer?.stop()
            clockServer?.stop()
            commandServer?.stop()
            spatialServer = null
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
        val volumes = command?.volumes().orEmpty()
        // The drawing's colours are whichever channel is holding the room just now, as on the
        // handset: the spatial channel's while playing, the standing channel's between songs.
        val colours = if (playing && stream != null) spatialServer?.places().orEmpty() else places
        HostStatus(
            room = room.copy(
                colours = colours,
                silentIds = room.icons.map { it.peerId }
                    .filter { it != selfId && (it in quiet || (onAudio != null && it !in onAudio)) }
                    .toSet()
            ),
            volumePercent = volume.percent,
            roomVolumePercent = roomVolume,
            volumeTouched = roomVolume != null || asked.isNotEmpty() || volume.percent != SoftwareVolume.FULL,
            open = command != null,
            playing = playing,
            ended = ended,
            file = file,
            playhead = songs?.takeIf { playing }?.playhead(),
            paused = songs?.paused == true,
            skipped = songs?.skipped().orEmpty(),
            selfId = selfId,
            selfPlace = selfId?.let { places[it] },
            phones = command?.standingPeerIds().orEmpty().map { peerId ->
                RoomPhone(
                    peerId = peerId,
                    name = command?.nameOf(peerId) ?: peerId.takeLast(4),
                    place = places[peerId],
                    quiet = peerId in quiet,
                    stopped = onAudio != null && (peerId !in onAudio || peerId in quiet),
                    volumePercent = volumes[peerId]?.percent,
                    askedPercent = asked[peerId]
                )
            },
            sinksOnAudio = chunkServer?.clientCount() ?: 0,
            droppedChunks = chunkServer?.droppedChunks() ?: 0,
            localBand = stream?.localSeamBand(),
            localShares = stream?.localSeamShares(),
            problem = problem
        )
    }

    /** One song - see the other [play]. */
    fun play(file: File, alsoHere: Boolean) = play(listOf(file), 0, alsoHere)

    /**
     * Starts the room playing [files] from [first] on, one after another, on a thread of its own
     * because opening the speakers must not happen on the window's. The handsets are told once
     * the stream exists - see [tell].
     */
    fun play(files: List<File>, first: Int, alsoHere: Boolean) = synchronized(lock) {
        val chunks = chunkServer ?: return
        if (player?.isAlive == true || files.isEmpty()) return
        problem = null
        this.file = files[first.coerceIn(0, files.size - 1)].name
        playing = true
        ended = false
        stopEchoUntilNanos = 0L
        val mine = ++generation
        val list = SongList(files, first, leadFrames = HostStream.DEFAULT_LEAD_NANOS * TonePcmSource.SAMPLE_RATE / 1_000_000_000L)
        songs = list
        player = Thread({ playOn(chunks, list, alsoHere, mine) }, "host-play").apply {
            isDaemon = true
            start()
        }
    }

    /** [by] songs on or back in the list; the room hears the new one a lead later. */
    fun stepSong(by: Int) = synchronized(lock) {
        songs?.step(by)
        stream?.jump()
    }

    /** To [millis] into the song playing. */
    fun seekTo(millis: Long) = synchronized(lock) {
        songs?.seekTo(millis)
        stream?.jump()
    }

    /**
     * Paused, the room is sent silence; the jump throws away what the sinks had queued, so it
     * goes quiet at once rather than a lead later, and going on again starts from what was heard.
     */
    fun setPaused(wanted: Boolean) {
        synchronized(lock) {
            val list = songs ?: return
            if (list.paused == wanted) return
            list.setPaused(wanted)
            stream?.jump()
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

    /**
     * Tells the whole room, this machine included, what volume to be - the handset host's room
     * slider. Everybody, including devices set on their own a moment ago: the room slider levels
     * the room.
     */
    fun setRoomVolume(percent: Int) = synchronized(lock) {
        roomVolume = percent
        asked.clear()
        volume.set(percent)
        commandServer?.send(RoomOrder(RoomCommand.SET_VOLUME, percent))
    }

    /** One device on its own, for the one standing next to a wall. Answers whether the line to it was there. */
    fun setDeviceVolume(peerId: String, percent: Int): Boolean = synchronized(lock) {
        asked[peerId] = percent
        commandServer?.sendTo(peerId, RoomOrder(RoomCommand.SET_VOLUME, percent)) == true
    }

    /** This machine's own sound on its own. */
    fun setOwnVolume(percent: Int) = synchronized(lock) { volume.set(percent) }

    /** Every device back to where it was before the room touched it, this machine included. */
    fun restoreVolume() = synchronized(lock) {
        roomVolume = null
        asked.clear()
        volume.restore()
        commandServer?.send(RoomCommand.RESTORE_VOLUME)
    }

    /** Sets the room to one of the four effects - the handset host's effect list. */
    fun setEffect(kind: EffectKind) = updateRoom { it.withEffect(kind) }

    /** 房间回声, 0 to 1. */
    fun setReverb(amount: Float) = updateRoom { it.copy(reverb = amount) }

    /** 包裹感: how much each device keeps when a turning source faces away from it. */
    fun setEnvelopment(amount: Float) = updateRoom { it.copy(envelopment = amount) }

    /** How long one circuit of 旋转 takes. */
    fun setSpinSeconds(seconds: Int) = updateRoom { it.copy(periodSeconds = seconds) }

    /** One device dragged somewhere else on the drawing. */
    fun moveIcon(icon: RoomIcon) = updateRoom { room ->
        room.copy(icons = room.icons.map { if (it.peerId == icon.peerId) SpatialRoom.clamped(icon) else it })
    }

    /** 自定义声音位置's dot dragged to [spot]: which way, how far off, how far in - the handset's moveSource. */
    fun moveSource(spot: SourceSpot) = updateRoom {
        it.copy(
            pan = SpatialRoom.panOf(spot),
            retreat = SpatialRoom.retreatOf(spot),
            envelopment = SpatialRoom.envelopmentOf(spot)
        )
    }

    /** Hands [peerId] the other half of the split, or takes it back. */
    fun togglePart(peerId: String) = updateRoom {
        it.copy(otherHalfIds = if (peerId in it.otherHalfIds) it.otherHalfIds - peerId else it.otherHalfIds + peerId)
    }

    /**
     * 分开放: null for not at all, or which way to split, fully - the handset's three segments.
     * Who carries which half is left as it was, for [EffectSettings]'s reason.
     */
    fun setSplit(axis: SplitAxis?) = updateRoom {
        if (axis == null) it.copy(separation = 0f) else it.copy(splitAxis = axis, separation = 1f)
    }

    /** 分得多彻底, 0 to 1. */
    fun setSeparation(amount: Float) = updateRoom { it.copy(separation = amount) }

    /** Where the low half stops, when splitting low from high. */
    fun setCrossoverHz(hz: Float) = updateRoom { it.copy(crossoverHz = hz) }

    private fun updateRoom(change: (RoomState) -> RoomState) = synchronized(lock) {
        room = change(room)
        publishRoom()
    }

    /**
     * The drawing brought up to date with who is in the room, told to everybody if that changed.
     * Under [lock]; run on every pass of the teller, as the handset host reads its room.
     *
     * Who is in it follows the handset host (HomeActivity.readRoom): while playing, whoever has
     * named itself on the spatial channel - the devices actually taking part - with this machine
     * first when it plays aloud; between songs, this machine and whoever stands by. A device that
     * comes and goes keeps where it was drawn.
     */
    private fun refreshRoom() {
        val self = selfId ?: return
        val spatial = spatialServer ?: return
        val roster =
            if (playing && stream != null) listOfNotNull(self.takeIf { playingHere }) + spatial.peerIds()
            else listOf(self) + (commandServer?.standingPeerIds() ?: emptyList())
        if (roster.distinct() == room.icons.map { it.peerId }) return
        whereTheyWere.putAll(room.icons.associateBy { it.peerId })
        room = room.copy(
            icons = SpatialRoom.reconciled(room.icons, roster, whereTheyWere),
            selfId = self,
            otherHalfIds = SpatialRoom.reconciledOtherHalf(room.otherHalfIds, roster)
        )
        publishRoom()
    }

    /**
     * Makes the room's rule the one everybody plays under, this machine included, from a shared
     * instant a little way off - the handset host's publishSpatialField. Under [lock].
     *
     * A room that cannot be drawn is not published, as on the handset: the next roster should
     * still find it, and a host that stopped over it would be the room going silent.
     */
    private fun publishRoom() {
        val field = runCatching { ruleOf(room) }.getOrNull() ?: return
        val stamped = field.copy(effectiveAtHostNanos = System.nanoTime() + SPATIAL_LEAD_NANOS)
        spatialServer?.publish(stamped)
        localSpatial?.apply(stamped)
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

    private fun playOn(chunks: ChunkServer, songs: SongList, alsoHere: Boolean, mine: Int) {
        // Everything this thread says about the room is said only while it is still the current
        // press of play: after that, [playing] and [problem] belong to the next one.
        fun current() = generation == mine
        try {
            // A list with nothing playable in it is said before anybody is told to play.
            if (!songs.prepare()) {
                if (current()) {
                    problem = HostProblem.FileUnreadable(songs.skipped().joinToString("；"))
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
                // This machine's own part of the room, shaped by the same functions a sink's is.
                val here = if (speakers == null) null else synchronized(lock) { selfId }?.let { SinkSpatial(it) }
                synchronized(lock) {
                    if (current()) {
                        playingHere = here != null
                        localSpatial = here
                        publishRoom()
                    }
                }
                // Which song the room was last told of, so it is told once per song.
                var announced: String? = null
                val hostStream = HostStream(
                    ports.chunk,
                    { _, _ ->
                        val pcm = songs.nextChunk() ?: ByteArray(HostStream.CHUNK_BYTES)
                        val name = songs.playhead()?.name
                        if (name != null && name != announced) {
                            announced = name
                            file = name
                            synchronized(lock) { spatialServer?.publishNowPlaying(name) }
                        }
                        pcm
                    },
                    localOutput = speakers?.output?.let { GainOutput(it, volume) },
                    chunkServer = chunks,
                    localShape = { chunk -> here?.shaped(chunk) ?: chunk }
                )
                if (current()) {
                    streamSinceNanos = System.nanoTime()
                    stream = hostStream
                }
                // Once through the list and no more, the way the handset host plays a folder: one
                // with no end is one a person can only stop.
                val lastPlayAt = hostStream.streamWhile { playing && current() && songs.hasMore() }
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
                // Only this press's: a newer one has set its own.
                synchronized(lock) {
                    if (current()) {
                        localSpatial = null
                        playingHere = false
                    }
                }
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
            synchronized(lock) { refreshRoom() }
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

        /**
         * How far ahead a new rule takes effect, so every device changes on the same chunk - the
         * handset host's SPATIAL_LEAD_NANOS, with its reasoning.
         */
        private const val SPATIAL_LEAD_NANOS = 200_000_000L

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
