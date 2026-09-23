package com.soundmesh.product

import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.CalibrationUpdate
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.LinkQuality
import com.soundmesh.core.LinkSurvey
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.RoomResultMessage
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.AlignmentResultClient
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.CalibrationPlanClient
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.EventLog
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.PeerCalibrationRunner
import com.soundmesh.probe.sync.PeerRunLog
import com.soundmesh.probe.sync.RoomResultClient
import com.soundmesh.probe.sync.RoundRecorder
import com.soundmesh.probe.sync.RoundSpeaker
import com.soundmesh.probe.sync.StoredApproximateCalibration
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.tellHostWhy
import java.io.File
import java.util.Locale

/**
 * Everything one sink round needs, in place of the intent a screen used to read it off.
 *
 * A data class rather than the intent itself, and that is the whole point of this file: the run
 * below has no screen in it, so it can be driven by [StandbyService] on a handset lying face down.
 * Reading an intent would have tied it back to an activity, which is the one thing Android will
 * not let a background app start.
 *
 * Every field here was an intent extra on [PeerCalibrateActivity], and every one of the diagnostic
 * arms keeps its shipped default: a caller that names nothing gets the run a listener's handset
 * does. See that screen's `timingFor` for why the schedule arrives as a function - the arm's own
 * defaults with anything a command line asked for already folded in.
 *
 * [audioSource] is a capture path's name rather than the handset's enum of them, since 09-23 when
 * this moved to core: the recorder a platform hands in is what reads it. A desktop has one path.
 */
data class SinkRoundRequest(
    val verifying: Boolean = false,
    val allowSlowLink: Boolean = false,
    val distanceOnly: Boolean = false,
    val room: Boolean = false,
    val audioSource: String = "MIC",
    val keepFractionWhileFilling: Boolean = true,
    val timingFor: (String?) -> ArmSchedule = ::defaultTimingFor
)

/**
 * Where a round's words go, and the one fact its caller cannot work out for itself.
 *
 * Two implementations and they want different things from it. A screen puts [say] in front of
 * somebody and counts down to [untilLocalNanos]; a service puts it in the notification that is
 * already there. Neither can be assumed, so the run below says what happened and lets the caller
 * decide where it lands - and, since the round is one copy for a handset and a desktop, in which
 * words: it names the sentence and the caller spells it.
 */
interface SinkRoundReport {
    /**
     * One line about where the round is, with the instant it is counting down to.
     *
     * [untilLocalNanos] is on [System.nanoTime]'s scale, and null means there is nothing to count
     * down to - the round has either finished or is waiting on somebody else.
     */
    fun say(line: RoundLine, untilLocalNanos: Long? = null)

    /**
     * The host called the round off while this handset was waiting for its plan.
     *
     * Told rather than inferred from the text: a screen that got here has nothing to show and
     * should leave at once, where one that merely failed lingers so its sentence can be read.
     */
    fun calledOff() {}

    /**
     * Who the host is, once a plan has said, for a caller that dialled an address without knowing.
     *
     * A handset always knows - it scanned the host's code - and ignores this. A desktop given an
     * address by hand does not, and the constant this round stores is filed under the host's id, so
     * that caller keeps it to find the constant again next time.
     */
    fun hostIs(hostId: String) {}
}

/**
 * The host a round is run against: where to reach it, and who it is if that is known.
 *
 * [hostId] is null only for a desktop that was given an address by hand and has never measured
 * against it; the round then takes the id the host's plan names. See [SinkRoundReport.hostIs].
 */
data class RoundHost(val address: String, val hostId: String?)

/**
 * The ports a round dials on the host, each the one the handset host has always listened on.
 *
 * Here since the round moved to core, which is also where the handset's own constants now read
 * them from, so a desktop sink cannot dial a different port from the one a handset host opened.
 */
object RoundPorts {
    const val CLOCK = ClockPacket.DEFAULT_PORT

    /** Where the host takes delivery of the sink's own reading of the run. */
    const val RESULT = 45125

    /** Next after the result's 45125. */
    const val PLAN = 45126

