package com.soundmesh.product

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
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
import com.soundmesh.probe.R
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.AlignmentResultClient
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.CalibrationAudioSource
import com.soundmesh.probe.sync.CalibrationPlanClient
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.EventLog
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.PeerCalibrationRunner
import com.soundmesh.probe.sync.PeerRunLog
import com.soundmesh.probe.sync.RoomResultClient
import com.soundmesh.probe.sync.RouterPoke
import com.soundmesh.probe.sync.StoredApproximateCalibration
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.SyncActivity
import com.soundmesh.probe.sync.holdingRadio
import com.soundmesh.probe.sync.keepingAwake
import com.soundmesh.probe.sync.radioHoldOf
import com.soundmesh.probe.sync.routerPokeOf
import com.soundmesh.probe.sync.tellHostWhy
import com.soundmesh.session.SessionService
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
 */
internal data class SinkRoundRequest(
    val verifying: Boolean = false,
    val allowSlowLink: Boolean = false,
    val distanceOnly: Boolean = false,
    val room: Boolean = false,
    val audioSource: CalibrationAudioSource = CalibrationAudioSource.parse(null),
    val keepFractionWhileFilling: Boolean = true,
    val timingFor: (String?) -> ArmSchedule = ::defaultTimingFor
)

/**
 * Where a round's words go, and the one fact its caller cannot work out for itself.
 *
 * Two implementations and they want different things from it. A screen puts [say] in front of
 * somebody and counts down to [untilElapsedMillis]; a service puts it in the notification that is
 * already there. Neither can be assumed, so the run below says what happened and lets the caller
 * decide where it lands.
 */
internal interface SinkRoundReport {
    /**
     * One line about where the round is, with the instant it is counting down to.
     *
     * [untilElapsedMillis] is on [SystemClock.elapsedRealtime]'s scale, and null means there is
     * nothing to count down to - the round has either finished or is waiting on somebody else.
     */
    fun say(text: String, untilElapsedMillis: Long? = null)

    /**
     * The host called the round off while this handset was waiting for its plan.
     *
     * Told rather than inferred from the text: a screen that got here has nothing to show and
     * should leave at once, where one that merely failed lingers so its sentence can be read.
     */
    fun calledOff() {}
}

/**
 * How far off an instant on the host's clock is, on the scale a screen counts down in.
 *
 * File scope rather than on either caller: the activity and the service both hand this to their
 * own [SinkRoundReport], and two copies of a subtraction is two places for a sign to be wrong.
 */
internal fun whenItReaches(instantNanos: Long, now: () -> Long): Long =
    SystemClock.elapsedRealtime() + (instantNanos - now()) / 1_000_000L

/**
 * Runs [body] with this handset's radio held out of power save, saying whether it worked.
 *
 * Lifted out of [PeerCalibrateActivity.start] when the sink half stopped needing a screen. Both
 * roles need it and both need it recorded the same way - an access point buffers frames for a
 * station that is asleep, and the reply half of an exchange is as much of the round trip as the
 * request half, so a host dozing costs the sink exactly the same milliseconds.
 *
 * [held] is called with whether the lock was actually taken. On the record rather than assumed:
 * the lock is best effort, and a run whose lock quietly did nothing looks exactly like a run that
 * proves power save is irrelevant.
 */
internal fun withRadioAwake(
    context: Context,
    events: EventLog,
    held: (Boolean) -> Unit,
    body: () -> Unit
) {
    holdingRadio(radioHoldOf(context), held = held) {
        // The lock above is not enough on its own; what keeps a radio awake is having something to
        // receive. See keepingAwake for the three numbers that say so.
        val poke: RouterPoke? = routerPokeOf(context)
        // Said out loud because a poke that reaches nobody measures exactly like no poke at all:
        // the first version of this aimed at an unreachable gateway and a whole round went by
        // looking like evidence that power save does not matter.
        keepingAwake(poke, answered = { answered ->
            events.write(
                when {
                    poke == null || answered == null ->
                        "radio-awake: this handset named no IPv4 router, so nothing is keeping it awake"
                    answered -> "radio-awake: " + poke.router.hostAddress + " answered"
                    else -> "radio-awake: " + poke.router.hostAddress + " did not answer"
                }
            )
        }, body = body)
    }
}

