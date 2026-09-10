package com.soundmesh.product

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.soundmesh.core.AlignmentPairing
import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationReply
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.FacingPair
import com.soundmesh.core.HostId
import com.soundmesh.core.CalibrationUpdate
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.EmissionDeviation
import com.soundmesh.core.LinkQuality
import com.soundmesh.core.LinkSurvey
import com.soundmesh.core.PairedAlignment
import com.soundmesh.core.RunVerdict
import com.soundmesh.probe.R
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.AlignmentResultClient
import com.soundmesh.probe.sync.AlignmentResultServer
import com.soundmesh.probe.sync.Calibration
import com.soundmesh.probe.sync.CalibrationPlanClient
import com.soundmesh.probe.sync.CalibrationPlanServer
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.CalibrationAudioSource
import com.soundmesh.probe.sync.PeerCalibrationRunner
import com.soundmesh.probe.sync.PeerRunLog
import com.soundmesh.probe.sync.holdingRadio
import com.soundmesh.probe.sync.radioHoldOf
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.StoredSeparation
import com.soundmesh.probe.sync.SyncActivity
import java.io.File
import kotlin.math.abs

/**
 * The furthest one run may move the pair's offset and still be an observation of it.
 *
 * The whole alignment budget. A run that says the constant belongs 5 ms from where it stands is
 * not a noisy reading of the same quantity - it is a run that measured something else, most
 * likely a correlator that took the wrong peak. C1 was such a run: 46766 against a standing
 * 34511, folded, and it left the pair at 36962 with nothing in any later result to notice it by.
 *
 * The eighteen runs of 2026-09-08 measured the honest spread this has to admit: a run-level bias
 * of mean 1.444 ms and sd 0.488, worst run 2.231. This leaves that 2.2x headroom.
 */
internal const val MAX_FOLD_STEP_MICROS = 5_000L

/**
 * RunStore accepts `[A-Z][0-9]+` and never clears a directory it is handed, so a case id names a
 * place on disk rather than a run. Every letter is already spoken for by an archived series, and
 * C1 landed on top of one: the first hardware run overwrote the calibration.wav an earlier
 * alignment run had left in runs/C1 on both handsets. Ninety and up is past the end of every
 * series the harness has recorded.
 */
internal const val CASE_MEASURE = "C90"
internal const val CASE_VERIFY = "C91"

/**
 * The experiment arm, which is a run the link gate would have refused.
 *
 * Its own directory rather than a flag inside the measurement's, for the reason the two above are
 * separate: a case id is a place, and an experiment landing in runs/C90 would overwrite the
 * calibration the pair actually uses with a run taken on a link known to be too slow to align.
 *
 * A directory is also a better record of the arm than a field would be. What is wanted later is
 * "was this run allowed past the gate", and that question is answered by which folder the file is
 * in, whether or not anything inside the file was written to say so. Whether the gate *would*
 * have fired is a different question, and the link quality in the clock report answers that one.
 */
internal const val CASE_SLOW_LINK = "C92"

/**
 * The distance-only arm: a separation, and deliberately nothing else.
 *
 * Its own case rather than a flag on C90 because it writes into the same places a calibration
 * does and must be tellable apart there afterwards - its own directory under the run store, its
 * own name on every file. See [timingFor] for what it drops and [keepsCorrection] for what it
 * refuses to touch.
 */
internal const val CASE_DISTANCE = "C93"

/** SyncActivity's own, and the one AlignmentAnalysis is written around. */
internal const val STAGGER_NANOS = 500_000_000L

/**
 * Five pairs. CalibrationUpdate.usable needs a cluster of at least two, and three is not
 * enough to trust a cluster mean: O60, O61 and O62 were the same binary run back to back
 * without the phones being touched, and came out +1.323, -0.927 and +0.097 ms.
 */
internal const val CHIRP_REPEATS = 5

/**
 * Five seconds, run-sync's own floor: a slice of the recording has to hold the record
 * lead, the stagger and the sweep, with the input latency and start jitter on top.
 */
internal const val CHIRP_INTERVAL_NANOS = 5_000_000_000L

/**
 * Three pairs, against the five a calibration takes.
 *
 * Five is what a cluster mean needs, and a distance has no cluster: it is one number per pair
 * with an outlier rate the archive puts near one in six, which a median over three handles. What
 * three buys over one is that a single missed chirp costs the run a sample instead of the answer.
 */
internal const val DISTANCE_REPEATS = 3

/**
 * Two seconds, against the five second interval a calibration runs.
 *
 * The floor is [STAGGER_NANOS] plus twice the recording-start uncertainty, which is 1.5 s, and
 * the floor is where consecutive search windows touch rather than where they overlap. Two seconds
 * takes the margin instead of arguing about it, and the whole schedule is four seconds long.
 */
internal const val DISTANCE_INTERVAL_NANOS = 2_000_000_000L

/**
 * Two seconds of lead, against seven.
 *
 * Seven covers the sink's warm-up and the gap after it while the estimator is also filling a
 * window; with no window to fill, what is left is the plan's round trip and the recording opening.
 * Two seconds is the shortest lead this screen has been run at on hardware - measured 09-10 - so
 * it is a value with a run behind it rather than the smallest number that looks plausible.
 */
internal const val DISTANCE_PLAN_LEAD_NANOS = 2_000_000_000L

/**
 * Which of the three a press is, or null if it is not one of them.
 *
 * Null is the verification asked for with the gate open. A verification measures what is left
 * after the stored constant is applied, and the experiment arm is defined by never touching that
 * constant - so the combination names no run, and the alternative to saying so is picking one of
 * the two silently. A driver that passed both would then read a residual as a measurement, which
 * is the shape of the mistake that has already cost this project a day: a flag that went missing
 * swapped the arm and nothing in the result said which one had run.
 */
internal fun calibrationCase(
    verifying: Boolean,
    allowSlowLink: Boolean,
    distanceOnly: Boolean = false
): String? = when {
    // A distance measurement runs on an estimator too young to align by, on purpose, and never
    // touches the stored constant. Pairing it with either of the other two describes no run for
    // the same reason their own pairing does not, and picking one silently is the mistake this
    // whole function exists to refuse.
    distanceOnly && (verifying || allowSlowLink) -> null
    distanceOnly -> CASE_DISTANCE
    verifying && allowSlowLink -> null
    allowSlowLink -> CASE_SLOW_LINK
    verifying -> CASE_VERIFY
    else -> CASE_MEASURE
}

