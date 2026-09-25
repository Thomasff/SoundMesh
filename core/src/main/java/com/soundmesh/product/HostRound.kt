package com.soundmesh.product

import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.AlignmentPairing
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationReply
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.CalibrationUpdate
import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.HostId
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.RoomOrder
import com.soundmesh.core.RoomReply
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.AlignmentResultServer
import com.soundmesh.probe.sync.CalibrationPlanServer
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.EventLog
import com.soundmesh.probe.sync.PeerCalibrationRun
import com.soundmesh.probe.sync.PeerCalibrationRunner
import com.soundmesh.probe.sync.PeerRunLog
import com.soundmesh.probe.sync.RoomResultServer
import com.soundmesh.probe.sync.RoundRecorder
import com.soundmesh.probe.sync.RoundSpeaker
import com.soundmesh.probe.sync.StoredListenerDistance
import com.soundmesh.probe.sync.StoredRoomField
import com.soundmesh.probe.sync.StoredSeparation
import java.io.File
import java.util.Collections
import java.util.Locale

/** What one round of serving one sink came to, for the timeline that records it. */
enum class RoundResult {
    /** A handset was measured. Whatever verdict it got, the round did its job. */
    SERVED,

    /** The wait for somebody to ask ran out. */
    NOBODY_ASKED,

    /** This round broke. */
    FAILED
}

/**
 * What a sound check heard. [tookPart] is every device the schedule named, host last; [heard] the
 * ones this host's recording carries; [excuses] who said why they would not take part.
 *
 * A device on the room's line that is in none of these was told and never came - it made no sound,
 * so it was not heard either, which is what the screen says about it.
 */
data class SoundCheck(
    val tookPart: List<String>,
    val heard: Set<String>,
    val excuses: Map<String, RoomExcuse>
)

/**
 * Which slots one pass of a room was heard in, by the gate a measurement reads them with.
 *
 * Nothing unless [ownSlot] itself was heard. Every other slot is looked for a fixed distance from
 * where this device's own chirp landed, so without that anchor a window can sit on noise, the
 * previous slot's tail, or anything at all; the one thing that can honestly be said is that this
 * device did not hear itself.
 */
fun heardSlots(arrivals: List<ChirpArrival?>, ownSlot: Int): Set<Int> {
    fun audible(arrival: ChirpArrival?) =
        arrival != null && arrival.ratio >= ChirpCorrelator.MIN_TRUSTWORTHY_RATIO && !arrival.atSearchEdge
    if (!audible(arrivals.getOrNull(ownSlot))) return emptySet()
    return arrivals.indices.filter { audible(arrivals[it]) }.toSet()
}

/**
 * What a sound check says against each of [judged]: its own reason where it gave one, [notHeard]
 * where this host did not hear it - including one told that never came, which made no sound - and
 * nothing where it was heard.
 */
fun unheardLines(
    check: SoundCheck,
    judged: List<String>,
    excuse: (RoomExcuse) -> String,
    notHeard: String
): Map<String, String> = judged.mapNotNull { peerId ->
    val said = check.excuses[peerId]
    when {
        said != null -> peerId to excuse(said)
        peerId !in check.heard -> peerId to notHeard
        else -> null
    }
}.toMap()

/**
 * Where the host stands for a round, which is what decides where each distance it is an end of is
 * filed. It changes nothing about the measurement.
 */
enum class HostPlace {
    /** Where it plays from: its distances are separations. A handset host's second step. */
    PLAYING,

    /**
     * Held above somebody's head, not standing where it will play: the distances this host is an
     * end of are the listener's, and the ones it is not an end of are ordinary separations. Filed
     * together they would be the same two names meaning two different things. A handset host's
     * first step.
     */
    OVERHEAD,

    /**
     * Where it plays from, and where the listener sits as well - a desktop host, with somebody at
     * the computer. Its distances are separations and the listener's at once, so they are filed as
     * both: the one arrangement where a round measures the listener without a step of its own.
     */
    LISTENING
}

/**
 * The standing line a host round tells the room over, whichever object holds it on this platform.
 *
 * A handset holds its one command server in a process-wide object so it outlives the home screen;
 * a desktop host holds it for as long as the role. The round needs the same six things from either.
 */
interface HostCommands {
    /** Says [command] to everybody standing by, and answers how many it reached. */
    fun send(command: RoomCommand): Int

    fun sendTo(peerId: String, order: RoomOrder): Boolean

    fun standingBy(): Int

    fun forgetExcuses()

    /** Who to tell when a handset says why it is not measuring, or null for nobody. */
    fun listenForExcuses(listener: ((String, RoomExcuse) -> Unit)?)
}

/**
 * Where a host round's words go.
 *
 * The round names the sentence and the caller spells it, as with [SinkRoundReport]. [heard] is kept
 * apart from [say] because they are two kinds of thing on the screen: one line about where the round
 * is, which every later line replaces, and one line per handset, which stays.
 */
interface HostRoundReport {
    /** One line about where the round is; [untilLocalNanos] is on [System.nanoTime]'s scale. */
    fun say(line: RoundLine, untilLocalNanos: Long? = null)