/**
 * Stops whatever this handset is playing, because a round is about to record it.
 *
 * Not a courtesy, and not a sink's problem alone - every handset in a round records, the one that
 * gathered it included. A round measures when a chirp arrived by correlating against the chirp,
 * and a recording with music over the top of it still produces a number. So the cost of skipping
 * this is not a failed round: it is a confident wrong answer, applied to every session afterwards
 * with nothing anywhere to notice it by.
 *
 * Not restarted at the end. The rest of the room is still measuring, and a handset that started
 * playing on its own in the middle of their window would be the next thing over their microphones.
 * Somebody presses play when the room is done, which is where that decision belongs.
 */
internal fun hushWhateverIsPlaying(context: Context, events: EventLog) {
    if (SessionService.ACTIVE == null) return
    events.write("round-hush: stopping playback, a chirp measured through music measures the music")
    context.startService(
        Intent(context, SessionService::class.java).setAction(SessionService.ACTION_STOP)
    )
    // Bounded, and short against the sixteen seconds of clock a round opens with. A session that
    // will not let go is worth a round measured over it far less than it is worth saying so, and
    // the wait ending is not the same as the session having stopped.
    val until = SystemClock.elapsedRealtime() + HUSH_WAIT_MILLIS
    while (SessionService.ACTIVE != null && SystemClock.elapsedRealtime() < until) {
        runCatching { Thread.sleep(HUSH_POLL_MILLIS) }
    }
    if (SessionService.ACTIVE != null) {
        events.write("round-hush: the session was still up when the round began")
    }
}

/** How long [hushWhateverIsPlaying] waits for playback to actually stop. */
private const val HUSH_WAIT_MILLIS = 3_000L

private const val HUSH_POLL_MILLIS = 50L

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
 */
