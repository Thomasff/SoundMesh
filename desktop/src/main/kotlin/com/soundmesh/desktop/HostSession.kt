package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.LeadMeter
import com.soundmesh.core.PairingCode
import com.soundmesh.core.PairingCodeCodec
import com.soundmesh.core.PeerAdvertisement
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.RoomOrder
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.Carried
import com.soundmesh.probe.sync.ChunkServer
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.EventLog
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.RoomCommandServer
import com.soundmesh.probe.sync.RoomCommands
import com.soundmesh.probe.sync.RoundRecorder
import com.soundmesh.probe.sync.RoundSpeaker
import com.soundmesh.probe.sync.SpatialFieldServer
import com.soundmesh.probe.sync.StoredListenerDistance
import com.soundmesh.product.ArmSchedule
import com.soundmesh.product.CarriedSides
import com.soundmesh.product.EffectKind
import com.soundmesh.product.HostCommands
import com.soundmesh.product.HostPlace
import com.soundmesh.product.HostRound
import com.soundmesh.product.HostRoundReport
import com.soundmesh.product.RoomIcon
import com.soundmesh.core.SplitAxis
import com.soundmesh.product.RoomState
import com.soundmesh.product.RoundDials
import com.soundmesh.product.RoundLine
import com.soundmesh.product.RoundPorts
import com.soundmesh.product.RoundResult
import com.soundmesh.product.SoundCheck
import com.soundmesh.product.SourceSpot
import com.soundmesh.product.SpatialRoom
import com.soundmesh.product.StoredRoomDrawing
import com.soundmesh.product.defaultTimingFor
import com.soundmesh.product.fitOffer
import com.soundmesh.product.measuredDistances
import com.soundmesh.product.readBack
import com.soundmesh.product.ruleOf
import com.soundmesh.product.withEffect
import java.io.File
import java.net.BindException

/** The ports a handset dials, together so a test can move all of them. */
data class HostPorts(
    val chunk: Int = ChunkCodec.DEFAULT_PORT,
    val clock: Int = ClockPacket.DEFAULT_PORT,
    val command: Int = COMMAND_PORT,
    val spatial: Int = SinkStream.SPATIAL_PORT,
    // A measuring round's own three, held only while one runs - the handset host's.
    val result: Int = RoundPorts.RESULT,
    val plan: Int = RoundPorts.PLAN,
    val room: Int = RoundPorts.ROOM
)

enum class HostPort { COMMAND, CLOCK, AUDIO, SPATIAL }

/** What stopped the host doing what it was asked, in terms the window can say. */
sealed interface HostProblem {
    data class PortTaken(val port: HostPort, val number: Int) : HostProblem
    data class FileUnreadable(val detail: String) : HostProblem
    data class SpeakersUnavailable(val detail: String) : HostProblem
    data class AdvertiseFailed(val detail: String) : HostProblem
    data class PlayFailed(val detail: String) : HostProblem
    data class CaptureFailed(val app: String, val detail: String) : HostProblem
    /** The network already had a host at [address] when this one was chosen - see [HostSession.open]. */
    data class AnotherHost(val address: String) : HostProblem
}

/**
 * The address of a host on this network that is not [myId], or null - the handset's
 * `HostSearch.anotherHost`, asked the same way of the same records.
 *
 * Filtered by id because this host's own record is already out when it asks, and has to be: two
 * people choosing host within the same few seconds can only see each other if both are. And only
 * a host whose command port answers: the record of one that has just stopped goes on being answered
 * for seconds, and standing down for it would leave a room with no host at all - see
 * [RoomCommands.stillServing], which the handset asks for the same reason.
 *
 * Null is "not found", never "there is none": a network that does not carry multicast answers as an
 * empty one does. Not asked of the gateway, as the handset also does for a host serving its own
 * hotspot - not built for the computer yet (2026-09-28).
 */
fun anotherHostOnTheNetwork(myId: String): String? =
    PeerAdvertisement.otherThan(PeerDiscovery.discover(HOST_CHECK_WINDOW_MILLIS).hosts, myId)
        ?.hostAddress
        ?.takeIf { RoomCommands.stillServing(it) }

/** How long [anotherHostOnTheNetwork] listens: the handset's `HostSearch.WINDOW_MILLIS`. */
private const val HOST_CHECK_WINDOW_MILLIS = 5_000

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
    val askedPercent: Int?,
    /** What constant it said it plays by, as the handset host's roster line reads it. */
    val carrying: Carried = Carried.UNSAID,
    /** Why it kept out of the last measuring round, or null when it did not. */
    val excuse: RoomExcuse? = null
)

/** The two jobs a measuring round can be - the handset's calibration screen's two. */
enum class MeasureJob { ROOM, PAIR }

/**
 * Where a measuring round has got to and what it came to, the handset calibration screen's state.
 *
 * Outlives the round, as that screen's does: the answer is read after the chirps have stopped.
 */