    /** What one handset's part of the round came to, replacing what it said last time. */
    fun heard(peerId: String, line: RoundLine)

    /** The per-handset lines of the last round, which are about that round, are done with. */
    fun forgetHeard()

    /** The plan is out: stopping now silences the room, rather than refusing to answer it. */
    fun underWay()

    /** A room was measured, with everybody who took part in it. */
    fun measured(peerIds: List<String>)
}

/**
 * One round on the host's side: a room gathered and measured, or one sink served a pair.
 *
 * Moved here from `PeerCalibrateActivity` on 09-24, for the reason the sink's half moved on 09-23: a
 * desktop host runs this same round rather than a copy of it. What a platform hands in is only what
 * differs - where the files live, the standing line, whether the clock is already being served, the
 * speaker and the recorder, where this host is standing, and how its sentences are spelled.
 *
 * The caller owns the thread, the radio hold and the busy flag. [room] and [pair] block for the
 * length of the round, which is a minute or two. One instance is one round.
 */
class HostRound(
    /** Where this device keeps its identity, its distances and its runs. */
    private val filesDir: File,
    private val hostId: String,
    private val commands: HostCommands,
    private val dials: RoundDials = RoundDials(),
    /**
     * Whether this round opens the clock port itself.
     *
     * A handset host opens it per round, because the session that also wants it cannot run at the
     * same time. A desktop host serves the clock for as long as it is the host, so the port is
     * already answering and a second server on it would fail to bind.
     */
    private val servesClock: Boolean = true,
    private val place: HostPlace,
    /** The arm's own schedule, with anything a command line asked for on top. */
    private val timingFor: (String?) -> ArmSchedule = ::defaultTimingFor,
    /** Whether the stop button has been pressed since the round started. */
    private val calledOff: () -> Boolean,
    private val report: HostRoundReport,
    /** Whether the recording stays once it has been read. See [PeerCalibrationRunner]. */
    private val keepsRecording: Boolean,
    /** The capture path's name, which the [recorder] reads. */
    private val audioSource: String = "MIC",
    /** Where the lines a person reading the device's own log would want go. */
    private val log: (String) -> Unit = {},
    /** The recording, given the run store, the case, host time and the capture path's name. */
    private val recorder: (RunStore, String, () -> Long, String) -> RoundRecorder,
    /** The output, given the clock offset in force and host time. */
    private val speaker: (offsetNanosNow: () -> Long, hostNanosNow: () -> Long) -> RoundSpeaker
) {
    /** One timeline, shared with every other part of the app. See [EventLog]. */
    private val events: EventLog by lazy { EventLog(filesDir) }

    /**
     * The plan server of the round in flight, or null when none is serving.
     *
     * Held here only so [callOff] can reach it. The round spends most of its life parked in that
     * server's accept(), and closing the socket is the only thing that wakes it - without which the
     * button would take up to five minutes to have any visible effect, which is indistinguishable
     * from a button that does not work.
     */
    @Volatile private var planServer: CalibrationPlanServer? = null

    /**
     * The schedule this round is running, set the moment its case is known and read everywhere
     * afterwards - including by the report, so a run says which arm it was on rather than what the
     * command line happened to ask for.
     */
    @Volatile private var timing = defaultTimingFor(null)

    /**
     * Ends the round in flight, on every handset in it.
     *
     * Two places have to hear the press, because a round has two halves and neither one can speak
     * for the other: handsets still waiting for a schedule are turned away by the plan server, and
     * handsets already chirping are told over the standing channel, which nothing about a round ever
     * made them leave. The caller sets what [calledOff] reads first, so this round sees it too.
     */
    fun callOff() {
        // Says so to the handsets already waiting, instead of closing their sockets under them -
        // which is the 09-13 message word for word. See [CalibrationPlanServer]. Both jobs now:
        // the pair used to close the socket alone, which left the one handset on the other end
        // of it reading a connection failure for a button somebody pressed on purpose.
        runCatching { planServer?.callOffRoom() }
        // And to everybody already chirping, who are past asking this host for anything. Said to
        // the whole standing room rather than to one named handset: a handset that is not in a
        // round has nothing to call off and does nothing with it, and a pair round started from
        // a screen's own button does not learn the other handset's name until it asks.
        runCatching { commands.send(RoomCommand.CALL_OFF) }
    }

    /**
     * The host's part of a pair: serve the clock, mint the plan when asked, play, and combine the
     * two halves.
     *
     * It stores no constant. The correction belongs to the handset that applies it, and this one
     * does not - what this produces is the answer the sink is waiting for on the socket it delivered
     * on. [aimedAt] is the one handset this round is for, or null to serve whichever sink asks.
     */
    fun pair(aimedAt: String?): RoundResult {
        val clockServer = if (servesClock) ClockSyncServer(dials.clock) else null
        val resultServer = AlignmentResultServer(dials.result)
        val planServer = CalibrationPlanServer(dials.plan)
        // Reachable from the stop button, which ends the wait by closing the socket this thread is
        // parked in accept() on. Without that the button does nothing visible for five minutes.
        this.planServer = planServer
        try {
            clockServer?.start()
            resultServer.start()
            planServer.start()
            // Said out loud at both ends, because these three are the same ports a session wants
            // and the second feature to ask is refused with nothing but a Java class name. A user
            // hit exactly that: calibration finished at 21:35 and a session still could not bind
            // at 21:38, and there was no way afterwards to tell whether the stop button had been
            // pressed at all. These two lines make the next occurrence answerable.
            log("the calibration servers are up: clock ${dials.clock}, result ${dials.result}, plan ${dials.plan}")
            // After the three servers are up and not before, which is the whole of the ordering
            // problem this replaces: the handset being told goes straight for the plan port, and
            // a sink that got there first found nothing listening and gave up.
            aimedAt?.let { peerId ->
                val reached = commands.sendTo(peerId, RoomOrder(RoomCommand.MEASURE_PAIR))
                events.write("pair-told $peerId, reached=$reached")
                if (!reached) report.say(RoundLine.AimedGone)
            }
            // One round, one handset. It used to serve a queue - press once, then walk from phone
            // to phone pressing start on each - which is not what the chip on a roster line means:
            // that chip names one handset, and it is the handset this round is with. What the
            // queue cost was a host that never stopped on its own, waiting five minutes for a
            // second volunteer that nothing on screen had asked for.
            return serveOneSink(aimedAt, planServer, resultServer).also { events.write("pair-round $it") }
        } finally {
            this.planServer = null
            planServer.stop()
            resultServer.stop()
            clockServer?.stop()
            log("the calibration servers are down; the clock port is free again")
        }
    }

    /**
     * The room's part: gather everybody, hand out one schedule, chirp in this host's own slot, then
     * combine every pair out of what they all heard. Answers whether a room was measured.
     *
     * Not [pair] with more handsets in it. That one serves one sink - it asks, gets a plan naming
     * the two of them, and is answered. A room's schedule names every slot, so it does not exist
     * until everybody has asked; and the pair between two sinks is made of two deliveries that
     * neither of them can combine.
     */
    fun room(): Boolean = gathered(aimedAt = null, oneChirp = false) { gathering ->
        val plan = gathering.plan
        val ownSlot = gathering.ownSlot
        val run = gathering.run
        val roomServer = gathering.roomServer
        // Written before anything is combined, so a room that loses everybody still leaves
        // this handset's own hearing of it on disk.
        File(RunStore(filesDir).prepareRun(plan.caseId), hostArtifact(hostId)).writeText(run.json)
        fileAttempt("HOST-${plan.caseId}-$hostId", run.json)
        log(run.json)
        // Keyed by slot throughout: the analysis speaks slots, and the one place a slot
        // becomes a name again is roomField.
        val heard = LinkedHashMap<Int, List<List<ChirpArrival?>>>()
        heard[ownSlot] = run.arrivalsByRepeat
        val heardFrom = ArrayList<String>()
        var field = RoomField(emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0)
        roomServer.awaitRoom(plan.slotIds.size - 1, ROOM_RESULT_TIMEOUT_MILLIS) { delivered ->
            for (message in delivered) {
                val slot = plan.slotIds.indexOf(message.senderId)
                // A delivery from a handset this plan never named, or one that read its
                // window against a slot other than the one it was given, is a hearing of a
                // different room. Combining it puts a real pair on the wrong two phones.
                if (slot < 0 || slot != message.ownSlot) continue
                heard[slot] = message.arrivalsByRepeat
                heardFrom += message.senderId
            }
            field = roomField(plan, heard, AlignmentAnalysis.DISTANCE_EDGE_SHARES)
            delivered.associate {
                it.senderId to RoomReply(
                    plan.slotIds.size,
                    pairsReadableFor(field, it.senderId),
                    // Offered to everybody who delivered, because the host cannot tell who
                    // needs it: the constant lives on the handset that applies it, and the
                    // channel where a handset says what it carries is closed for the whole
                    // of a round. The receiver keeps it only if it has nothing better.
                    offeredTo(field, hostId, it.senderId)
                )
            }
        }
        fileAttempt(
            "HOST-${plan.caseId}-ROOM",
            roomReportJson(plan, field, heardFrom, timing.planLeadNanos)
        )
        // The whole field, which is what the room screen reads to check a drawing against.
        // The per-peer files below are the same distances for the pairs this handset is an
        // end of; this is the only place the rest of them have ever had.
        val toStore = roomFieldToStore(field.separationMetres, hostId, place == HostPlace.OVERHEAD)
        if (saysSomethingAboutTheRoom(toStore)) {
            runCatching { StoredRoomField(filesDir).write(toStore) }
            events.write("room-field written, ${toStore.count { it.value != null }} pairs")
        } else {
            events.write("room-field kept: this round measured no distance between handsets")
        }
        // Read, not yet used. The room measured how far apart every pair fires at the same
        // instant it measured how far apart they stand, and if that number is good enough
        // it replaces a minute per handset of somebody walking to each one. Whether it is
        // good enough is a comparison against constants measured the long way, so the first
        // thing it has to do is be readable afterwards.
        for (entry in field.alignmentErrorMs) {
            val millis = entry.value ?: continue
            events.write(
                "room-firing ${entry.key.first} ${entry.key.second} " +
                    String.format(Locale.US, "%.3f", millis) + "ms"
            )
        }
        for (entry in field.edgeFiringOffsetMs) {
            val millis = entry.value ?: continue
            events.write(
                "room-firing-edge ${entry.key.first} ${entry.key.second} " +
                    String.format(Locale.US, "%.3f", millis) + "ms"
            )
        }
        // And what was actually handed out, which is a different list: a pair can be readable
        // and still be refused here, and a refusal that leaves no trace is a fix that looks
        // like a feature that was never built.
        for (entry in field.offerableOffsetMicros) {
            events.write(
                "room-offer ${entry.key.first} ${entry.key.second} " + (entry.value?.let {
                    String.format(Locale.US, "%.3f", it / 1000.0) + "ms"
                } ?: "refused: the repeats of this pair did not agree closely enough")
            )
        }
        // And the per-peer files, which is where every other arm writes a distance and
        // where the pair flow reads one. What may be kept is what [separationToStore] would
        // keep - a room always sweeps the thresholds, so the narrower rule and the wider one
        // are the same rule here.
        for (entry in field.separationMetres) {
            val peer = when (hostId) {
                entry.key.first -> entry.key.second
                entry.key.second -> entry.key.first
                else -> continue
            }
            val metres = entry.value ?: continue
            runCatching {
                if (place != HostPlace.PLAYING) StoredListenerDistance(filesDir, peer).write(metres)
                if (place != HostPlace.OVERHEAD) StoredSeparation(filesDir, peer).write(metres)
            }
        }
        report.say(
            RoundLine.RoomDone(
                plan.slotIds.size,
                field.separationMetres.count { it.value != null },
                field.separationMetres.size
            )
        )
        report.measured(plan.slotIds)
        true
    } ?: false

    /**
     * Whether each device can be heard here, which is all a sound check asks: the same gathering
     * as [room], one chirp each, and nothing filed. [aimedAt] checks that one device alone.
     *
     * Judged on this host's own recording only, by the same gate a measurement reads - so a device
     * called heard here is one a round would read from here. Every device in a room also has to
     * hear every other one, and that this does not ask. Null when no plan went out.
     *
     * The sinks run an ordinary room round and cannot tell the difference; what keeps them from
     * filing anything is the answer they get, which offers no constant.
     */
    fun soundCheck(aimedAt: String? = null): SoundCheck? = gathered(aimedAt, oneChirp = true) { gathering ->
        val plan = gathering.plan
        // Waited for, so every sink ends its round on an answer rather than a refused socket. The
        // count it is told is true of this round, since it is shown on that sink's screen; the
        // constant is never offered, so nobody keeps anything from one chirp.
        gathering.roomServer.awaitRoom(plan.slotIds.size - 1, ROOM_RESULT_TIMEOUT_MILLIS) { delivered ->
            val all = LinkedHashMap<Int, List<List<ChirpArrival?>>>()
            all[gathering.ownSlot] = gathering.run.arrivalsByRepeat
            for (message in delivered) {
                val slot = plan.slotIds.indexOf(message.senderId)
                if (slot >= 0 && slot == message.ownSlot) all[slot] = message.arrivalsByRepeat
            }
            val field = roomField(plan, all, AlignmentAnalysis.DISTANCE_EDGE_SHARES)
            delivered.associate {
                it.senderId to RoomReply(plan.slotIds.size, pairsReadableFor(field, it.senderId), null)
            }
        }
        val heard = heardSlots(gathering.run.arrivalsByRepeat.firstOrNull().orEmpty(), gathering.ownSlot)
            .map { plan.slotIds[it] }.toSet()
        events.write("sound-check heard ${heard.size} of ${plan.slotIds.size}: ${plan.slotIds.filter { it !in heard }.joinToString(" ").ifEmpty { "nobody missed" }}")
        SoundCheck(plan.slotIds, heard, LinkedHashMap(gathering.excuses))
    }

    /** What [gathered] hands on: the plan, this host's slot and hearing, and who said why not. */
    private class Gathering(
        val plan: CalibrationPlan,
        val ownSlot: Int,
        val run: PeerCalibrationRun,
        val roomServer: RoomResultServer,
        val excuses: Map<String, RoomExcuse>
    )

    /**
     * The half of a room round that [room] and [soundCheck] share: tell the room, gather it, hand
     * out one schedule and chirp in this host's own slot. [measure] is what is made of it, on the
     * servers still open; null when no plan went out or the round was called off.
     */
    private fun <T> gathered(aimedAt: String?, oneChirp: Boolean, measure: (Gathering) -> T): T? {
        val clockServer = if (servesClock) ClockSyncServer(dials.clock) else null
        val roomServer = RoomResultServer(dials.room)
        val planServer = CalibrationPlanServer(dials.plan)
        // Reachable from the stop button, which ends the wait by closing the socket this
        // thread is parked in accept() on.
        this.planServer = planServer
        try {
            clockServer?.start()
            roomServer.start()
            planServer.start()
            // Every server is bound, so this is the first instant a handset dialling in would be
            // answered rather than refused: CalibrationPlanClient opens a socket and throws if
            // nothing is listening, it does not retry. So the room is told from here and not from
            // the screen that started this - which is also why the command server outlives that
            // screen. Handsets not standing by are unaffected; somebody presses those by hand.
            // How many it was said to, kept for the whole wait. The count of handsets standing
            // by is not the denominator to show against arrivals: obeying means leaving the
            // home screen, so a handset that is on its way here has already stopped being
            // counted, and a screen reading "1 of 0" says nothing anybody can act on.
            // Cleared before the ask, because an excuse is about one press of one button: a
            // handset that could not measure an hour ago is not a fact about this round.
            commands.forgetExcuses()
            // And the lines on screen with them, for the same reason: they are one per handset
            // and they are about the round that just ran.
            report.forgetHeard()
            // Heard live rather than collected afterwards, and that is the whole value of it: a
            // handset waiting on its own permission dialog is fixable in the ten seconds before
            // the window closes and unfixable a minute later.
            //
            // Into the per-handset lines rather than the one message, which is what 09-13 got
            // wrong: the excuse arrived 152 ms after the room opened and the count of who had
            // joined overwrote it 1.9 seconds later, so it was on screen for under two seconds
            // and the person it was for never saw it. There is one message and every later write
            // wins; these lines are one per handset and stay.
            // Kept as names rather than a count, so a handset that excuses twice is still one
            // handset. The room is complete when everybody told has either asked or said why not,
            // and a count would let one noisy refusal close the room on somebody still arriving.
            val excuses = Collections.synchronizedMap(LinkedHashMap<String, RoomExcuse>())
            commands.listenForExcuses { peerId, excuse ->
                events.write("room-excuse $peerId ${excuse.name}")
                report.heard(peerId, RoundLine.Excused(excuse))
                excuses[peerId] = excuse
            }
            // Handsets told to leave their home screens a moment ago are on their way back to
            // them, and a room told while they are in the air reaches nobody at all. Reported on
            // 09-13: call a round off, press again straight away, and the host says it told
            // nobody. This costs nothing when they are already there - the first look answers.
            if (!awaitBriefly(ROOM_RETURN_GRACE_MILLIS) { commands.standingBy() > 0 }) {
                events.write("room-nobody-standing after ${ROOM_RETURN_GRACE_MILLIS}ms of waiting")
            }
            // A sound check of one device is a room of two: told by name, so nobody else joins.
            val told = if (aimedAt != null) {
                if (commands.sendTo(aimedAt, RoomOrder(RoomCommand.MEASURE_ROOM))) 1 else 0
            } else commands.send(
                if (place == HostPlace.OVERHEAD && !oneChirp) RoomCommand.MEASURE_OVERHEAD else RoomCommand.MEASURE_ROOM
            )
            log("the room servers are up: clock ${dials.clock}, room ${dials.room}, plan ${dials.plan}")
            report.say(
                if (told == 0) RoundLine.RoomToldNobody
                else RoundLine.RoomWaiting(told, ROOM_WINDOW_MILLIS / 1000)
            )
            events.write(
                "room-gathering opened as ${if (oneChirp) "sound check" else if (place == HostPlace.OVERHEAD) "overhead" else "room"}, " +
                    "told $told handsets, waiting up to ${ROOM_WINDOW_MILLIS / 1000}s"
            )
            // One chirp each for a sound check: it asks whether a device is heard, not where it is.
            timing = timingFor(CASE_ROOM).let { if (oneChirp) it.copy(repeats = 1) else it }
            val plan = planServer.awaitRoom(
                PLAN_WAIT_MILLIS,
                ROOM_SETTLE_MILLIS,
                ROOM_WINDOW_MILLIS,
                onJoined = { joined ->
                    events.write("room-joined $joined of $told told")
                    report.say(RoundLine.RoomJoined(joined, told))
                },
                // Everybody told has either asked or said why not, so there is nobody left to
                // wait for. Guarded on having told anybody at all: a round nobody was told about
                // would otherwise be complete the moment one stranger asked.
                enough = { joined ->
                    told > 0 && joined >= told - excuses.size
                }
            ) { asks ->
                // Every ask has to be this arm's. A handset running the pair flow would be
                // handed a slot it never agreed to chirp in, and the room would then hold one
                // silent slot with nothing afterwards saying whose it was.
                asks.firstOrNull { it.caseId != CASE_ROOM }?.let {
                    throw IllegalArgumentException("not a room this handset runs: ${it.caseId}")
                }
                // The names reach a file name on this side too, on the same terms as the pair
                // path: checked for shape here rather than trusted from where they came.
                asks.firstOrNull { !HostId.isValid(it.sinkId) }?.let {
                    throw IllegalArgumentException("not a handset name: ${it.sinkId}")
                }
                // The host takes the last slot, which is the convention combineFacing's signs
                // are written in and the one CalibrationSchedule's role overload encodes.
                val slots = asks.map { it.sinkId } + hostId
                events.write("room-gathered ${slots.size} handsets: ${slots.joinToString(" ")}")
                // Said out loud, because a handset asking twice is the visible end of something
                // that went wrong out of sight - on 09-13 it was one stuck behind a permission
                // dialog resuming a round that had already ended.
                if (planServer.supersededAsks > 0) events.write(
                    "room-superseded ${planServer.supersededAsks}: a handset asked twice and " +
                        "the older ask was dropped"
                )
                require(slots.size == slots.distinct().size) {
                    "one handset asked twice, and a room names each of them once: $slots"
                }
                CalibrationPlan(
                    caseId = CASE_ROOM,
                    hostId = hostId,
                    firstChirpAtHostNanos = System.nanoTime() + timing.planLeadNanos,
                    staggerNanos = timing.staggerNanos,
                    repeats = timing.repeats,
                    // Widened for the room this turned out to be, which is the first moment
                    // anything knows how big it is.
                    intervalNanos = roomIntervalNanos(timing.intervalNanos, slots.size, timing.staggerNanos),
                    slotIds = slots
                )
            } ?: run {
                report.say(
                    // Or called off before the gathering began: the press closed the plan socket
                    // first, and the gathering then found none - PLAN_UNBOUND, which is the button
                    // working and not a round that broke.
                    if (planServer.failureCode == CalibrationPlanServer.CALLED_OFF || calledOff()) {
                        events.write("room-called-off: pressed while the room was still gathering")
                        RoundLine.RoomCalledOffHere
                    } else {
                        RoundLine.Failed(planServer.failureCode ?: "ROOM_LOST")
                    }
                )
                return null
            }
            // The plan is out. Up to here calling the round off meant refusing to answer it; from
            // here it means telling everybody who holds it to stop, which is a different sentence
            // beside the same button - see StopOffer.
            report.underWay()
            val ownSlot = plan.slotIds.indexOf(hostId)
            val runner = runnerFor(plan, ownSlot)
            report.say(RoundLine.Running, runner.timing().recordUntilHostNanos)
            val run = runner.run()
            // A called-off round leaves nothing behind. Not even the half of it this handset
            // heard: a recording that stops in the middle of a schedule still correlates, and a
            // file that looks like every other run but holds a room that was never finished is
            // worse than no file at all. What it does leave is a line in the timeline.
            if (calledOff()) {
                events.write("room-cancelled while chirping; nothing of this round is kept")
                return null
            }
            return measure(Gathering(plan, ownSlot, run, roomServer, LinkedHashMap(excuses)))
        } finally {
            this.planServer = null
            // Nobody is standing at the screen once the round is over, and a listener held past
            // that is a screen being written to that is not there.
            commands.listenForExcuses(null)
            planServer.stop()
            roomServer.stop()
            clockServer?.stop()
            events.write("room-ports released")
            log("the room servers are down; the clock port is free again")
        }
    }

    /**
     * One handset's turn: wait to be asked, mint the plan, play, and combine the two halves.
     *
     * Nothing is stored here but the distance. The correction belongs to the handset that applies
     * it, and this one does not - what this produces is the answer the sink is waiting for on the
     * socket it delivered on.
     */
    private fun serveOneSink(
        aimedAt: String?,
        planServer: CalibrationPlanServer,
        resultServer: AlignmentResultServer
    ): RoundResult {
        // Two waits, and the difference is who has to act. The named handset has already been told
        // over the standing line and is on its way, so asking somebody to walk to it and press
        // something would be sending them on an errand that undoes itself. See [a button asserts
        // its own scope]: the words are the assertion, not the button.
        //
        // Said at all because this is the sixteen seconds of clock exchange, which makes no sound:
        // a screen that says nothing here is a screen that looks like it did not hear the press.
        report.say(if (aimedAt != null) RoundLine.WaitingAimed else RoundLine.Waiting)
        // Which handset this round is with. Set on the accept, because that is the only
        // moment it is known, and every file this run writes is named with it.
        var servedSink: String? = null
        val plan = planServer.awaitRequest(PLAN_WAIT_MILLIS) { request ->
            // The case names a directory RunStore will create, and it arrived over a socket.
            // Only the two this handset runs are honoured; anything else ends the run here
            // rather than at the run store.
            if (request.caseId !in setOf(CASE_MEASURE, CASE_VERIFY, CASE_SLOW_LINK, CASE_DISTANCE)) {
                throw IllegalArgumentException("not a case this handset runs: ${request.caseId}")
            }
            // The sink's name arrived over the same socket and names files on this side too.
            // Checked for shape here rather than trusted from where it came, on the same terms
            // as every other id that reaches a file name.
            if (!HostId.isValid(request.sinkId)) {
                throw IllegalArgumentException("not a handset name: ${request.sinkId}")
            }
            servedSink = request.sinkId
            // The arm is the sink's to name - it is the handset somebody pressed something on -
            // and the plan is where the host adopts it. Held on the field as well so that the
            // report this side files says which schedule actually ran.
            timing = timingFor(request.caseId)
            CalibrationPlan(
                caseId = request.caseId,
                hostId = hostId,
                // Far enough out to cover the warm-up and the gap the sink has yet to start.
                firstChirpAtHostNanos = System.nanoTime() + timing.planLeadNanos,
                staggerNanos = timing.staggerNanos,
                repeats = timing.repeats,
                intervalNanos = timing.intervalNanos
            )
        } ?: return when {
            // The stop button called this off, so what came back is the button working rather than
            // anything having gone wrong. It has already put its own sentence on the screen.
            calledOff() -> RoundResult.FAILED
            planServer.failureCode == CalibrationPlanServer.TIMEOUT -> {
                report.say(RoundLine.Failed(CalibrationPlanServer.TIMEOUT))
                RoundResult.NOBODY_ASKED
            }
            else -> {
                report.say(RoundLine.Failed(planServer.failureCode ?: "PLAN_LOST"))
                RoundResult.FAILED
            }
        }
        // Non-null by construction: awaitRequest answers a plan only once planFor has run.
        val sinkId = servedSink ?: run {
            report.say(RoundLine.Failed("PLAN_UNSIGNED"))
            return RoundResult.FAILED
        }
        // The plan is out, and from here both sides act on their own clocks. The stop button
        // stays live and changes meaning rather than going grey: before this it refuses to hand
        // the schedule out, after it it says so over the standing channel the other handset never
        // left - see StopOffer and [RoomCommand.CALL_OFF].
        report.underWay()
        val runner = runnerFor(plan, null)
        report.say(RoundLine.Running, runner.timing().recordUntilHostNanos)
        val run = runner.run()
        // Nothing of a called-off round is kept, on the same terms as the room's: no file, no
        // delivery, and above all no stored constant. The wait for the sink's half is skipped
        // with it - the sink stopped too, so waiting would be a minute spent on a socket nobody
        // is going to open.
        if (calledOff()) {
            events.write("pair-cancelled while chirping; nothing of this round is kept")
            return RoundResult.FAILED
        }
        // Written before anything is answered, so a refused run still leaves its evidence.
        // Named with the peer, not just the case: a case id names a directory this only ever
        // mkdirs, so a second sink measured on this host landed on the first one's file and
        // the directory's own mtime did not move to say so.
        File(RunStore(filesDir).prepareRun(plan.caseId), hostArtifact(sinkId)).writeText(run.json)
        fileAttempt("HOST-${plan.caseId}-$sinkId", run.json)
        log(run.json)
        var outcome: RoundLine = RoundLine.Kept(run.refusal ?: "ONE_SIDED_RUN")
        resultServer.awaitResult(RESULT_TIMEOUT_MILLIS) { message ->
            // The delivery is signed, and this host waits on one socket that any handset in
            // the room still holding a plan can reach. Combining a stranger's readings would
            // not make a worse number, it would make a number about a pair that never ran.
            if (message.sinkId != sinkId) {
                outcome = RoundLine.WrongSink(message.sinkId)
                return@awaitResult CalibrationReply(null, null, null)
            }
            val combined = AlignmentPairing.combine(plan.caseId, run.readings, message)
            val metres = measuredSeparationMetres(combined.pairs)
            // Kept rather than only shown, and kept outside the verdict: the separation is the
            // half difference of the two recordings and the alignment is the half sum, so a run
            // that will not cluster still measured the room. Guarded, because a room screen's
            // check is not worth a failed calibration. Shown and kept are two questions: what
            // this run measured goes on the screen for the person who ran it, and only what it
            // can vouch for goes on disk for a check nobody will be watching.
            val keep = separationToStore(combined.pairs)
            if (keep != null) runCatching {
                if (place != HostPlace.PLAYING) StoredListenerDistance(filesDir, sinkId).write(keep)
                if (place != HostPlace.OVERHEAD) StoredSeparation(filesDir, sinkId).write(keep)
            }
            outcome = combined.verdict?.clusterMeanMs?.let { mean ->
                RoundLine.HostDone(mean, metres ?: 0.0)
            } ?: metres?.takeIf { plan.caseId == CASE_DISTANCE }?.let {
                // The distance arm has no verdict to report and is not failing when it has none.
                RoundLine.DistanceDone(it)
            } ?: RoundLine.Kept(combined.failure?.name ?: "NO_VERDICT")
            // Filed here rather than after: this is the only point at which both halves of
            // the run exist in one place, and before this the combination was never written
            // down at all - one sentence on a screen, then gone.
            fileAttempt(
                "HOST-${plan.caseId}-$sinkId-PAIRED",
                pairedReportJson(
                    caseId = plan.caseId,
                    hostId = plan.hostId,
                    sinkId = sinkId,
                    combined = combined,
                    hostReadings = run.readings,
                    sinkReadings = message.readings,
                    intervalFrames = chirpIntervalFrames(plan.intervalNanos),
                    planLeadNanos = timing.planLeadNanos
                )
            )
            CalibrationReply(
                // Read through what the sink says it applied, never through this handset's
                // idea of it: only the sink knows what it actually used.
                measuredOffsetMicros = if (!keepsCorrection(plan.caseId)) null
                else CalibrationUpdate.measured(
                    message.appliedOffsetMicros,
                    combined.verdict
                ),
                clusterMeanMicros = combined.verdict?.clusterMeanMs?.let { (it * 1000).toLong() },
                passed = combined.verdict?.passed
            )
        }
        // Kept on the screen rather than replacing what the last sink said. A host that
        // measures two handsets in a row has two answers, and the pair of them is the whole
        // point of measuring the second one.
        report.heard(sinkId, outcome)
        return RoundResult.SERVED
    }

    /** This host's own part of [plan], on this platform's speaker and recorder. */
    private fun runnerFor(plan: CalibrationPlan, ownSlot: Int?): PeerCalibrationRunner {
        val runStore = RunStore(filesDir)
        val hostNanosNow = { System.nanoTime() }
        return PeerCalibrationRunner(
            runStore = runStore,
            caseId = plan.caseId,
            role = CalibrationRole.HOST,
            plan = plan,
            ownSlot = ownSlot,
            hostNanosNow = hostNanosNow,
            audioSource = audioSource,
            edgeShares = AlignmentAnalysis.DISTANCE_EDGE_SHARES,
            calledOff = calledOff,
            keepsRecording = keepsRecording,
            recorder = recorder(runStore, plan.caseId, hostNanosNow, audioSource),
            speaker = { speaker({ 0L }, hostNanosNow) }
        )
    }

    /**
     * The file the host's own half of a run goes in, under the peer it ran with.
     *
     * [sinkId] has been through [HostId.isValid] by the time it reaches here, which is what makes
     * it safe in a file name: it arrived over a socket, and hexadecimal of a fixed length cannot
     * hold a path segment.
     */
    private fun hostArtifact(sinkId: String): String = "peer-calibration-$sinkId.json"

    /**
     * Keeps one more copy of what this attempt produced, under a name no later run can claim.
     *
     * A case id names a directory the run store never clears, so a second run of the same case
     * overwrites the first where it stands - in place, which is why nothing outside says so: the
     * directory's own mtime does not move either. See [PeerRunLog].
     *
     * Guarded rather than left to throw. This is a second copy of evidence, and a full disk
     * turning a finished measurement into a vanished app would cost more than the copy is worth.
     */
    private fun fileAttempt(label: String, json: String) {
        runCatching { PeerRunLog(filesDir).write(label, json, System.currentTimeMillis()) }
            .onFailure { log("this attempt could not be filed under $label: $it") }
    }

    companion object {
        /**
         * The plan's chirp interval in frames, which is the grid an emission is measured against.
         *
         * Derived from the plan rather than from [CHIRP_INTERVAL_NANOS]: the plan is what both
         * handsets actually played to, and a host reading its own constant would answer for a
         * schedule nobody followed if the two ever came apart.
         */
        fun chirpIntervalFrames(intervalNanos: Long): Int =
            (intervalNanos * ChirpGenerator.SAMPLE_RATE / 1_000_000_000L).toInt()

        /** How long the host holds the round open waiting for somebody to pick up the other phone. */
        const val PLAN_WAIT_MILLIS = 300_000

        /**
         * How long a room waits for handsets to come back to their home screens before telling it.
         *
         * Sized for the trip back from the calibration screen to that one, not for anything on the
         * network: a handset whose round was called off finishes and resumes the home screen in a
         * couple of hundred milliseconds, and this is a few of those.
         */
        const val ROOM_RETURN_GRACE_MILLIS = 2_000L

        /**
         * How long the room waits between one handset asking and the next, before deciding
         * nobody else is coming.
         *
         * The fallback, and only that, since 2026-09-15: the host tells the room over the
         * standing line and knows how many it told, so a complete room stops waiting the moment
         * the last handset is in. What is left for this to cover is a handset that was told and
         * never arrived and never said why - its app killed, its process gone - and three seconds
         * is what that costs now.
         *
         * Eight until then, and eight was the right number for what it used to be: the gap
         * between two people pressing two buttons, back when a round was started by walking to
         * each phone. Nobody walks any more, so every round paid eight seconds of silence after
         * the last handset had already asked.
         */
        const val ROOM_SETTLE_MILLIS = 3_000

        /**
         * The whole gathering, from the first ask.
         *
         * Bounded by what a sink will wait for its plan - the handset that asks first waits out
         * everybody after it - and [CalibrationPlanServer.awaitRoom] checks that rather than
         * trusting it. Under CalibrationPlanClient's 30 s with room to spare.
         */
        const val ROOM_WINDOW_MILLIS = 25_000

        /**
         * How long the host waits for the room to deliver what it heard.
         *
         * The pair's own bound, for the same reason: a correlation pass takes seconds and this
         * is what stands between a handset that died mid-run and a host that never finishes. A
         * room's pass is wider than a pair's - one search per slot rather than three in all -
         * so the headroom over it is in RoomResultClient's reply timeout, not here.
         */
        const val ROOM_RESULT_TIMEOUT_MILLIS = 120_000

        /** The sink's correlation pass takes seconds; this bounds a sink that died mid-run. */
        const val RESULT_TIMEOUT_MILLIS = 120_000
    }
}