/**
 * The chirp schedule a case runs, and the two waits that come before it.
 *
 * One value rather than four knobs on a command line. Everything the distance arm shortens could
 * have been passed in - and then four numbers spread over two `am start`s would have had to agree
 * with each other, with nothing checking that they did. That is the shape that swapped an arm on
 * this project once and left nothing in the result to say which one had run: the case names the
 * arm, and every number the arm needs comes out of the name.
 */
internal data class CalibrationTiming(
    val repeats: Int,
    val intervalNanos: Long,
    val staggerNanos: Long,
    val planLeadNanos: Long,
    val clockFillNanos: Long
)

/**
 * What [caseId] runs, given what the command line asked for.
 *
 * Only [CASE_DISTANCE] departs from the shipped schedule, and what it drops is exactly what its
 * answer does not depend on. The separation comes out of the half difference of the two
 * recordings, where a clock error enters both sides with the same sign and cancels - so the fill
 * wait, whose whole purpose is an offset accurate enough to align by, buys the distance nothing.
 * The lead comes down with it or the saving is spent waiting, and the repeats and the interval
 * come down because a person is standing still holding a phone to their ear for the whole of it.
 *
 * What does not come down is the stagger, and under it the interval: a pair is searched for over
 * the stagger with the recording-start uncertainty added either side, so windows begin to touch
 * at stagger plus twice that. Below it a pair can be answered by its neighbour's chirp, which is
 * worse than a miss because the reading is then wrong rather than absent.
 */
internal fun timingFor(
    caseId: String?,
    requestedPlanLeadNanos: Long,
    requestedClockFillNanos: Long
): CalibrationTiming = when (caseId) {
    CASE_DISTANCE -> CalibrationTiming(
        repeats = DISTANCE_REPEATS,
        intervalNanos = DISTANCE_INTERVAL_NANOS,
        staggerNanos = STAGGER_NANOS,
        planLeadNanos = DISTANCE_PLAN_LEAD_NANOS,
        clockFillNanos = 0L
    )
    else -> CalibrationTiming(
        repeats = CHIRP_REPEATS,
        intervalNanos = CHIRP_INTERVAL_NANOS,
        staggerNanos = STAGGER_NANOS,
        planLeadNanos = requestedPlanLeadNanos,
        clockFillNanos = requestedClockFillNanos
    )
}

/**
 * Whether a finished run of [caseId] may move the standing correction at all.
 *
 * Asked before [foldsIntoStoredCalibration], and asking a different question: that one is about
 * how far a measurement may travel, this one about whether the run was ever measuring the thing.
 * A distance run's estimator is eight samples old - ample for a separation, which cancels the
 * clock, and nowhere near enough for an alignment, which is made of it. The fold exempts a pair
 * nobody has measured yet, deliberately, so on a fresh pair nothing else stands between a two
 * second run and the constant every session afterwards applies.
 */
internal fun keepsCorrection(caseId: String): Boolean = caseId != CASE_DISTANCE

/**
 * How far apart the two handsets were, from the pairs that could be read, or null if none could.
 *
 * The median rather than the mean, which the five-pair schedule could afford not to care about
 * and a three-pair one cannot: across the six archived runs that recorded separations, one pair
 * in six sat at roughly twice its neighbours, and one such pair moves a three-sample mean by a
 * third of the answer.
 *
 * Independent of the verdict on purpose. The flight time is the half difference of the two
 * recordings and the alignment is the half sum; they share the chirps and nothing else. A run
 * whose alignment will not cluster still measured the room, and this used to be written inside
 * the branch that had a cluster mean - which made the measurement that cancels the clock
 * conditional on the one that is made of it.
 */
internal fun measuredSeparationMetres(pairs: List<FacingPair?>): Double? {
    val metres = pairs.filterNotNull().map { it.separationMetres }.sorted()
    if (metres.isEmpty()) return null
    val middle = metres.size / 2
    return if (metres.size % 2 == 1) metres[middle] else (metres[middle - 1] + metres[middle]) / 2
}

/**
 * Whether a finished run may move the correction this handset carries.
 *
 * The run is already known to be readable by the time this is asked - [CalibrationUpdate.measured]
 * answers null otherwise, and [CalibrationUpdate.usable] says what readable means: a hole where a
 * pair should be, or a scatter too wide for the model, and not being wrong. **Not being wrong is
 * exactly what this must not test.**
 *
 * It used to test it. The condition here was [RunVerdict.passed], which contains
 * `|cluster mean| <= 1.0`, so the admission read the very quantity the fold estimates. On
 * 2026-09-08 eighteen runs in one unchanged configuration measured that quantity: one wide
 * distribution, mean 1.444 ms and sd 0.488. Against a 1.0 gate that admits 18.2% of runs, and
 * those runs have a mean of 0.734 - so the constant converged on 0.734, stayed 0.710 ms short of
 * the truth, and no number of further runs could move it. Two of the eighteen passed, at a mean
 * of 0.657, which is the same story counted rather than modelled. Selecting on the estimated
 * quantity biases the estimate; that is not a tuning problem, it is what the rule was.
 *
 * A window around the constant already held cuts both sides equally, so it does not. It still
 * refuses C1, which is what the passed condition was added for.
 *
 * The first run is exempt for the reason [CalibrationUpdate.usable] gives: a pair nobody has
 * measured sits tens of milliseconds out, and there is no constant for a window to be around.
 * That is also the jam this leaves open, and [StoredCalibration.forget] is its exit.
 *
 * Recorded in docs/feasibility-results/on-device-calibration.md, section 19.
 */
internal fun foldsIntoStoredCalibration(
    observations: Int,
    measuredMicros: Long,
    estimateMicros: Long
): Boolean = observations == 0 || abs(measuredMicros - estimateMicros) <= MAX_FOLD_STEP_MICROS

/**
 * The clock layer of a peer calibration report.
 *
 * File scope so it can be judged on what it produces rather than on how its source reads. The
 * assertion that went in the other way round here passed against an unrelated line while the
 * feature it named was not wired up at all.
 */