data class MeasureStatus(
    val running: Boolean = false,
    val job: MeasureJob? = null,
    /** The device a pair round is with; null for a room. */
    val aimedAt: String? = null,
    /** The chirps have started: calling it off now silences the room rather than refusing to answer. */
    val underWay: Boolean = false,
    /** Where the round has got to, or what it came to; null once a device's own line replaces it. */
    val line: RoundLine? = null,
    /** When the recording ends, on [System.nanoTime]'s scale, while the chirps are going. */
    val untilLocalNanos: Long? = null,
    /** What each device's part came to, one line each, in the order they said it. */
    val heard: List<Pair<String, RoundLine>> = emptyList(),
    /** Why this machine could not record, when that is what kept the round from starting. */
    val microphone: MicrophoneProblem? = null,
    val microphoneDetail: String? = null,
    /** The last room round finished with an answer, which is when measuring again is offered beside it. */
    val roomMeasured: Boolean = false,
    /** The run is a sound check: one chirp each, nothing kept, no stop. */
    val checking: Boolean = false,
    /** What the last sound check heard, until a volume it was judged at changes. */
    val soundCheck: SoundCheck? = null,
    /** Why the last sound check never reached a chirp, or null. */
    val checkFailed: RoundLine? = null,
    /**
     * A volume has moved since [soundCheck] was taken. Its words against the rows still stand for
     * the devices nobody touched, but a pass is no longer something it can say.
     */
    val checkTouched: Boolean = false,
    /** A pair has been served with [aimedAt], which is what 完成 on its page waits for. */
    val pairServed: Boolean = false
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
    /** The program whose sound the room is playing, or null when it is playing songs or nothing. */
    val capturing: String?,
    /** Chunks of that sound made up with silence because the program handed over nothing. */
    val capturePadded: Int?,
    /** Programs this machine turned down in the mixer and has not yet been able to put back. */
    val heldDown: List<String>,
    val sinksOnAudio: Int,
    val droppedChunks: Int,
    /** This machine's own playout while the room plays: chunks played, and dropped for being late. */
    val localPlayed: Int?,
    val localLate: Int?,
    /** Times the room's timeline started again this play - a jump, a pause, or the source falling behind. */
    val jumps: Int?,
    val localBand: String?,
    val localShares: String?,
    val problem: HostProblem?,
    val measure: MeasureStatus = MeasureStatus(),
    /**
     * The hollow ones on the drawing that said, before they went, that their own system does not
     * exempt SoundMesh from power saving - the handset host's StandbyLook.KILLED. Only what they
     * said about themselves: going on its own says nothing about why.
     */
    val killedIds: Set<String> = emptySet()
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
 *
 * **It measures the room and pairs, the way a handset host does** - core's one copy of the host's
 * round ([HostRound]), on this machine's speakers and microphone, since 09-24. The one difference is
 * where it stands for a room: a person is sitting at the computer, so the computer is the listener's
 * seat as well as a speaker, and there is no step of holding it over anybody's head. See
 * [HostPlace.LISTENING].
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
    private val stoppedGraceMillis: Long = STOPPED_GRACE_MILLIS,
    /** The volume mixer, and a program's sound on its own - parameters so a test touches neither. */
    private val mixer: AppMixer = AudioSessions,
    private val openCapture: (target: CaptureTarget, onPcm: (ByteArray, Int) -> Unit) -> CaptureHandle =
        { target, onPcm -> AppCapture(target, onPcm = onPcm) },
    /** Why a round could not record here, or null when it could. See [MicrophoneCheck]. */
    private val microphoneProblem: () -> Pair<MicrophoneProblem, String>? = { MicrophoneCheck.problem() },
    /** A round's recording, given the run store, the case and host time. */
    private val roundRecorder: (RunStore, String, () -> Long) -> RoundRecorder =
        { store, caseId, hostNanosNow -> WasapiRoundRecorder(store, caseId, hostNanosNow) },
    /** A round's sound, given host time; null for this machine's speakers at SoundMesh's volume. */
    private val roundSpeaker: ((() -> Long) -> RoundSpeaker)? = null,
    /** What a round's schedule is; a test shortens it here. */
    private val timingFor: (String?) -> ArmSchedule = ::defaultTimingFor,
    /** Whether a round's recording stays once read, which the window ties to its details switch. */
    private val keepsRecordings: () -> Boolean = { false },
    /**
     * The address of a host on the network other than the one with this id, or null. A parameter
     * so a test can say there is one without anybody hosting - the real one listens for five
     * seconds. See [anotherHostOnTheNetwork].
     */
    private val anotherHost: (myId: String) -> String? = ::anotherHostOnTheNetwork
) {
    private val lock = Any()
    private val turnDown = AppTurnDown(identityDirectory, mixer)

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
    @Volatile private var capture: CapturedFeed? = null
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

    // Which devices not in the room just now were carrying the other half, and the file the
    // drawing outlives the window in - the handset's, see [keepTheDrawing].
    private val sidesTheyCarried = CarriedSides()
    private val drawing = StoredRoomDrawing(identityDirectory)

    // This machine's own part of the room while it plays aloud, or null - see [playOn].
    @Volatile private var localSpatial: SinkSpatial? = null
    private var playingHere = false

    // Until when a stop is said again, and when it was last said. See [echoStop].
    private var stopEchoUntilNanos = 0L
    private var stopSaidAtNanos = 0L

    // A measuring round: what it has said, the round itself while it runs, and whether it has been
    // called off. Changed only through [noteMeasure], because the round's thread and the thread an
    // excuse arrives on both write it.
    private val measureLock = Any()
    @Volatile private var measure = MeasureStatus()
    @Volatile private var roundInFlight: HostRound? = null
    @Volatile private var callingOff = false

    /** Binds the three ports and advertises, or says which port was taken and holds none of them. */
    fun open() {
        val (command, hostId) = synchronized(lock) { bindPorts() } ?: return
        if (!advertise) return
        // Outside the lock: Windows probes the network for the name before it answers, three
        // quarters of a second, and [status] - which the board waits on - would wait that long too.
        // After all the ports, so nothing that finds the record can dial one not yet open. A record
        // that will not go on leaves nothing open: nobody could find this host, and one that stayed
        // open would stream to nobody while the button said it was the host.
        val advertised = try {
            advertiser("$SERVICE_NAME_PREFIX-$hostId", ports.chunk, hostId)
        } catch (e: Exception) {
            if (synchronized(lock) { commandServer === command }) {
                close()
                synchronized(lock) { problem = HostProblem.AdvertiseFailed(e.message ?: e.toString()) }
            }
            return
        }
        synchronized(lock) {
            if (commandServer === command) record = advertised else runCatching { advertised.close() }
        }
        refuseToBeTheSecondHost(command, hostId)
    }

    /**
     * Gives the role back if the network already has a host - the handset's refuseToBeTheSecondHost,
     * which until 2026-09-28 was the only one: two computers both hosted, and a handset that had
     * been host for minutes shared the network with a computer that never looked.
     *
     * After the record is out, for the reason [anotherHostOnTheNetwork] filters by id. On the
     * sessions' thread and so holding it for the five seconds the search listens: a click in that
     * time waits rather than racing a close against it. Not once a device stands by, as on the
     * handset: this host has a room by then, and the one that took the role just now gives way.
     * Two chosen within the same few seconds both see each other and both stand down, and whoever
     * chooses again is the one that stays.
     */
    private fun refuseToBeTheSecondHost(command: RoomCommandServer, hostId: String) {
        val other = runCatching { anotherHost(hostId) }.getOrNull() ?: return
        synchronized(lock) {
            if (commandServer !== command || playing) return
            if (command.standingPeerIds().isNotEmpty()) return
        }
        EventLog(identityDirectory).write("stepping down as host: $other is already one")
        close()
        synchronized(lock) { problem = HostProblem.AnotherHost(other) }
    }

    /** [open]'s part under the lock: this session's ports and id, or null when there is nothing to advertise. */
    private fun bindPorts(): Pair<RoomCommandServer, String>? {
        if (commandServer != null) return null
        problem = null
        // A program a host that died left turned down - see [AppTurnDown]. Not a reason to refuse the role.
        runCatching { turnDown.putBack() }
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
        if (!bind(HostPort.COMMAND, ports.command, command::start, command::stop)) return null
        if (!bind(HostPort.CLOCK, ports.clock, clock::start, clock::stop)) return null
        if (!bind(HostPort.AUDIO, ports.chunk, chunks::start, chunks::stop)) return null
        // With the role rather than per play, as the handset opens it per session: for the same
        // reason as the other two, see the class comment.
        if (!bind(HostPort.SPATIAL, ports.spatial, spatial::start, spatial::stop)) return null

        // A handset that opens a second line under the same name replaces its first, and its id
        // never leaves [RoomCommandServer.standingPeerIds] - so [tell] would go on counting it as
        // told while the handset, back from out of range with its session long over, stands by
        // waiting to hear it. The channel says so here, for a replaced line and for a failed
        // write alike. Called outside the channel's own lock, and [tell] holds that one only for
        // a moment inside [RoomCommandServer.sendTo], so taking ours here cannot deadlock.
        command.onLeft = { peerId, _ -> synchronized(lock) { told.remove(peerId) } }

        commandServer = command
        clockServer = clock
        chunkServer = chunks
        spatialServer = spatial
        selfId = hostId
        drawing.read()?.let { saved ->
            whereTheyWere.putAll(saved.placements.associateBy { it.peerId })
            sidesTheyCarried.remember(saved.room.otherHalfIds)
            room = room.readBack(saved)
        }
        refreshRoom()
        rereadDistances()
        teller = Thread({ tellWhileOpen() }, "host-tell").apply {
            isDaemon = true
            start()
        }
        return command to hostId
    }

    /** Withdraws the record first, the mirror of [open], then gives every port back. */
    fun close() {
        // Nobody is at the window a round was started from any more - the handset's onDestroy.
        callOffMeasuring()
        stopPlaying()
        synchronized(lock) {
            // Only a session that was open has a drawing of its own; a close without one would
            // write the defaults over what the last one left.
            if (commandServer != null) keepTheDrawing()
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

    // This machine's speakers while the room plays here too, for [loudness]; null otherwise.
    @Volatile private var heardOn: Speakers? = null

    /**
     * How loud what this machine is playing is right now, 0..1, and 0 while it plays nothing here -
     * the handset host's loudness(), which the window's edge light reads once a frame. Not part of
     * [status], which is read twice a second.
     */
    fun loudness(): Float = heardOn?.loudness() ?: 0f

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
        val carrying = command?.carrying().orEmpty()
        val excuses = command?.excuses().orEmpty()
        // The drawing's colours are whichever channel is holding the room just now, as on the
        // handset: the spatial channel's while playing, the standing channel's between songs.
        val colours = if (playing && stream != null) spatialServer?.places().orEmpty() else places
        // Between songs the drawing keeps whoever went (see [refreshRoom]), and not standing by is
        // the only way to have stopped: nobody is sent audio, and a line that closed is not quiet.
        val standing = if (playing && stream != null) null else command?.standingPeerIds().orEmpty().toSet()
        val silent = room.icons.map { it.peerId }
            .filter { it != selfId && (it in quiet || (onAudio != null && it !in onAudio) || (standing != null && it !in standing)) }
            .toSet()
        // By name, as the handset host matches them: the command channel keeps what each one said
        // under the name it gave, and one that never gave a name is left out rather than guessed.
        val saidNotExempt = command?.notExemptNames().orEmpty().toSet()
        HostStatus(
            room = room.copy(colours = colours, silentIds = silent),
            killedIds = silent.filter { command?.nameOf(it) in saidNotExempt }.toSet(),
            volumePercent = volume.percent,
            roomVolumePercent = roomVolume,
            volumeTouched = roomVolume != null || asked.isNotEmpty() || volume.percent != SoftwareVolume.FULL,
            capturing = capture?.takeIf { playing }?.app,
            capturePadded = capture?.takeIf { playing }?.padded(),
            heldDown = if (playing && capture != null) emptyList() else turnDown.held(),
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
                    askedPercent = asked[peerId],
                    carrying = carrying[peerId] ?: Carried.UNSAID,
                    excuse = excuses[peerId]
                )
            },
            sinksOnAudio = chunkServer?.clientCount() ?: 0,
            droppedChunks = chunkServer?.droppedChunks() ?: 0,
            localPlayed = stream?.playedLocally(),
            localLate = stream?.lateLocally(),
            jumps = stream?.jumps,
            localBand = stream?.localSeamBand(),
            localShares = stream?.localSeamShares(),
            problem = problem,
            measure = measure
        )
    }

    /**
     * What the code a handset scans says - this host's id, [address], and the audio port - or null
     * before the role is open. Spelled by core's codec, the one the handset host and the handset
     * that scans both use; which of this machine's addresses to put in it is the window's to ask.
     */
    fun pairingCode(address: String): String? = synchronized(lock) {
        val id = selfId?.takeIf { commandServer != null } ?: return null
        PairingCodeCodec.encode(PairingCode(id, address, ports.chunk))
    }

    /** One song - see the other [play]. */
    fun play(file: File, alsoHere: Boolean) = play(listOf(file), 0, alsoHere)

    /**
     * Starts the room playing [files] from [first] on, one after another, on a thread of its own
     * because opening the speakers must not happen on the window's. The handsets are told once
     * the stream exists - see [tell].
     */
    fun play(files: List<File>, first: Int, alsoHere: Boolean) = synchronized(lock) {
        if (files.isEmpty()) return
        val list = SongList(files, first, leadFrames = HostStream.DEFAULT_LEAD_NANOS * TonePcmSource.SAMPLE_RATE / 1_000_000_000L)
        startPlayer(SongFeed(list), files[first.coerceIn(0, files.size - 1)].name, alsoHere) {
            songs = list
            capture = null
        }
    }

    /**
     * Starts the room playing whatever [programs] - from [AudioSessions.list] - are playing, added
     * together, until stopped: the handset host's 抓取音频. The room is told it is [name]. Each
     * program is turned down in the mixer for as long as the room plays it, and put back at stop -
     * see [AppTurnDown].
     */
    fun playApps(programs: List<AudioSession>, name: String, alsoHere: Boolean) = synchronized(lock) {
        if (programs.isEmpty()) return
        playCaptured(AppFeed(programs, name, openCapture, turnDown), alsoHere)
    }

    /**
     * Starts the room playing everything this machine plays but SoundMesh, programs started later
     * included - 所有声音 - told it is [name]. Every row in the mixer is turned down while it lasts:
     * see [EverythingFeed].
     */
    fun playEverything(name: String, alsoHere: Boolean) = synchronized(lock) {
        playCaptured(EverythingFeed(name, ProcessHandle.current().pid(), openCapture, turnDown, mixer), alsoHere)
    }

    /** Under [lock]. */
    private fun playCaptured(feed: CapturedFeed, alsoHere: Boolean) {
        startPlayer(feed, feed.app, alsoHere) {
            songs = null
            capture = feed
        }
    }

    /** Under [lock]. [adopt] makes [feed] the one the window's controls act on. */
    private fun startPlayer(feed: Feed, name: String, alsoHere: Boolean, adopt: () -> Unit) {
        val chunks = chunkServer ?: return
        if (player?.isAlive == true) return
        // A round is recording the room, and music played now is what it would measure.
        if (measure.running) return
        problem = null
        this.file = name
        playing = true
        ended = false
        stopEchoUntilNanos = 0L
        val mine = ++generation
        adopt()
        player = Thread({ playOn(chunks, feed, alsoHere, mine) }, "host-play").apply {
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
    fun stopPlaying() {
        val stopping = synchronized(lock) {
            if (!playing && player == null) return
            sayStop()
            player
        }
        // Outside the lock: the player takes it on its way out, and waiting for it while holding
        // it made every stop wait the whole [JOIN_MILLIS] - with the window's status, which
        // takes the same lock, frozen for as long.
        stopping?.join(JOIN_MILLIS)
        synchronized(lock) {
            // Only if no newer play has started in the meantime; its player is its own.
            if (player === stopping) {
                player = null
                stream = null
            }
        }
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
        // A sound check says what was heard at the old level, which is no longer anybody's.
        noteMeasure { it.copy(soundCheck = null) }
    }

    /** One device on its own, for the one standing next to a wall. Answers whether the line to it was there. */
    fun setDeviceVolume(peerId: String, percent: Int): Boolean = synchronized(lock) {
        asked[peerId] = percent
        forgetCheckOf(peerId)
        commandServer?.sendTo(peerId, RoomOrder(RoomCommand.SET_VOLUME, percent)) == true
    }

    /** This machine's own sound on its own. */
    fun setOwnVolume(percent: Int) = synchronized(lock) {
        volume.set(percent)
        selfId?.let(::forgetCheckOf)
    }

    /** Takes the last sound check's word off one device whose volume has just moved. */
    private fun forgetCheckOf(peerId: String) = noteMeasure {
        it.copy(soundCheck = it.soundCheck?.let { check ->
            check.copy(heard = check.heard + peerId, excuses = check.excuses - peerId)
        }, checkTouched = it.soundCheck != null)
    }

    /**
     * 试音: every device standing by chirps once, or [aimedAt] alone with this machine, and the
     * ones this machine did not hear are named - the handset host's sound check. Files nothing.
     */
    fun soundCheck(aimedAt: String?) =
        startMeasuring(if (aimedAt == null) MeasureJob.ROOM else MeasureJob.PAIR, aimedAt, check = true)

    /** Every device back to where it was before the room touched it, this machine included. */
    fun restoreVolume() = synchronized(lock) {
        roomVolume = null
        asked.clear()
        volume.restore()
        commandServer?.send(RoomCommand.RESTORE_VOLUME)
    }

    /** 位置同步校准: every device standing by measured in one window, this machine among them. */
    fun measureRoom() = startMeasuring(MeasureJob.ROOM, null)

    /** One device's constant, measured against this machine - the handset host's per-row calibrate. */
    fun measurePair(peerId: String) = startMeasuring(MeasureJob.PAIR, peerId)

    /**
     * Ends the round in flight on every device in it, or does nothing when none is running. Said
     * in the round's own words at once, as the handset's stop button does.
     */
    fun callOffMeasuring() {
        if (!measure.running) return
        callingOff = true
        noteMeasure {
            it.copy(line = if (it.job == MeasureJob.ROOM) RoundLine.RoomCalledOffHere else RoundLine.Stopping, untilLocalNanos = null)
        }
        runCatching { roundInFlight?.callOff() }
    }

    /**
     * Starts a round on a thread of its own, as the handset's calibration screen does - one at a
     * time, since two would share the microphone and the ports.
     *
     * The microphone is asked first, as the desktop sink asks it: a round that cannot record is
     * one where every device is told to chirp for a host that will hear none of it.
     */
    private fun startMeasuring(job: MeasureJob, aimedAt: String?, check: Boolean = false) {
        val command: RoomCommandServer
        val self: String
        synchronized(lock) {
            command = commandServer ?: return
            self = selfId ?: return
            if (measure.running) return
            callingOff = false
            noteMeasure {
                it.copy(
                    running = true,
                    job = job,
                    aimedAt = aimedAt,
                    // Each device's lines are about the job that said them: a room's excuses are
                    // not a pair's answer. Pairs keep each other's, as the handset's pair screen
                    // does - two devices measured in a row are two answers worth comparing.
                    heard = if (it.job == job) it.heard else emptyList(),
                    underWay = false,
                    line = RoundLine.Waiting,
                    untilLocalNanos = null,
                    microphone = null,
                    microphoneDetail = null,
                    // A sound check measures nothing, so it takes away no answer a round left.
                    roomMeasured = check && it.roomMeasured,
                    checking = check,
                    soundCheck = if (check) null else it.soundCheck,
                    checkFailed = null,
                    checkTouched = !check && it.checkTouched,
                    pairServed = if (check) it.pairServed && it.aimedAt == aimedAt else false
                )
            }
        }
        Thread({ measureOn(job, aimedAt, command, self, check) }, "host-measure").apply {
            isDaemon = true
            start()
        }
    }

    private fun measureOn(job: MeasureJob, aimedAt: String?, command: RoomCommandServer, self: String, check: Boolean) {
        try {
            microphoneProblem()?.let { (trouble, detail) ->
                noteMeasure { it.copy(line = null, microphone = trouble, microphoneDetail = detail) }
                return
            }
            // A chirp measured through music measures the music - the handset host's
            // hushWhateverIsPlaying. Not started again afterwards, as on the handset.
            stopPlaying()
            lendSpatialPort()
            val round = HostRound(
                filesDir = identityDirectory,
                hostId = self,
                commands = StandingLine(command),
                dials = RoundDials(
                    clock = ports.clock,
                    result = ports.result,
                    plan = ports.plan,
                    room = ports.room,
                    command = ports.command
                ),
                // Served for as long as this machine is the host; see the class comment.
                servesClock = false,
                // A room is measured with everything where it plays and the person at the computer,
                // so its distances are the listener's too. A pair is not: its instruction brings the
                // device to within arm's length of this machine, and filed as the listener's, one
                // pairing would seat the listener beside that device. Filed as the handset host's
                // roster pair files it, a separation and nothing more.
                place = if (job == MeasureJob.ROOM) HostPlace.LISTENING else HostPlace.PLAYING,
                timingFor = timingFor,
                calledOff = { callingOff },
                report = object : HostRoundReport {
                    override fun say(line: RoundLine, untilLocalNanos: Long?) =
                        noteMeasure { it.copy(line = line, untilLocalNanos = untilLocalNanos) }

                    // The line that described the work does not survive the work, as on the
                    // handset: a device's answer replaces it (PeerCalibrateActivity.record).
                    override fun heard(peerId: String, line: RoundLine) = noteMeasure {
                        it.copy(
                            line = null,
                            untilLocalNanos = null,
                            heard = it.heard.filterNot { (id, _) -> id == peerId } + (peerId to line)
                        )
                    }

                    override fun forgetHeard() = noteMeasure { it.copy(heard = emptyList()) }

                    override fun underWay() = noteMeasure { it.copy(underWay = true) }

                    override fun measured(peerIds: List<String>) {}
                },
                keepsRecording = keepsRecordings(),
                recorder = { store, caseId, hostNanosNow, _ -> roundRecorder(store, caseId, hostNanosNow) },
                speaker = { _, hostNanosNow ->
                    roundSpeaker?.invoke(hostNanosNow) ?: FrameRoundSpeaker(openSpeakers, volume, hostNanosNow)
                }
            )
            roundInFlight = round
            // Called off while the microphone was being asked or the music stopped: nothing has
            // been said to the room yet, so there is nothing to take back.
            if (callingOff) return
            if (check) {
                val heard = round.soundCheck(aimedAt)
                // The round's own lines were about gathering a room nobody asked to measure; the
                // answer is on the rows. A check that never chirped keeps the line saying why.
                noteMeasure {
                    it.copy(soundCheck = heard, checkFailed = if (heard == null) it.line else null, line = null, heard = emptyList())
                }
                return
            }
            when (job) {
                MeasureJob.ROOM -> if (round.room()) noteMeasure { it.copy(roomMeasured = true) }
                MeasureJob.PAIR -> if (round.pair(aimedAt) == RoundResult.SERVED) noteMeasure { it.copy(pairServed = true) }
            }
        } catch (e: Throwable) {
            noteMeasure {
                val failed = RoundLine.Failed(e.message ?: e.toString())
                if (check) it.copy(checkFailed = failed, line = null) else it.copy(line = failed)
            }
        } finally {
            roundInFlight = null
            takeBackSpatialPort()
            noteMeasure { it.copy(running = false, underWay = false, untilLocalNanos = null, checking = false) }
            synchronized(lock) { rereadDistances() }
        }
    }

    /**
     * Hands the spatial port to the round's plan server, which dials the same number: a handset
     * sink asks for its plan on RoundPorts.PLAN, and that is SinkStream.SPATIAL_PORT, 45126. The
     * handset host never meets this - its spatial server lives only while a session plays, and a
     * round cannot run beside one - while this machine holds its for as long as it is the host.
     * Nothing is playing by now, so nobody is on it.
     *
     * Waited for until the port can be bound again, not only until close returns: the JDK hands a
     * socket closed under a thread blocked in accept to that thread to close, and until it has,
     * the port is still listening (09-20).
     */
    private fun lendSpatialPort() {
        val lent = synchronized(lock) {
            val spatial = spatialServer ?: return
            spatialServer = null
            spatial
        }
        lent.stop()
        awaitPortFree(ports.spatial)
    }

    /** The spatial server back on its port once the round has let go of it, while still the host. */
    private fun takeBackSpatialPort() {
        // Never lent - a round that stopped at the microphone - or no longer the host.
        if (synchronized(lock) { spatialServer != null || commandServer == null }) return
        awaitPortFree(ports.spatial)
        synchronized(lock) {
            val command = commandServer ?: return
            val self = selfId ?: return
            if (spatialServer != null) return
            val spatial = SpatialFieldServer(ports.spatial, self, command.places())
            try {
                spatial.start()
            } catch (_: BindException) {
                problem = HostProblem.PortTaken(HostPort.SPATIAL, ports.spatial)
                return
            }
            spatialServer = spatial
            publishRoom()
        }
    }

    /** Waits, a bounded while, until nothing is listening on [port]. */
    private fun awaitPortFree(port: Int) {
        val until = System.nanoTime() + PORT_FREE_WAIT_NANOS
        while (System.nanoTime() < until) {
            try {
                java.net.ServerSocket(port).close()
                return
            } catch (_: BindException) {
                Thread.sleep(PORT_POLL_MILLIS)
            }
        }
    }

    private fun noteMeasure(change: (MeasureStatus) -> MeasureStatus) = synchronized(measureLock) {
        measure = change(measure)
    }

    /** This host's standing line, as the round asks for it. */
    private class StandingLine(private val server: RoomCommandServer) : HostCommands {
        override fun send(command: RoomCommand): Int = server.send(command)

        override fun sendTo(peerId: String, order: RoomOrder): Boolean = server.sendTo(peerId, order)

        override fun standingBy(): Int = server.standingBy()

        override fun forgetExcuses() = server.forgetExcuses()

        override fun listenForExcuses(listener: ((String, RoomExcuse) -> Unit)?) {
            server.onExcuse = listener
        }
    }

    /** Sets the room to one of the four effects - the handset host's effect list. */
    fun setEffect(kind: EffectKind) = updateRoom { it.withEffect(kind) }

    /** 房间回声, 0 to 1. */
    fun setReverb(amount: Float) = updateRoom { it.copy(reverb = amount) }

    /** 包裹感: how much each device keeps when a turning source faces away from it. */
    fun setEnvelopment(amount: Float) = updateRoom { it.copy(envelopment = amount) }

    /** How long one circuit of 旋转 takes. */
    fun setSpinSeconds(seconds: Int) = updateRoom { it.copy(periodSeconds = seconds) }

    /**
     * One device dragged somewhere else on the drawing - a person saying where it is, which is a
     * fresh opinion for the fit to answer, as the handset's withIconMoved says.
     */
    fun moveIcon(icon: RoomIcon) = updateRoom { room ->
        room.copy(
            icons = room.icons.map { if (it.peerId == icon.peerId) SpatialRoom.clamped(icon) else it },
            fitted = false
        )
    }

    /** 吸附到实测位置: the drawing moved onto what was measured, when there is anything to offer. */
    fun fitRoom() = updateRoom { room ->
        fitOffer(room)?.let { room.copy(icons = it.icons, metresPerUnit = it.metresPerUnit, fitted = true) } ?: room
    }

    /** Whether the near devices are held back for the far ones - the handset's switch beside the delays. */
    fun setDelayCompensation(on: Boolean) = updateRoom { it.copy(delayCompensation = on) }

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
     * first when it plays aloud; between songs, this machine, whoever is already drawn and whoever
     * stands by (the handset host's betweenSessionsRoster). One that goes between songs stays,
     * hollow, as on the handset host: that is when a phone's own power saving stops it, and a
     * drawing that dropped it would have nothing left to show it on. A device that comes and goes
     * keeps where it was drawn.
     */
    private fun refreshRoom() {
        val self = selfId ?: return
        val spatial = spatialServer ?: return
        val roster =
            if (playing && stream != null) listOfNotNull(self.takeIf { playingHere }) + spatial.peerIds()
            else (listOf(self) + room.icons.map { it.peerId } + (commandServer?.standingPeerIds() ?: emptyList())).distinct()
        if (roster.distinct() == room.icons.map { it.peerId }) return
        // Somebody drawn for the first time lands where nothing measured put them. Devices that
        // come and go keep where they were - which on this machine is every start and stop of a
        // song, the roster switching between who stands by and who takes the audio - and a fit
        // taken is still true of them.
        val newcomer = roster.any { it != self && it !in whereTheyWere && room.icons.none { icon -> icon.peerId == it } }
        whereTheyWere.putAll(room.icons.associateBy { it.peerId })
        room = room.copy(
            icons = SpatialRoom.reconciled(room.icons, roster, whereTheyWere),
            selfId = self,
            otherHalfIds = sidesTheyCarried.reconciled(room.otherHalfIds, before = room.icons.map { it.peerId }, after = roster),
            // Off disk only when the roster changed, as the handset host reads them: this runs five
            // times a second. A new device is a fresh reason to offer the fit; the scale stays,
            // since somebody joining does not change how big the room is.
            measuredMetres = measuredDistances(identityDirectory, self, roster),
            listenerMetres = listenerMetres(self),
            fitted = room.fitted && !newcomer
        )
        publishRoom()
    }

    /**
     * The distances brought up to date after a round, the handset host's rereadDistances. Under
     * [lock]. A fresh measurement is a fresh room, so the old scale goes with it; a reading that
     * came back the same is dropped, so a fit somebody already took is not offered again.
     */
    private fun rereadDistances() {
        val self = selfId ?: return
        val measured = measuredDistances(identityDirectory, self, room.icons.map { it.peerId })
        val listener = listenerMetres(self)
        if (measured == room.measuredMetres && listener == room.listenerMetres) return
        room = room.copy(measuredMetres = measured, listenerMetres = listener, fitted = false, metresPerUnit = 0.0)
        publishRoom()
    }

    /**
     * How far the listener is from each device: what this machine's rounds measured to each of
     * them, and [LISTENER_METRES] to this machine itself - which a round cannot measure, since it is
     * the machine doing the listening. Nothing at all until something has been measured, so an
     * unmeasured room is not told the listener sits beside a computer and nowhere else.
     */
    private fun listenerMetres(self: String): Map<String, Double> {
        val measured = StoredListenerDistance.all(identityDirectory)
        return if (measured.isEmpty()) measured else measured + (self to LISTENER_METRES)
    }

    /**
     * Writes the drawing down when this machine stops being the host, by the handset's file and
     * rules (StoredRoomDrawing): where every device was put, including ones not here just now,
     * and how the rule was set. Under [lock].
     *
     * On leaving rather than on every change, as the handset writes it on pausing: a drag changes
     * the room on every move of the mouse. What this does not cover is the process dying without
     * closing, the same trade the handset makes.
     */
    private fun keepTheDrawing() {
        val placed = LinkedHashMap(whereTheyWere)
        for (icon in room.icons) placed[icon.peerId] = icon
        val sides = sidesTheyCarried.toKeep(room.otherHalfIds, room.icons.map { it.peerId })
        drawing.write(placed.values.toList(), room.copy(otherHalfIds = sides))
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

    private fun playOn(chunks: ChunkServer, feed: Feed, alsoHere: Boolean, mine: Int) {
        // Everything this thread says about the room is said only while it is still the current
        // press of play: after that, [playing] and [problem] belong to the next one.
        fun current() = generation == mine
        try {
            // A list with nothing playable in it, or a program that cannot be heard, is said
            // before anybody is told to play.
            feed.prepare()?.let {
                if (current()) {
                    problem = it
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
                heardOn = speakers
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
                val events = EventLog(identityDirectory)
                // How much older than the capture each chunk goes out, on top of the lead - see [CaptureFeed].
                val backlog = LeadMeter("capture-backlog")
                val hostStream = HostStream(
                    ports.chunk,
                    { _, _ ->
                        val pcm = feed.nextChunk()
                        if (feed is CapturedFeed) backlog.record(feed.backlogNanos(), System.nanoTime())?.let { events.write(it) }
                        val name = feed.name()
                        if (name != null && name != announced) {
                            announced = name
                            file = name
                            synchronized(lock) { spatialServer?.publishNowPlaying(name) }
                        }
                        pcm
                    },
                    localOutput = speakers?.output?.let { GainOutput(it, volume) },
                    chunkServer = chunks,
                    localShape = { chunk -> here?.shaped(chunk) ?: chunk },
                    events = events
                )
                if (current()) {
                    streamSinceNanos = System.nanoTime()
                    stream = hostStream
                }
                // Once through the list and no more, the way the handset host plays a folder: one
                // with no end is one a person can only stop.
                val lastPlayAt = hostStream.streamWhile { playing && current() && feed.hasMore() }
                if (lastPlayAt != null && playing && current()) {
                    // Every chunk is stamped a lead into its own future, so the song is over only
                    // once the last one has been heard - the handset host's endOfSong. Stopping at
                    // the last send would cut the final second off every song.
                    val over = lastPlayAt + HostStream.CHUNK_NANOS
                    while (playing && current() && System.nanoTime() - over < 0) Thread.sleep(END_POLL_MILLIS)
                    if (playing && current()) endOfSong(mine)
                }
            } finally {
                // Straight away rather than after a tail: stop is a person asking for quiet, and the
                // handsets are stopping at the same moment.
                if (heardOn === speakers) heardOn = null
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
        } finally {
            // Whatever the way out: a program left turned down is the one thing here that outlives
            // this process.
            runCatching { feed.close() }
        }
    }

    private fun tellWhileOpen() {
        var putBackAt = 0L
        while (!Thread.currentThread().isInterrupted) {
            tell()
            synchronized(lock) { refreshRoom() }
            echoStop()
            if (System.nanoTime() - putBackAt >= PUT_BACK_EVERY_NANOS) {
                putBackAt = System.nanoTime()
                putBackWhileIdle()
            }
            try {
                Thread.sleep(retellMillis)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * A program that quit while the room played it could not be put back then, and Windows gives
     * it the level it was left at when it runs again: it is put back as soon as it does, rather
     * than the next time this machine is host. Under [lock], and only while nothing plays, because
     * a play turns its programs down on its own thread once it has started, and a put back
     * between the two would undo that.
     */
    private fun putBackWhileIdle() = synchronized(lock) {
        if (!playing) runCatching { turnDown.putBack() }
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

        /** How soon a program left turned down is put back once it runs again. Picked. */
        private const val PUT_BACK_EVERY_NANOS = 1_000_000_000L

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

        /**
         * How far the person at the computer is taken to be from its speakers, in metres.
         *
         * Guessed, not measured: somebody sitting at a laptop, its speakers about an arm's length
         * away (09-24, the user's choice). A round cannot measure it - the computer is the one
         * listening. What it moves is how far this machine's own sound is held back for the far
         * devices, and how much it is turned down as the nearest: half a metre wrong is about
         * 1.5 ms.
         */
        const val LISTENER_METRES = 0.5

        /** The most a round waits for the spatial port to come free, either way. Picked, not measured. */
        private const val PORT_FREE_WAIT_NANOS = 3_000_000_000L

        private const val PORT_POLL_MILLIS = 20L
    }
}