    /** Next after the plan's 45126, and held only while a room is being measured. */
    const val ROOM = 45127
}

/**
 * The ports one round dials, which are [RoundPorts] and the command port unless somebody says
 * otherwise - a desktop sink told to follow a host on other ports, or a test standing in for one.
 */
data class RoundDials(
    val clock: Int = RoundPorts.CLOCK,
    val result: Int = RoundPorts.RESULT,
    val plan: Int = RoundPorts.PLAN,
    val room: Int = RoundPorts.ROOM,
    val command: Int = COMMAND_PORT
)

/**
 * One handset measuring as a sink: the clock, the chirps, and what it does with the answer.
 *
 * Moved here from [PeerCalibrateActivity] unchanged, which is the only reason it is worth having
 * its own file. A sink in a room round does three things - hold a clock exchange open, play one
 * chirp when the plan says to while recording, and hand back what it heard - and not one of them
 * needs a screen. It needed one only because it grew inside an activity, and that accident is what
 * made a handset with its screen off answer a room round with "ASLEEP".
 *
 * The caller owns the thread, the radio hold and the busy flag. This runs on whatever thread it is
 * called on and blocks for the length of the round, which is a minute or two.
 *
 * In core since 09-23, so a desktop sink runs this same round rather than a copy of it. What a
 * platform hands in is only what differs: where the round's files live, who the host is, where the
 * sound goes out and the recording comes from, and how its sentences are spelled.
 */