internal fun clockReportJson(
    intervalMillis: Long,
    windowSize: Int,
    bestCount: Int,
    /**
     * Which filtering rule the window was filling under, because a run cannot be read without it.
     *
     * Section 26 replaced a frozen count with a fraction and justified it entirely in replay, on
     * archived chirps that all landed after the window was full - where the two rules agree. The
     * run that fires a chirp early enough to tell them apart is the reason this field exists: two
     * such runs differ in nothing else a report records.
     */
    keepFractionWhileFilling: Boolean,
    /**
     * How long the exchange ran before anything was scheduled against it.
     *
     * Beside [keepFractionWhileFilling] because it decides whether that rule was reachable at
     * all: the two rules differ only on a chirp fired while the window is still filling, and
     * this wait is what stands between the run and that state. Measured on hardware 09-10,
     * the default put every chirp at 18 s or later of estimator life.
     */
    clockFillNanos: Long,
    radioHeld: Boolean,
    link: LinkQuality?,
    atStart: ClockEstimate?,
    atEnd: ClockEstimate?,
    exchanges: List<ClockExchange>
): String =
    "{\"intervalMillis\":$intervalMillis,\"windowSize\":$windowSize,\"bestCount\":$bestCount," +
        "\"keepFractionWhileFilling\":$keepFractionWhileFilling," +
        "\"clockFillNanos\":$clockFillNanos," +
        "\"radioHeld\":$radioHeld,\"link\":${linkJson(link)}," +
        "\"atStart\":${estimateJson(atStart)},\"atEnd\":${estimateJson(atEnd)}," +
        "\"exchanges\":${exchangesJson(exchanges)}}"

/**
 * The run as the two handsets together saw it, with each side's own emission beside it.
 *
 * Written by the host, because the host is the only place both halves exist: the sink's readings
 * arrive over the socket and are combined here, and until now that combination was never written
 * down at all - it produced one sentence on a screen and was gone. Every offline analysis of a
 * peer run has had to re-combine the two files by hand.
 *
 * The emission block is what section 21 left standing. A run's combined error is
 * `C + host emission - sink emission`, so its scatter never says which side moved; these two say.
 * They are read from the recording each handset made of itself and again from the partner's, which
 * is the check: a real emission event is seen by both microphones, and the two readings of one
 * differ by a sd of 10.4 frames across 290 archived chirps against steps of 56.
 *
 * **Reported, never judged.** Removing a handset's emission jitter from the combined value cuts a
 * run's scatter 0.704 -> 0.292 ms and the eighteen-run spread of cluster means only 0.488 -> 0.460,
 * because five chirps already average the jitter out; and it moves the constant by 0.17 ms, which
 * would be wrong to apply, since those steps are real sound leaving a real speaker. What it is for
 * is telling a run scattered by a handset from a run scattered by a link or a room.
 */
internal fun pairedReportJson(
    caseId: String,
    hostId: String,
    sinkId: String,
    combined: PairedAlignment,
    hostReadings: List<AlignmentReading>,
    sinkReadings: List<AlignmentReading>,
    intervalFrames: Int,
    /**
     * How far ahead of the request the first chirp was scheduled - the host half of the same
     * experiment [clockReportJson] records the sink half of. Every archived run used one value,
     * so nothing until now had to say which.
     */
    planLeadNanos: Long
): String {
    // The sink plays at the plan's instant and the host a stagger later, and first/second are
    // ordered by arrival, so firstIndex is the sink's chirp in either recording. See
    // CalibrationSchedule; reading it the other way round cost a day and a wrong attribution.
    val hostOwn = EmissionDeviation.of(hostReadings.map { it.secondIndex }, intervalFrames)
    val sinkOwn = EmissionDeviation.of(sinkReadings.map { it.firstIndex }, intervalFrames)
    val hostSeenBySink = EmissionDeviation.of(sinkReadings.map { it.secondIndex }, intervalFrames)
    val sinkSeenByHost = EmissionDeviation.of(hostReadings.map { it.firstIndex }, intervalFrames)
    return "{\"role\":\"HOST\",\"caseId\":\"$caseId\",\"hostId\":\"$hostId\"," +
        "\"sinkId\":\"$sinkId\",\"planLeadNanos\":$planLeadNanos," +
        "\"failure\":${combined.failure?.let { "\"${it.name}\"" } ?: "null"}," +
        "\"combinedMs\":${numbers(combined.pairs.map { it?.alignmentErrorMs })}," +
        "\"separationMetres\":${numbers(combined.pairs.map { it?.separationMetres })}," +
        "\"verdict\":${verdictJson(combined.verdict)}," +
        "\"emission\":{\"hostMs\":${numbers(hostOwn)},\"sinkMs\":${numbers(sinkOwn)}," +
        "\"hostSeenBySinkMs\":${numbers(hostSeenBySink)},\"sinkSeenByHostMs\":${numbers(sinkSeenByHost)}," +
        "\"hostSpreadMs\":${number(EmissionDeviation.spreadMs(hostOwn))}," +
        "\"sinkSpreadMs\":${number(EmissionDeviation.spreadMs(sinkOwn))}}}"
}

private fun verdictJson(verdict: RunVerdict?): String =
    verdict?.let {
        "{\"clusterMeanMs\":${number(it.clusterMeanMs)},\"clusterSdMs\":${number(it.clusterSdMs)}," +
            "\"clusterCount\":${it.clusterCount},\"outliers\":${numbers(it.outliers)}," +
            "\"maxAbsMs\":${number(it.maxAbsMs)},\"passed\":${it.passed}," +
            "\"failures\":[${it.failures.joinToString(",") { failure -> "\"${failure.name}\"" }}]}"
    } ?: "null"

private fun numbers(values: List<Double?>): String = values.joinToString(",", "[", "]") { number(it) }

/** Six decimals is a tenth of a microsecond; the quantities here are read in milliseconds. */
private fun number(value: Double?): String =
    value?.let { if (it.isFinite()) String.format(java.util.Locale.US, "%.6f", it) else "null" } ?: "null"

/**
 * A run the link gate turned away, in the shape a finished run is already written in.
 *
 * Empty pairs and a named refusal is exactly what [PeerCalibrationRunner] writes for a run that
 * played and could not be read, so one replay script reads both. Until this existed a refused run
 * wrote nothing at all: the refusal returns before the report is written, so every measurement of
 * a link too slow to align on was discarded at the moment it was made, and the archive holds 66
 * runs whose round trip median tops out at 23.8 ms against a gate set at 40. The gate was throwing
 * away the only data that could say where the gate belongs.
 */