internal class SinkRound(
    private val context: Context,
    private val request: SinkRoundRequest,
    /** Whether the radio lock was actually taken, for the run's own report. See [withRadioAwake]. */
    private val radioHeld: () -> Boolean,
    private val report: SinkRoundReport
) {
    private val filesDir: File get() = context.filesDir

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
            ?: return show(string(R.string.pair_calibrate_failed, "ARMS_COMBINED"))
        timing = timingFor(caseId)
        val paired = PairedHost(filesDir).read()
            ?: return show(string(R.string.pair_calibrate_no_pairing))
        // The name this handset answers to, which it signs both of its messages with. The same
        // name a host uses for itself: it is what this phone is called, not what role it is in.
        val sinkId = HostIdentity(filesDir).current()
        val stored = StoredCalibration(filesDir, paired.hostId).read()
        val appliedMicros = stored?.micros ?: 0L
        val estimator = ClockOffsetEstimator(keepFractionWhileFilling = keepFractionWhileFilling())
        val clockClient = ClockSyncClient(paired.address, SyncActivity.CLOCK_PORT, estimator)
        val clockThread =
            Thread({ clockClient.runFor(CLOCK_SECONDS, CLOCK_INTERVAL_MILLIS) }, "SoundMeshPeerClock")
        val clockStartedAt = System.nanoTime()
        clockThread.start()
        try {
            show(
                string(R.string.pair_calibrate_clock),
                whenItReaches(clockStartedAt + timing.clockFillNanos) { System.nanoTime() }
            )
            val deadline = clockStartedAt + CONVERGENCE_TIMEOUT_NANOS
            while (clockClient.currentEstimate() == null && System.nanoTime() < deadline) {
                Thread.sleep(CONVERGENCE_POLL_MILLIS)
            }
            if (clockClient.currentEstimate() == null) {
                tellHost(RoomExcuse.CLOCK_NOT_CONVERGED)
                return show(string(R.string.pair_calibrate_failed, "CLOCK_NOT_CONVERGED"))
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
            while (System.nanoTime() - clockStartedAt < timing.clockFillNanos) {
                Thread.sleep(CONVERGENCE_POLL_MILLIS)
            }
            val converged = clockClient.currentEstimate() ?: run {
                tellHost(RoomExcuse.CLOCK_NOT_CONVERGED)
                return show(string(R.string.pair_calibrate_failed, "CLOCK_NOT_CONVERGED"))
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
                    string(
                        R.string.pair_calibrate_slow_link,
                        it.medianRoundTripNanos / 1_000_000.0,
                        LinkSurvey.MAX_MEDIAN_ROUND_TRIP_NANOS / 1_000_000.0
                    )
                )
            }
            val plan = try {
                CalibrationPlanClient(paired.address, PeerCalibrateActivity.PLAN_PORT).request(caseId, sinkId)
            } catch (off: CalibrationPlanClient.RoomCalledOff) {
                // Somebody pressed a button on the host. Said in those words rather than as the
                // failure every unreadable answer shares, because there is nothing here to fix.
                events.write("room-called-off by the host while this handset was waiting")
                report.calledOff()
                return show(string(R.string.pair_calibrate_room_called_off))
            }
            // The host id is the file name the correction is stored under. A plan from somebody
            // this handset never scanned would file the answer against the wrong peer, and every
            // later session would apply it with nothing in the result to notice it by.
            if (plan.hostId != paired.hostId) {
                return show(string(R.string.pair_calibrate_failed, "PLAN_FROM_ANOTHER_HOST"))
            }
            // Both sides file under the plan's case. A host that answered with a different one
            // would split one run across two directories with nothing in either saying so.
            if (plan.caseId != caseId) {
                return show(string(R.string.pair_calibrate_failed, "PLAN_FOR_ANOTHER_CASE"))
            }
            // Which chirp of the window is this handset's, straight out of the plan that named
            // it. A room the host built without this handset in it is not a room this handset
            // can read: it would have no anchor to read its own recording against.
            var ownSlot: Int? = null
            if (plan.caseId == CASE_ROOM) {
                val slot = plan.slotIds.indexOf(sinkId)
                if (slot < 0) {
                    return show(string(R.string.pair_calibrate_failed, "ROOM_WITHOUT_THIS_HANDSET"))
                }
                ownSlot = slot
            }
            // The correction is subtracted from this handset's view of host time, exactly as
            // SinkSession applies it, so a verification tests it where the product puts it.
            val hostNanosNow = {
                System.nanoTime() +
                    (clockClient.currentEstimate() ?: converged).offsetNanos -
                    appliedMicros * 1_000L
            }
            val runner = PeerCalibrationRunner(
                runStore = RunStore(filesDir),
                caseId = caseId,
                role = CalibrationRole.SINK,
                plan = plan,
                ownSlot = ownSlot,
                hostNanosNow = hostNanosNow,
                offsetNanosNow = { (clockClient.currentEstimate() ?: converged).offsetNanos },
                audioSource = audioSource(),
                edgeShares = AlignmentAnalysis.DISTANCE_EDGE_SHARES
            )
            // Counted to the instant the recording closes rather than to the last chirp: the
            // sound still has to leave the output buffer and cross the room, and somebody who
            // stands up at the last chirp has moved during the part being measured.
            show(
                string(R.string.pair_calibrate_running),
                whenItReaches(runner.timing().recordUntilHostNanos, hostNanosNow)
            )
            val run = runner.run()
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
            Log.i(LOG_TAG, json)
            // A room's delivery is this handset's hearing rather than a pair's arithmetic, and
            // the run ends here: the field belongs to the host, which is the only handset that
            // ever holds the whole room. What comes back is what the room made of this one.
            if (ownSlot != null) {
                val room = RoomResultClient(paired.address, PeerCalibrateActivity.ROOM_PORT)
                    .exchange(RoomResultMessage(plan.caseId, sinkId, ownSlot, run.arrivalsByRepeat))
                // The host offers this to everybody who delivered; whether to keep it is decided
                // here, and only here, because only this handset knows what it already carries.
                // A measurement outranks the offer and is left alone - the offer is what stands
                // in for one until somebody has a minute to walk to this phone.
                val kept = room.approximateOffsetMicros?.takeIf { stored == null }?.also {
                    runCatching { StoredApproximateCalibration(filesDir, paired.hostId).write(it) }
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
                    if (kept == null) string(
                        R.string.pair_calibrate_room_sink_done, room.handsets, room.ownPairsReadable
                    ) else string(
                        R.string.pair_calibrate_room_sink_approximate,
                        room.handsets, room.ownPairsReadable, kept / 1000.0
                    )
                )
            }
            // Delivered even when there is nothing to deliver: the host waits on this message, so
            // an empty run and a dead sink look the same from an end of a socket that never opens.
            val reply = AlignmentResultClient(paired.address, SyncActivity.RESULT_PORT)
                .exchange(plan.caseId, sinkId, appliedMicros, run.readings)
            // The experiment arm ends here, one step short of every arm that moves the constant.
            // That is what it is: the gate refuses these links because the offset a two-way
            // exchange gives on one is biased by half the difference of the one way delays, so a
            // constant folded from here would carry that bias into every session afterwards. What
            // it is for is measuring that bias acoustically, and the readings are already filed.
            if (allowSlowLink) return show(
                reply.measuredOffsetMicros?.let {
                    string(R.string.pair_calibrate_measured_only, it / 1000.0)
                } ?: string(R.string.pair_calibrate_kept, run.refusal ?: "NOT_USABLE")
            )
            // A verification measures the residual left after the stored constant is applied.
            // Writing a residual where the constant lives would halve the correction every time.
            if (verifying) return show(
                string(R.string.pair_calibrate_verified, (reply.clusterMeanMicros ?: 0L) / 1000.0)
            )
            val observed = reply.measuredOffsetMicros
                ?: return show(string(R.string.pair_calibrate_kept, run.refusal ?: "NOT_USABLE"))
            val observations = stored?.observations ?: 0
            if (!foldsIntoStoredCalibration(observations, observed, appliedMicros)) {
                return show(
                    string(R.string.pair_calibrate_not_folded, (observed - appliedMicros) / 1000.0)
                )
            }
            val folded = CalibrationUpdate.fold(appliedMicros, observations, observed)
                ?: return show(string(R.string.pair_calibrate_kept, "OFFSET_OUT_OF_RANGE"))
            StoredCalibration(filesDir, paired.hostId).write(folded, observations + 1)
            // The measurement is here now, so the guess goes. Read order alone would hide it
            // rather than remove it, and a guess nothing reads is a guess nothing checks either.
            runCatching { StoredApproximateCalibration(filesDir, paired.hostId).forget() }
            show(string(R.string.pair_calibrate_done, folded / 1000.0, observations + 1))
        } finally {
            clockThread.interrupt()
        }
    }

    private fun show(text: String, until: Long? = null) = report.say(text, until)

    /** The next four stand in for what the screen read off its intent, spelled the same way. */
    private fun distanceOnly(): Boolean = request.distanceOnly

    private fun roomAsked(): Boolean = request.room

    private fun keepFractionWhileFilling(): Boolean = request.keepFractionWhileFilling

    private fun audioSource(): CalibrationAudioSource = request.audioSource

    private fun timingFor(caseId: String?): ArmSchedule = request.timingFor(caseId)

    private fun string(id: Int, vararg args: Any?): String = context.getString(id, *args)

    /**
     * Tells the host why this handset is not going to measure.
     *
     * Only a handset that has scanned a pairing code knows an address to tell it at - one that has
     * never been paired is invisible to the host by construction, and no channel added here would
     * reach it. The role check the screen makes is not needed: this class only ever runs as a sink.
     */
    private fun tellHost(excuse: RoomExcuse) {
        val paired = PairedHost(filesDir).read() ?: return
        events.write("excuse-told ${excuse.name}")
        tellHostWhy(paired.address, COMMAND_PORT, HostIdentity(filesDir).current(), excuse)
    }

    private fun fileAttempt(label: String, json: String) {
        runCatching { PeerRunLog(filesDir).write(label, json, System.currentTimeMillis()) }
            .onFailure { Log.e(LOG_TAG, "this attempt could not be filed under $label", it) }
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
        const val LOG_TAG = "SoundMeshSinkRound"

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