class SinkRound(
    /** Where this device keeps its identity, its constants and its runs. */
    private val filesDir: File,
    private val request: SinkRoundRequest,
    /** The host to measure against, or null when this device has none. */
    private val host: () -> RoundHost?,
    private val dials: RoundDials = RoundDials(),
    /** Whether the radio lock was actually taken, for the run's own report. See `withRadioAwake`. */
    private val radioHeld: () -> Boolean,
    /**
     * Whether the host has called this round off since it started. See [MeasuringNow.calledOff].
     *
     * Asked at every point this round stands still, and there are three of them worth the line:
     * the sixteen seconds of silent clock filling, the wait for a plan, and the minute of chirps.
     * Without it the only one of the three that could be interrupted was the middle one, because
     * it is the only one where this handset is waiting on the host for something.
     */
    private val calledOff: () -> Boolean = { false },
    private val report: SinkRoundReport,
    /** Whether the recording stays once it has been read. See [PeerCalibrationRunner]. */
    private val keepsRecording: Boolean,
    /** Where the lines a person reading the device's own log would want go. */
    private val log: (String) -> Unit = {},
    /** The recording, given the run store, the case, host time and the capture path's name. */
    private val recorder: (RunStore, String, () -> Long, String) -> RoundRecorder,
    /** The output, given the clock offset in force and host time. */
    private val speaker: (offsetNanosNow: () -> Long, hostNanosNow: () -> Long) -> RoundSpeaker
) {
    /** One timeline, shared with every other part of the app. See [EventLog]. */
    private val events: EventLog by lazy { EventLog(filesDir) }

    /** What the link looked like when the clock had filled its window, or null if unmeasured. */
    @Volatile
    private var link: LinkQuality? = null

    /** The schedule this round is running, set the moment its case is known. */
    @Volatile
    private var timing: ArmSchedule = defaultTimingFor(null)

    fun run() {
        // Named here rather than read through the request at each use, so that this run reads
        // exactly as it did on the screen it came from. Seven tests read this file as source.
        val verifying = request.verifying
        val allowSlowLink = request.allowSlowLink
        // Named before anything is spent, and before the gate, so that a refused run can still
        // say which one it would have been - and so that the one combination that names no run is
        // turned away here rather than two minutes of clock later.
        val caseId = calibrationCase(verifying, allowSlowLink, distanceOnly(), roomAsked())
            ?: return show(RoundLine.Failed("ARMS_COMBINED"))
        timing = timingFor(caseId)
        val paired = host()
            ?: return show(RoundLine.NoPairing)
        // The name this handset answers to, which it signs both of its messages with. The same
        // name a host uses for itself: it is what this phone is called, not what role it is in.
        val sinkId = HostIdentity(filesDir).current()
        val estimator = ClockOffsetEstimator(keepFractionWhileFilling = keepFractionWhileFilling())
        val clockClient = ClockSyncClient(paired.address, dials.clock, estimator)
        val clockThread =
            Thread({ clockClient.runFor(CLOCK_SECONDS, CLOCK_INTERVAL_MILLIS) }, "SoundMeshPeerClock")
        val clockStartedAt = System.nanoTime()
        clockThread.start()
        try {
            show(RoundLine.Clock, clockStartedAt + timing.clockFillNanos)
            val deadline = clockStartedAt + CONVERGENCE_TIMEOUT_NANOS
            while (clockClient.currentEstimate() == null && System.nanoTime() < deadline &&
                !calledOff()
            ) {
                Thread.sleep(CONVERGENCE_POLL_MILLIS)
            }
            if (calledOff()) return calledOffHere()
            if (clockClient.currentEstimate() == null) {
                tellHost(RoomExcuse.CLOCK_NOT_CONVERGED)
                return show(RoundLine.Failed("CLOCK_NOT_CONVERGED"))
            }
            // Having an estimate is not the same as having a settled one, and the first run on
            // hardware cost exactly that difference. MIN_SAMPLES is the point the estimator will
            // answer at, eight of a sixty-four wide window; the offset it answers with then is
            // still moving as the window fills. C1 measured its five chirps against five different
            // offsets spanning 8.1 ms, and the five alignment errors moved with them one for one.
            //
            // The harness never met this because it plays two minutes of audio between converging
            // and chirping, which at its own two second cadence is exactly the window's worth of
            // exchanges. This waits for the same thing directly instead of buying it by accident.
            while (System.nanoTime() - clockStartedAt < timing.clockFillNanos && !calledOff()) {
                Thread.sleep(CONVERGENCE_POLL_MILLIS)
            }
            if (calledOff()) return calledOffHere()
            val converged = clockClient.currentEstimate() ?: run {
                tellHost(RoomExcuse.CLOCK_NOT_CONVERGED)
                return show(RoundLine.Failed("CLOCK_NOT_CONVERGED"))
            }
            // Read before the chirps rather than after, because that is the only point at which
            // knowing costs nothing. A link this slow cannot be aligned by any estimator - the bias
            // a two-way exchange carries is half the difference between the one way delays, which
            // is systematic - so the alternative to saying so here is fifty seconds of standing
            // still for a number nobody can read.
            //
            // Opened only for the experiment arm, and the survey is read either way: a run that
            // was let past is worth nothing without the number it was let past on, and that number
            // is what the experiment is about.
            link = LinkSurvey.of(clockClient.recordedExchanges())
            link?.takeIf { !it.usable && !allowSlowLink }?.let {
                // Stopped before the exchanges are read, on the same terms as a finished run:
                // recordedExchanges is documented to be read once runFor has returned, and what is
                // being filed here is the whole record rather than the survey's summary of it.
                clockThread.interrupt()
                clockThread.join(CLOCK_JOIN_MILLIS)
                fileAttempt(
                    "SINK-REFUSED",
                    refusedRunJson(
                        caseId,
                        SLOW_LINK,
                        clockReportJson(
                            CLOCK_INTERVAL_MILLIS,
                            estimator.windowSize,
                            estimator.bestCount,
                            estimator.keepFractionWhileFilling,
                            timing.clockFillNanos,
                            radioHeld(),
                            link,
                            converged,
                            clockClient.currentEstimate(),
                            clockClient.recordedExchanges()
                        )
                    )
                )
                tellHost(RoomExcuse.SLOW_LINK)
                return show(
                    RoundLine.SlowLink(
                        it.medianRoundTripNanos / 1_000_000.0,
                        LinkSurvey.MAX_MEDIAN_ROUND_TRIP_NANOS / 1_000_000.0
                    )
                )
            }
            val plan = try {
                CalibrationPlanClient(paired.address, dials.plan).request(caseId, sinkId)
            } catch (off: CalibrationPlanClient.RoomCalledOff) {
                // Somebody pressed a button on the host. Said in those words rather than as the
                // failure every unreadable answer shares, because there is nothing here to fix.
                events.write("room-called-off by the host while this handset was waiting")
                report.calledOff()
                return show(RoundLine.CalledOff)
            }
            // The host id is the file name the correction is stored under. A plan from somebody
            // this handset never scanned would file the answer against the wrong peer, and every
            // later session would apply it with nothing in the result to notice it by.
            //
            // A desktop dialling an address by hand has scanned nobody and knows no id to check
            // against, so for it the plan is what says who the host is - and it is told, to find
            // the constant filed under that id again next time. See SinkRoundReport.hostIs.
            val expectedHostId = paired.hostId
            if (expectedHostId != null && plan.hostId != expectedHostId) {
                return show(RoundLine.Failed("PLAN_FROM_ANOTHER_HOST"))
            }
            val hostId = plan.hostId
            if (expectedHostId == null) report.hostIs(hostId)
            // Both sides file under the plan's case. A host that answered with a different one
            // would split one run across two directories with nothing in either saying so.
            if (plan.caseId != caseId) {
                return show(RoundLine.Failed("PLAN_FOR_ANOTHER_CASE"))
            }
            // Which chirp of the window is this handset's, straight out of the plan that named
            // it. A room the host built without this handset in it is not a room this handset
            // can read: it would have no anchor to read its own recording against.
            var ownSlot: Int? = null
            if (plan.caseId == CASE_ROOM) {
                val slot = plan.slotIds.indexOf(sinkId)
                if (slot < 0) {
                    return show(RoundLine.Failed("ROOM_WITHOUT_THIS_HANDSET"))
                }
                ownSlot = slot
            }
            // Read once the host is certain rather than when the round began: until the plan, a
            // desktop given an address by hand does not know which file is its. Nothing reads it
            // before this point, so on a handset it is the same read at a later moment.
            val stored = StoredCalibration(filesDir, hostId).read()
            val appliedMicros = stored?.micros ?: 0L
            // The correction is subtracted from this handset's view of host time, exactly as
            // SinkSession applies it, so a verification tests it where the product puts it.
            val hostNanosNow = {
                System.nanoTime() +
                    (clockClient.currentEstimate() ?: converged).offsetNanos -
                    appliedMicros * 1_000L
            }
            val offsetNanosNow = { (clockClient.currentEstimate() ?: converged).offsetNanos }
            val runner = PeerCalibrationRunner(
                runStore = RunStore(filesDir),
                caseId = caseId,
                role = CalibrationRole.SINK,
                plan = plan,
                ownSlot = ownSlot,
                hostNanosNow = hostNanosNow,
                offsetNanosNow = offsetNanosNow,
                audioSource = audioSource(),
                edgeShares = AlignmentAnalysis.DISTANCE_EDGE_SHARES,
                calledOff = calledOff,
                keepsRecording = keepsRecording,
                recorder = recorder(RunStore(filesDir), caseId, hostNanosNow, audioSource()),
                speaker = { speaker(offsetNanosNow, hostNanosNow) }
            )
            // Counted to the instant the recording closes rather than to the last chirp: the
            // sound still has to leave the output buffer and cross the room, and somebody who
            // stands up at the last chirp has moved during the part being measured.
            show(RoundLine.Running, localNanosOf(runner.timing().recordUntilHostNanos, hostNanosNow))
            val run = runner.run()
            // Before anything is filed or delivered. A round that was called off has no answer,
            // and the one thing that must not happen here is the constant being folded from half
            // a run - every session afterwards would carry it with nothing to notice it by.
            if (calledOff()) return calledOffHere()
            // Spliced in rather than passed to the runner: the clock belongs to this round, and
            // the reason to record it is that the constant is only as good as the offset the
            // chirps were scheduled against. Without it, a run whose estimate was still moving
            // reads exactly like a run whose room was noisy.
            // The clock's work is over - what is left is one socket exchange with the host - and
            // recordedExchanges is documented to be read once runFor has returned. It is backed by
            // a plain ArrayList the clock thread appends to, so reading it from here while that
            // thread still runs is a race, and the harness avoids it by joining first (see
            // SyncActivity, which builds its report after clockThread.join()). Stopped here rather
            // than only in the finally so this side does the same.
            clockThread.interrupt()
            clockThread.join(CLOCK_JOIN_MILLIS)
            val json = withClock(
                run.json, estimator, converged, clockClient.currentEstimate(), clockClient.recordedExchanges()
            )
            File(RunStore(filesDir).prepareRun(caseId), ARTIFACT).writeText(json)
            fileAttempt("SINK-$caseId", json)
            log(json)
            // A room's delivery is this handset's hearing rather than a pair's arithmetic, and
            // the run ends here: the field belongs to the host, which is the only handset that
            // ever holds the whole room. What comes back is what the room made of this one.
            if (ownSlot != null) {
                val room = RoomResultClient(paired.address, dials.room)
                    .exchange(RoomResultMessage(plan.caseId, sinkId, ownSlot, run.arrivalsByRepeat))
                // The host offers this to everybody who delivered; whether to keep it is decided
                // here, and only here, because only this handset knows what it already carries.
                // A measurement outranks the offer and is left alone - the offer is what stands
                // in for one until somebody has a minute to walk to this phone.
                val kept = room.approximateOffsetMicros?.takeIf { stored == null }?.also {
                    runCatching { StoredApproximateCalibration(filesDir, hostId).write(it) }
                }
                // Written down as well as shown, and that is not belt and braces: the screen says
                // it once to whoever is looking, and the next proper calibration deletes the file
                // - so without this line there is afterwards no evidence anywhere that the offer
                // was ever taken. Both outcomes, because "ignored it" is the other half of the
                // answer and an offer that silently went nowhere reads exactly like no offer.
                events.write(
                    when {
                        kept != null -> "room-offer kept " +
                            String.format(Locale.US, "%.3f", kept / 1000.0) + "ms"
                        room.approximateOffsetMicros != null ->
                            "room-offer ignored: this handset already carries a measured constant"
                        else -> "room-offer none: the host offered nothing for this pair"
                    }
                )
                return show(
                    if (kept == null) RoundLine.RoomSinkDone(room.handsets, room.ownPairsReadable)
                    else RoundLine.RoomSinkApproximate(room.handsets, room.ownPairsReadable, kept / 1000.0)
                )
            }
            // Delivered even when there is nothing to deliver: the host waits on this message, so
            // an empty run and a dead sink look the same from an end of a socket that never opens.
            val reply = AlignmentResultClient(paired.address, dials.result)
                .exchange(plan.caseId, sinkId, appliedMicros, run.readings)
            // The experiment arm ends here, one step short of every arm that moves the constant.
            // That is what it is: the gate refuses these links because the offset a two-way
            // exchange gives on one is biased by half the difference of the one way delays, so a
            // constant folded from here would carry that bias into every session afterwards. What
            // it is for is measuring that bias acoustically, and the readings are already filed.
            if (allowSlowLink) return show(
                reply.measuredOffsetMicros?.let {
                    RoundLine.MeasuredOnly(it / 1000.0)
                } ?: RoundLine.Kept(run.refusal ?: "NOT_USABLE")
            )
            // A verification measures the residual left after the stored constant is applied.
            // Writing a residual where the constant lives would halve the correction every time.
            if (verifying) return show(
                RoundLine.Verified((reply.clusterMeanMicros ?: 0L) / 1000.0)
            )
            val observed = reply.measuredOffsetMicros
                ?: return show(RoundLine.Kept(run.refusal ?: "NOT_USABLE"))
            val observations = stored?.observations ?: 0
            if (!foldsIntoStoredCalibration(observations, observed, appliedMicros)) {
                return show(RoundLine.NotFolded((observed - appliedMicros) / 1000.0))
            }
            val folded = CalibrationUpdate.fold(appliedMicros, observations, observed)
                ?: return show(RoundLine.Kept("OFFSET_OUT_OF_RANGE"))
            StoredCalibration(filesDir, hostId).write(folded, observations + 1)
            // The measurement is here now, so the guess goes. Read order alone would hide it
            // rather than remove it, and a guess nothing reads is a guess nothing checks either.
            runCatching { StoredApproximateCalibration(filesDir, hostId).forget() }
            show(RoundLine.Done(folded / 1000.0, observations + 1))
        } finally {
            clockThread.interrupt()
        }
    }

    private fun show(line: RoundLine, untilLocalNanos: Long? = null) = report.say(line, untilLocalNanos)

    /**
     * Where an instant on the host's clock falls on this device's own, for a countdown.
     *
     * Read at the moment of asking, which is what the handset's whenItReaches always did: the
     * offset moves as the exchange runs, and a countdown only needs to be right when it is shown.
     */
    private fun localNanosOf(hostNanos: Long, hostNanosNow: () -> Long): Long =
        hostNanos - hostNanosNow() + System.nanoTime()

    /**
     * Leaves the round because the host said to, in the one sentence that is true of it.
     *
     * The same words whether the press landed during the clock, the wait for a plan or the chirps:
     * from this handset all three are the host changing its mind, and the person who changed it is
     * standing at the host rather than here.
     */
    private fun calledOffHere() {
        events.write("round-called-off by the host; nothing of it is kept")
        report.calledOff()
        show(RoundLine.CalledOff)
    }

    /** The next four stand in for what the screen read off its intent, spelled the same way. */
    private fun distanceOnly(): Boolean = request.distanceOnly

    private fun roomAsked(): Boolean = request.room

    private fun keepFractionWhileFilling(): Boolean = request.keepFractionWhileFilling

    private fun audioSource(): String = request.audioSource

    private fun timingFor(caseId: String?): ArmSchedule = request.timingFor(caseId)

    /**
     * Tells the host why this handset is not going to measure.
     *
     * Only a handset that has scanned a pairing code knows an address to tell it at - one that has
     * never been paired is invisible to the host by construction, and no channel added here would
     * reach it. The role check the screen makes is not needed: this class only ever runs as a sink.
     */
    private fun tellHost(excuse: RoomExcuse) {
        val paired = host() ?: return
        events.write("excuse-told ${excuse.name}")
        tellHostWhy(paired.address, dials.command, HostIdentity(filesDir).current(), excuse)
    }

    private fun fileAttempt(label: String, json: String) {
        runCatching { PeerRunLog(filesDir).write(label, json, System.currentTimeMillis()) }
            .onFailure { log("this attempt could not be filed under $label: $it") }
    }

    /** The run's own report, with the clock it was scheduled against spliced beside it. */
    private fun withClock(
        json: String,
        estimator: ClockOffsetEstimator,
        atStart: ClockEstimate?,
        atEnd: ClockEstimate?,
        exchanges: List<ClockExchange>
    ): String = withClockReport(
        json,
        clockReportJson(
            CLOCK_INTERVAL_MILLIS, estimator.windowSize, estimator.bestCount,
            estimator.keepFractionWhileFilling,
            timing.clockFillNanos, radioHeld(), link, atStart, atEnd, exchanges
        )
    )

    private companion object {
        /** The same name the screen filed a finished run under; read by whatever reads runs. */
        const val ARTIFACT = "peer-calibration.json"

        /** Why a refused run was refused, in the field a finished run names its own refusal in. */
        const val SLOW_LINK = "SLOW_LINK"

        /** Long enough for the whole schedule; the exchange runs the length of the calibration. */
        const val CLOCK_SECONDS = 120

        /** SyncActivity's own convergence bound, and it is the first estimate this bounds. */
        const val CONVERGENCE_TIMEOUT_NANOS = 40_000_000_000L

        /** Far finer than the seconds convergence takes, and it costs nothing to wait this way. */
        const val CONVERGENCE_POLL_MILLIS = 50L

        /**
         * How long the run waits for the clock thread to notice it has been stopped.
         *
         * Bounded rather than open ended: the thread is inside a socket receive or a sleep, both of
         * which end promptly, and a run that has already measured everything it came for should
         * report it even if this one join is the thing that hangs.
         */
        const val CLOCK_JOIN_MILLIS = 2_000L
    }
}