internal fun refusedRunJson(caseId: String, refusal: String, clock: String): String =
    "{\"role\":\"SINK\",\"caseId\":\"$caseId\",\"pairs\":[],\"renderer\":null," +
        "\"refusal\":\"$refusal\",\"clock\":$clock}"

/** The run's own report with [clock] spliced in beside it, under the name the analysis reads. */
internal fun withClockReport(runJson: String, clock: String): String =
    runJson.dropLast(1) + ",\"clock\":" + clock + "}"

/**
 * Four timestamps per exchange, the same shape [SyncActivity] has always written.
 *
 * Kept the same on purpose: the harness's recorded runs and these are the same kind of evidence,
 * and one replay script should read both.
 */
private fun exchangesJson(exchanges: List<ClockExchange>): String =
    exchanges.joinToString(",", "[", "]") { "[${it.t1},${it.t2},${it.t3},${it.t4}]" }

private fun linkJson(link: LinkQuality?): String =
    link?.let {
        "{\"medianRoundTripNanos\":${it.medianRoundTripNanos}," +
            "\"p90RoundTripNanos\":${it.p90RoundTripNanos},\"samples\":${it.samples}}"
    } ?: "null"

private fun estimateJson(estimate: ClockEstimate?): String =
    estimate?.let {
        "{\"offsetNanos\":${it.offsetNanos},\"uncertaintyNanos\":${it.uncertaintyNanos}," +
            "\"driftPpm\":${it.driftPpm},\"sampleCount\":${it.sampleCount}}"
    } ?: "null"

/** What one round of serving one sink came to, as far as the loop running them is concerned. */
internal enum class RoundResult {
    /** A handset was measured. Whatever verdict it got, the round did its job. */
    SERVED,

    /** The wait for somebody to ask ran out. Nobody else is coming, so the session is over. */
    NOBODY_ASKED,

    /** This round broke. The next handset is still owed its turn. */
    FAILED
}

/**
 * Serves one sink after another off a single press, and answers how many were measured.
 *
 * Apart from what a round does, because a round is fifty seconds of chirps against a real handset
 * and this is a decision: given how the last one came out, does the next handset still get a turn.
 * Same shape as the other internal functions in this file, and for the same reason - the thing
 * worth guarding is lifted out of the thing that needs a room and two phones.
 *
 * A throw counts as a failure rather than ending the session. That is the whole point of the
 * change this belongs to: a session used to be one round, so one sink's trouble was the end of it
 * by construction, and a host measuring three handsets cannot be built that way.
 *
 * [round] is handed the number served so far, because that is what the screen counts up while
 * somebody walks across the room to the next phone.
 */
internal fun serveRounds(stopped: () -> Boolean, round: (Int) -> RoundResult): Int {
    var served = 0
    var failuresInARow = 0
    while (!stopped()) {
        when (runCatching { round(served) }.getOrDefault(RoundResult.FAILED)) {
            RoundResult.SERVED -> {
                served++
                // Consecutive, not cumulative: a room where every other handset has trouble is
                // still a room worth finishing, and counting them all would stop it partway
                // through for a reason nobody watching could see.
                failuresInARow = 0
            }
            RoundResult.NOBODY_ASKED -> return served
            RoundResult.FAILED -> {
                failuresInARow++
                if (failuresInARow >= MAX_FAILURES_IN_A_ROW) return served
            }
        }
    }
    return served
}

/**
 * How many rounds may break in a row before the session gives up.
 *
 * Bounded rather than open, because a round can fail without waiting - a request this host refuses
 * comes back at once - so an unbounded "carry on" spins one thread for as long as the screen is
 * up, which from outside looks exactly like a session that is working.
 */
internal const val MAX_FAILURES_IN_A_ROW = 3

/**
 * Measures the fixed offset between this handset and the one it is paired with, and stores it.
 *
 * The constant this produces used to take a PC, a cable and four harness runs driven from a
 * command line - which works once, for the two handsets in this room, and is exactly what the
 * product's premise rules out. `SinkSession` only ever read this file; nothing in the product
 * could write it. This is what writes it.
 *
 * The role is not asked for twice. It travels in from the home screen, which has already asked
 * which side this phone is being, because the correction is directional and two answers to one
 * question can disagree - and what a disagreement produces here is a correction filed under the
 * wrong peer, applied silently ever after with nothing in any result to notice it by.
 *
 * Nothing starts on its own, on the same terms as [CalibrateActivity]: a calibration is a minute
 * of chirps that only works with two phones left alone in a quiet room, so the screen explains
 * itself and waits to be told.
 *
 * Startable by name as well, with `auto`, because the first thing this has to do is agree with the
 * harness runs it replaces, and that comparison is driven over ADB.
 */
class PeerCalibrateActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var state by mutableStateOf(PeerCalibrateState())

    /** One calibration at a time: two would share a microphone, a port and a run directory. */
    @Volatile private var running = false

    /**
     * Set by the stop button, read between rounds.
     *
     * Between rather than inside: a round is fifty seconds of chirps whose answer only exists once
     * both halves are in, so ending one partway would throw away the measurement it was most of
     * the way through, for no gain over waiting out the minute.
     */
    @Volatile private var stopping = false

    /**
     * The plan server of a host session in flight, or null when none is serving.
     *
     * Held here only so the stop button can reach it. The run thread spends most of a session
     * parked in that server's accept(), and closing the socket is the only thing that wakes it -
     * without which the button would take up to five minutes to have any visible effect, which is
     * indistinguishable from a button that does not work.
     */
    @Volatile private var hostPlanServer: CalibrationPlanServer? = null

    /**
     * Whether the WiFi radio was actually held out of power save for this run.
     *
     * On the record rather than assumed: the lock is best effort, and a run whose lock quietly did
     * nothing looks exactly like a run that proves power save is irrelevant.
     */
    @Volatile private var radioHeld = false

    /** What the link looked like when the clock had filled its window, or null if unmeasured. */
    @Volatile private var link: LinkQuality? = null

    /** Set by the button that asked for the permission, so the run resumes once it is granted. */
    private var verifyingAfterPermission = false
    private var serveManyAfterPermission = false
    private var allowSlowLinkAfterPermission = false

    /**
     * The schedule this round is running, set the moment its case is known and read everywhere
     * afterwards - including by the report, so a run says which arm it was on rather than what
     * the command line happened to ask for.
     */
    @Volatile
    private var timing = timingFor(null, PLAN_LEAD_NANOS, CLOCK_FILL_NANOS)

    private val askRecordAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start(verifyingAfterPermission, serveManyAfterPermission, allowSlowLinkAfterPermission)
        else state = state.copy(message = getString(R.string.pair_calibrate_no_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A minute of quiet room, and a screen that sleeps takes the CPU with it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val stored = storedCalibration()
                    PeerCalibrateScreen(
                        state = state.copy(
                            role = role(),
                            stored = stored?.micros,
                            observations = stored?.observations ?: 0
                        ),
                        actions = PeerCalibrateActions(
                            calibrate = { begin(verifying = false, serveMany = true, allowSlowLink = false) },
                            verify = { begin(verifying = true, serveMany = true, allowSlowLink = false) },
                            forget = { forget() },
                            stop = { stopServing() }
                        )
                    )
                }
            }
        }
        if (intent.getBooleanExtra("auto", false)) beginFrom(intent)
    }

    /**
     * A second start reaches here rather than [onCreate], because this screen is singleTask.
     *
     * Without it the screen sits on the last run's answer and measures nothing - which is how the
     * Magic6's second output-lead reading was lost: `am start` reported success, the activity was
     * already up, and three minutes of quiet room bought nothing at all.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("auto", false)) beginFrom(intent)
    }

    /**
     * Which side of the pair this handset is on, or null if nobody has said yet.
     *
     * Handed in rather than worked out, and deliberately not derived from the pairing file: both
     * handsets in this room hold one, because they have each scanned the other at some point, so
     * "has a scanned pairing" would have made both of them the sink and no run could start. The
     * role is what this phone is being right now, which is the same question the home screen
     * already asks - [HomeScreen]'s own comment says it is kept off disk because swapping the two
     * and trying again is the most common thing anybody does - so this follows that answer instead
     * of inventing a second one that could disagree with it.
     */
    private fun role(): CalibrationRole? =
        CalibrationRole.entries.firstOrNull { it.name == intent.getStringExtra("role") }

    /**
     * The next three are read off the intent the way [role] is, rather than threaded through
     * [begin], because the screen is singleTask and onNewIntent calls setIntent: the intent is
     * always the one that started the run in progress. Threading them would have added three
     * parameters to four signatures and three more fields to hold across the permission prompt.
     *
     * All three are diagnostic arms of one experiment (queue item 16 and 20a), reachable only from
     * a command line, and each is written into the run's report by the side that honours it. None
     * changes what a listener's handset does: the defaults are the shipped constants.
     *
     * Note the flag types. `--ei` and `--ez`, not `-e`: a string extra read as an int or a boolean
     * returns the default with only a log line to say so, and the run then measures the shipped arm
     * while the command line says otherwise. That has already cost this project one silent session.
     */
    private fun planLeadNanos(): Long =
        intent.getIntExtra("plan_lead_millis", (PLAN_LEAD_NANOS / 1_000_000L).toInt()) * 1_000_000L

    /** `--ez frozen_count true` restores the pre-section-26 rule. See [ClockOffsetEstimator]. */
    private fun keepFractionWhileFilling(): Boolean = !intent.getBooleanExtra("frozen_count", false)

    /**
     * How long to let the window fill before asking for a plan. `--ei clock_fill_millis 0`
     * fires the first chirp as soon as the estimator will answer at all.
     *
     * [planLeadNanos] cannot reach this: the lead moves the first chirp relative to the plan
     * request, and this wait ends before that request is made. Section 26 found the published
     * offset biased +5.10 ms over the first four seconds and was never checked acoustically
     * because of exactly that - measured 09-10, a lead of 7000 put the first chirp at 22.9 s
     * of estimator life and a lead of 2000 at 18.0 s, both far outside the four.
     *
     * Shipping keeps the full wait, and [CLOCK_FILL_NANOS] carries why. What this opens is the
     * run that can say whether the wait is still buying anything under the section-26 rule -
     * if it is not, sixteen seconds come off every calibration.
     */
    private fun requestedClockFillNanos(): Long =
        intent.getIntExtra("clock_fill_millis", (CLOCK_FILL_NANOS / 1_000_000L).toInt()) * 1_000_000L

    /**
     * `--ez distance_only true` measures how far apart the two handsets are and nothing else.
     *
     * Command line only for now, because what reads the answer does not exist yet: the room
     * screen scales a drawing by it, and until it does, a listener offered this button would be
     * standing still for a number nothing displays. The measurement itself is a product one -
     * this is not an experiment arm and does not belong beside [allow_slow_link] in that sense.
     */
    private fun distanceOnly(): Boolean = intent.getBooleanExtra("distance_only", false)

    /**
     * The capture source, default MIC as every archived run used.
     *
     * MIC is the vendor processing chain, whose convergence is time-varying and could be landing on
     * the chirp onset; UNPROCESSED is the control. CalibrationRunner falls back if the source will
     * not open, and records which it opened, so asking is not the same as getting.
     */
    private fun audioSource(): CalibrationAudioSource =
        CalibrationAudioSource.parse(intent.getStringExtra("audio_source"))

    /**
     * The ADB-driven start, which serves one handset unless asked for more.
     *
     * One round is what every archived run of this screen did, and it is what a driver expects: a
     * host that went on serving would still be running five minutes later, and the next
     * `am start` would find [running] set and be ignored - which looks from outside like a
     * command that succeeded and measured nothing. `-e serve_many true` opts back in.
     */
    private fun beginFrom(intent: Intent) = begin(
        verifying = intent.getBooleanExtra("verify", false),
        serveMany = intent.getBooleanExtra("serve_many", false),
        // Deliberately reachable only from a command line. The gate exists because a link this
        // slow cannot be aligned by any estimator, so a listener who got past it by pressing
        // something would be handed a correction measured on a link that cannot carry one - and
        // it would then be applied to every session afterwards with nothing to notice it by.
        // What is on the other side of it is an experiment: measure the network asymmetry
        // acoustically on a link slow enough to have one worth measuring. See the roadmap.
        allowSlowLink = intent.getBooleanExtra("allow_slow_link", false)
    )

    private fun begin(verifying: Boolean, serveMany: Boolean, allowSlowLink: Boolean) {
        if (running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            verifyingAfterPermission = verifying
            serveManyAfterPermission = serveMany
            allowSlowLinkAfterPermission = allowSlowLink
            askRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        start(verifying, serveMany, allowSlowLink)
    }

    private fun start(verifying: Boolean, serveMany: Boolean, allowSlowLink: Boolean = false) {
        running = true
        // Cleared here rather than when the session ends, so a stop pressed as the last round
        // finished cannot end the next session before it has served anybody.
        stopping = false
        state = state.copy(running = true, message = getString(R.string.pair_calibrate_waiting))
        // Guarded here rather than inside: an uncaught throw on any thread takes the whole process
        // with it, and a calibration that vanishes tells whoever ran it nothing at all.
        Thread({
            runCatching {
                // Held across the whole run and on both sides. An access point buffers frames for
                // a station that is asleep, and the reply half of an exchange is as much of the
                // round trip as the request half - a host dozing costs the sink exactly the same
                // milliseconds. Whether it was actually taken is recorded, not assumed.
                holdingRadio(radioHoldOf(this), held = { radioHeld = it }) {
                    when (role()) {
                        CalibrationRole.HOST -> measureAsHost(serveMany)
                        CalibrationRole.SINK -> measureAsSink(verifying, allowSlowLink)
                        null -> show(getString(R.string.pair_calibrate_no_role))
                    }
                }
            }.onFailure {
                Log.e(LOG_TAG, "the pair calibration did not finish", it)
                show(getString(R.string.pair_calibrate_failed, it.javaClass.simpleName))
            }
            running = false
            handler.post { state = state.copy(running = false) }
        }, "SoundMeshPeerCalibrate").start()
    }

    /**
     * The host's part: serve the clock, mint the plan when asked, play, and combine the two halves.
     *
     * It stores nothing. The correction belongs to the handset that applies it, and this one does
     * not - what this produces is the answer the sink is waiting for on the socket it delivered on.
     */
    private fun measureAsHost(serveMany: Boolean) {
        val hostId = HostIdentity(filesDir).current()
        val clockServer = ClockSyncServer(SyncActivity.CLOCK_PORT)
        val resultServer = AlignmentResultServer(SyncActivity.RESULT_PORT)
        val planServer = CalibrationPlanServer(PLAN_PORT)
        // Reachable from the stop button, which ends the wait by closing the socket this thread is
        // parked in accept() on. Without that the button does nothing visible for five minutes.
        hostPlanServer = planServer
        try {
            clockServer.start()
            resultServer.start()
            planServer.start()
            // Said out loud at both ends, because these three are the same ports a session wants
            // and the second feature to ask is refused with nothing but a Java class name. A user
            // hit exactly that: calibration finished at 21:35 and a session still could not bind
            // at 21:38, and there was no way afterwards to tell whether the stop button had been
            // pressed at all. These two lines make the next occurrence answerable.
            Log.i(LOG_TAG, "the calibration servers are up: clock ${SyncActivity.CLOCK_PORT}, " +
                "result ${SyncActivity.RESULT_PORT}, plan $PLAN_PORT")
            // Opened once and held across every round, which is the whole of what one press
            // serving several handsets amounts to: they used to be opened and closed around a
            // single round, so the second sink to press start found nothing listening at all.
            val served = if (serveMany) {
                serveRounds({ stopping }) { alreadyServed ->
                    serveOneSink(hostId, planServer, resultServer, alreadyServed)
                }
            } else {
                // Spelled out rather than expressed as a loop of one, so that the path every
                // archived measurement was taken on is the same few lines it always was.
                if (serveOneSink(hostId, planServer, resultServer, 0) == RoundResult.SERVED) 1 else 0
            }
            if (served > 0) show(getString(R.string.pair_calibrate_served, served))
        } finally {
            hostPlanServer = null
            planServer.stop()
            resultServer.stop()
            clockServer.stop()
            Log.i(LOG_TAG, "the calibration servers are down; the clock port is free again")
        }
    }

    /**
     * One handset's turn: wait to be asked, mint the plan, play, and combine the two halves.
     *
     * The servers are handed in rather than opened here, because they outlive a round. A sink that
     * presses start while this host is between handsets has to find the ports already listening,
     * and that is the difference between one press serving a room and one press serving a phone.
     *
     * Nothing is stored here. The correction belongs to the handset that applies it, and this one
     * does not - what this produces is the answer the sink is waiting for on the socket it
     * delivered on.
     */
    private fun serveOneSink(
        hostId: String,
        planServer: CalibrationPlanServer,
        resultServer: AlignmentResultServer,
        alreadyServed: Int
    ): RoundResult {
        show(
            if (alreadyServed == 0) getString(R.string.pair_calibrate_waiting)
            else getString(R.string.pair_calibrate_waiting_next, alreadyServed)
        )
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
            timing = timingFor(request.caseId, planLeadNanos(), requestedClockFillNanos())
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
            // The stop button closed the socket this was waiting on, so what came back is the
            // button working rather than anything having gone wrong. The loop is about to end.
            stopping -> RoundResult.FAILED
            planServer.failureCode == CalibrationPlanServer.TIMEOUT -> {
                // Said only when nothing has been measured yet. After a handset or two this is
                // how a session ends rather than how one fails, and a failure line under two good
                // answers reads as the answers themselves being in doubt.
                if (alreadyServed == 0) {
                    show(getString(R.string.pair_calibrate_failed, CalibrationPlanServer.TIMEOUT))
                }
                RoundResult.NOBODY_ASKED
            }
            else -> {
                show(getString(R.string.pair_calibrate_failed, planServer.failureCode ?: "PLAN_LOST"))
                RoundResult.FAILED
            }
        }
        // Non-null by construction: awaitRequest answers a plan only once planFor has run.
        val sinkId = servedSink ?: run {
            show(getString(R.string.pair_calibrate_failed, "PLAN_UNSIGNED"))
            return RoundResult.FAILED
        }
        show(getString(R.string.pair_calibrate_running))
        val run = PeerCalibrationRunner(
            runStore = RunStore(filesDir),
            caseId = plan.caseId,
            role = CalibrationRole.HOST,
            plan = plan,
            hostNanosNow = { System.nanoTime() },
            audioSource = audioSource()
        ).run()
        // Written before anything is answered, so a refused run still leaves its evidence.
        // Named with the peer, not just the case: a case id names a directory this only ever
        // mkdirs, so a second sink measured on this host landed on the first one's file and
        // the directory's own mtime did not move to say so.
        File(RunStore(filesDir).prepareRun(plan.caseId), hostArtifact(sinkId)).writeText(run.json)
        fileAttempt("HOST-${plan.caseId}-$sinkId", run.json)
        Log.i(LOG_TAG, run.json)
        var outcome = getString(R.string.pair_calibrate_kept, run.refusal ?: "ONE_SIDED_RUN")
        resultServer.awaitResult(RESULT_TIMEOUT_MILLIS) { message ->
            // The delivery is signed, and this host waits on one socket that any handset in
            // the room still holding a plan can reach. Combining a stranger's readings would
            // not make a worse number, it would make a number about a pair that never ran.
            if (message.sinkId != sinkId) {
                outcome = getString(R.string.pair_calibrate_wrong_sink, shortName(message.sinkId))
                return@awaitResult CalibrationReply(null, null, null)
            }
            val combined = AlignmentPairing.combine(plan.caseId, run.readings, message)
            val metres = measuredSeparationMetres(combined.pairs)
            // Kept rather than only shown, and kept outside the verdict: the separation is the
            // half difference of the two recordings and the alignment is the half sum, so a run
            // that will not cluster still measured the room. Guarded, because a room screen's
            // check is not worth a failed calibration.
            if (metres != null) runCatching { StoredSeparation(filesDir, sinkId).write(metres) }
            outcome = combined.verdict?.clusterMeanMs?.let { mean ->
                getString(R.string.pair_calibrate_host_done, mean, metres ?: 0.0)
            } ?: metres?.takeIf { plan.caseId == CASE_DISTANCE }?.let {
                // The distance arm has no verdict to report and is not failing when it has none.
                getString(R.string.pair_calibrate_distance_done, it)
            } ?: getString(
                R.string.pair_calibrate_kept,
                combined.failure?.name ?: "NO_VERDICT"
            )
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
        record(sinkId, outcome)
        return RoundResult.SERVED
    }

    /**
     * The sink's part: converge the clock, fetch the plan, play, deliver, and fold what comes back.
     *
     * The clock comes first because the plan's instants are in the host's clock and this handset
     * cannot act on one until it can convert. The other order would leave the plan expiring inside
     * a wait it caused itself.
     */
    private fun measureAsSink(verifying: Boolean, allowSlowLink: Boolean) {
        // Named before anything is spent, and before the gate, so that a refused run can still
        // say which one it would have been - and so that the one combination that names no run is
        // turned away here rather than two minutes of clock later.
        val caseId = calibrationCase(verifying, allowSlowLink, distanceOnly())
            ?: return show(getString(R.string.pair_calibrate_failed, "ARMS_COMBINED"))
        timing = timingFor(caseId, planLeadNanos(), requestedClockFillNanos())
        val paired = PairedHost(filesDir).read()
            ?: return show(getString(R.string.pair_calibrate_no_pairing))
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
            show(getString(R.string.pair_calibrate_clock))
            val deadline = clockStartedAt + CONVERGENCE_TIMEOUT_NANOS
            while (clockClient.currentEstimate() == null && System.nanoTime() < deadline) {
                Thread.sleep(CONVERGENCE_POLL_MILLIS)
            }
            if (clockClient.currentEstimate() == null) {
                return show(getString(R.string.pair_calibrate_failed, "CLOCK_NOT_CONVERGED"))
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
            val converged = clockClient.currentEstimate()
                ?: return show(getString(R.string.pair_calibrate_failed, "CLOCK_NOT_CONVERGED"))
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
                            radioHeld,
                            link,
                            converged,
                            clockClient.currentEstimate(),
                            clockClient.recordedExchanges()
                        )
                    )
                )
                return show(getString(
                    R.string.pair_calibrate_slow_link,
                    it.medianRoundTripNanos / 1_000_000.0,
                    LinkSurvey.MAX_MEDIAN_ROUND_TRIP_NANOS / 1_000_000.0
                ))
            }
            val plan = CalibrationPlanClient(paired.address, PLAN_PORT).request(caseId, sinkId)
            // The host id is the file name the correction is stored under. A plan from somebody
            // this handset never scanned would file the answer against the wrong peer, and every
            // later session would apply it with nothing in the result to notice it by.
            if (plan.hostId != paired.hostId) {
                return show(getString(R.string.pair_calibrate_failed, "PLAN_FROM_ANOTHER_HOST"))
            }
            // Both sides file under the plan's case. A host that answered with a different one
            // would split one run across two directories with nothing in either saying so.
            if (plan.caseId != caseId) {
                return show(getString(R.string.pair_calibrate_failed, "PLAN_FOR_ANOTHER_CASE"))
            }
            show(getString(R.string.pair_calibrate_running))
            val run = PeerCalibrationRunner(
                runStore = RunStore(filesDir),
                caseId = caseId,
                role = CalibrationRole.SINK,
                plan = plan,
                // The correction is subtracted from this handset's view of host time, exactly as
                // SinkSession applies it, so a verification tests it where the product puts it.
                hostNanosNow = {
                    System.nanoTime() +
                        (clockClient.currentEstimate() ?: converged).offsetNanos -
                        appliedMicros * 1_000L
                },
                offsetNanosNow = { (clockClient.currentEstimate() ?: converged).offsetNanos },
                audioSource = audioSource()
            ).run()
            // Spliced in rather than passed to the runner: the clock belongs to this screen, and
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
                    getString(R.string.pair_calibrate_measured_only, it / 1000.0)
                } ?: getString(R.string.pair_calibrate_kept, run.refusal ?: "NOT_USABLE")
            )
            // A verification measures the residual left after the stored constant is applied.
            // Writing a residual where the constant lives would halve the correction every time.
            if (verifying) return show(
                getString(R.string.pair_calibrate_verified, (reply.clusterMeanMicros ?: 0L) / 1000.0)
            )
            val observed = reply.measuredOffsetMicros
                ?: return show(getString(R.string.pair_calibrate_kept, run.refusal ?: "NOT_USABLE"))
            val observations = stored?.observations ?: 0
            if (!foldsIntoStoredCalibration(observations, observed, appliedMicros)) {
                return show(
                    getString(R.string.pair_calibrate_not_folded, (observed - appliedMicros) / 1000.0)
                )
            }
            val folded = CalibrationUpdate.fold(appliedMicros, observations, observed)
                ?: return show(getString(R.string.pair_calibrate_kept, "OFFSET_OUT_OF_RANGE"))
            StoredCalibration(filesDir, paired.hostId).write(folded, observations + 1)
            show(getString(R.string.pair_calibrate_done, folded / 1000.0, observations + 1))
        } finally {
            clockThread.interrupt()
        }
    }

    /**
     * The constant this handset carries for the peer it is paired with, or null if it carries none.
     *
     * Read on every recomposition rather than held: the run writes it from another thread, and a
     * remembered copy would leave the screen announcing the answer it had before it measured.
     */
    private fun storedCalibration(): Calibration? =
        PairedHost(filesDir).read()?.let { StoredCalibration(filesDir, it.hostId).read() }

    /**
     * Drops this pair's constant, so the next run is adopted whole the way a first run is.
     *
     * Refused while a run is going, and that is not tidiness: the rule that decides whether a run
     * may move the constant reads the observation count, and clearing it mid-run would turn the
     * run in flight into a first run - adopted whether or not it passed.
     */
    /**
     * Ends a host session after the round in flight, rather than in the middle of one.
     *
     * Only a host has anything to stop: a sink's run is one round with nothing after it, so the
     * button is not offered there. See [stopping] for why the round in flight is left to finish.
     */
    private fun stopServing() {
        if (!running) return
        stopping = true
        show(getString(R.string.pair_calibrate_stopping))
        // What makes the button take effect now instead of at the end of the wait.
        runCatching { hostPlanServer?.stop() }
    }

    private fun forget() {
        if (running) return
        PairedHost(filesDir).read()?.let { StoredCalibration(filesDir, it.hostId).forget() }
        // Also what redraws the screen: the stored value is read from disk during composition,
        // and the message is the state change that sends it back for a fresh look.
        show(getString(R.string.pair_calibrate_forgotten))
    }

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
    /**
     * The file the host's own half of a run goes in, under the peer it ran with.
     *
     * [sinkId] has been through [HostId.isValid] by the time it reaches here, which is what makes
     * it safe in a file name: it arrived over a socket, and hexadecimal of a fixed length cannot
     * hold a path segment.
     */
    private fun hostArtifact(sinkId: String): String = "peer-calibration-$sinkId.json"

    /** Enough of a handset's name to tell two apart in a room, for a screen a person reads. */
    private fun shortName(sinkId: String): String = sinkId.take(SHORT_NAME_LENGTH)

    /**
     * Adds one sink's answer to what the screen shows, replacing that sink's previous one.
     *
     * Kept apart by the whole name and shown by the short one. Matching on what is displayed
     * would fold two handsets sharing six hexadecimal characters into one line, which is a rare
     * accident with no symptom - the second measurement would simply appear to be the first's.
     */
    private fun record(sinkId: String, outcome: String) {
        handler.post {
            state = state.copy(
                outcomes = state.outcomes.filterNot { it.sinkId == sinkId } +
                    SinkOutcome(sinkId = sinkId, name = shortName(sinkId), text = outcome)
            )
        }
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
            CLOCK_INTERVAL_MILLIS, estimator.windowSize, estimator.bestCount, estimator.keepFractionWhileFilling,
            timing.clockFillNanos, radioHeld, link, atStart, atEnd, exchanges
        )
    )

    private fun show(text: String) {
        handler.post { state = state.copy(message = text) }
    }

    private companion object {
        const val LOG_TAG = "SoundMeshPeerCalibrate"
        const val ARTIFACT = "peer-calibration.json"

        /** Why a refused run was refused, in the field a finished run names its own refusal in. */
        const val SLOW_LINK = "SLOW_LINK"

        /**
         * The plan's chirp interval in frames, which is the grid an emission is measured against.
         *
         * Derived from the plan rather than from [CHIRP_INTERVAL_NANOS]: the plan is what both
         * handsets actually played to, and a host reading its own constant would answer for a
         * schedule nobody followed if the two ever came apart.
         */
        fun chirpIntervalFrames(intervalNanos: Long): Int =
            (intervalNanos * ChirpGenerator.SAMPLE_RATE / 1_000_000_000L).toInt()

        /** Next after AlignmentResultServer's 45125. */
        /** Six hexadecimal characters: 24 bits, read by a person to tell two phones apart. */
        const val SHORT_NAME_LENGTH = 6

        const val PLAN_PORT = 45126

        /** How long the host holds the screen open waiting for somebody to pick up the other phone. */
        const val PLAN_WAIT_MILLIS = 300_000

        /**
         * How far out the plan puts the first chirp: the sink's warm-up and the gap after it, plus
         * enough for the sink to have received the plan and started. See CalibrationSchedule.
         */
        const val PLAN_LEAD_NANOS = 7_000_000_000L

        /** Long enough for the whole schedule; the exchange runs the length of the calibration. */
        const val CLOCK_SECONDS = 120

        /**
         * How often the clock is exchanged during a calibration, against the harness's own 2000.
         *
         * The estimator's window is sized in exchanges, not in seconds - sixty-four of them, of
         * which the eight quietest are kept, because round trips on one link are bimodal and a
         * quiet one is about an eighth of the traffic. Filling that window at the harness's cadence
         * takes two minutes, which the harness pays for out of its audio segment and a calibration
         * has no reason to pay at all.
         *
         * Sampling faster is safe for the quantity that matters here: the offset is the mean of the
         * kept midpoints anchored at their centroid, never extrapolated, so a drift slope fitted
         * over a shorter span cannot enter it - and the staleness that anchoring costs is half a
         * window of real drift, which a shorter window makes smaller rather than larger. What it
         * cannot rule out is quiet moments on the link being clustered in time, so that sixty-four
         * exchanges over sixteen seconds meet fewer of them than sixty-four over two minutes. That
         * shows up as a wider `uncertaintyNanos`, which every run now records for exactly this.
         */
        const val CLOCK_INTERVAL_MILLIS = 250L

        /**
         * How long the exchange runs before anything is scheduled against it: one window's worth.
         *
         * Derived rather than chosen - the window size times the cadence - because the property
         * being waited for is structural. Below a full window the estimator is still answering
         * from a growing population and its answer moves as it grows, which C1 measured at 8.1 ms
         * across twenty seconds and paid for in the whole run.
         */
        const val CLOCK_FILL_NANOS =
            ClockOffsetEstimator.DEFAULT_WINDOW * CLOCK_INTERVAL_MILLIS * 1_000_000L

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

        /** The sink's correlation pass takes seconds; this bounds a sink that died mid-run. */
        const val RESULT_TIMEOUT_MILLIS = 120_000
    }
}
