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
import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.AlignmentPairing
import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.AlignmentVerdict
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.PeerBadge
import com.soundmesh.core.CalibrationReply
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.ChirpArrival
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
import com.soundmesh.core.RoomReply
import com.soundmesh.core.RoomResultMessage
import com.soundmesh.core.RunVerdict
import com.soundmesh.probe.R
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.sync.AlignmentResultClient
import com.soundmesh.probe.sync.AlignmentResultServer
import com.soundmesh.probe.sync.Calibration
import com.soundmesh.probe.sync.CalibrationPlanClient
import com.soundmesh.core.RoomCommand
import com.soundmesh.probe.sync.CalibrationPlanServer
import com.soundmesh.probe.sync.RoomCommands
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.CalibrationAudioSource
import com.soundmesh.probe.sync.PeerCalibrationRunner
import com.soundmesh.probe.sync.PeerRunLog
import com.soundmesh.probe.sync.RoomResultClient
import com.soundmesh.probe.sync.RoomResultServer
import com.soundmesh.probe.sync.holdingRadio
import com.soundmesh.probe.sync.keepingAwake
import com.soundmesh.probe.sync.radioHoldOf
import com.soundmesh.probe.sync.RouterPoke
import com.soundmesh.probe.sync.routerPokeOf
import com.soundmesh.probe.sync.StoredApproximateCalibration
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.StoredRoomField
import com.soundmesh.probe.sync.StoredListenerDistance
import com.soundmesh.probe.sync.EventLog
import java.util.Locale
import com.soundmesh.probe.sync.StoredSeparation
import com.soundmesh.probe.sync.SyncActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

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

/**
 * The whole room in one window: every handset chirps once, everybody records all of it, and
 * one analysis pass answers every pair - including the two sinks facing each other, which a
 * host-centred round of pairs never reaches at all.
 *
 * Its own case beside [CASE_DISTANCE] rather than a wider version of it, for the reason every
 * case here has its own directory: a room writes the same kinds of file a pair does and has to
 * be tellable apart from them afterwards. It also coexists with the pair flow rather than
 * replacing it - a pair is what measures the constant a session applies, and this measures
 * where the handsets are.
 *
 * Which is why the two flows refuse each other's cases rather than tolerating them. A room ask
 * arriving at a pair host would be answered with a plan naming nobody, and the handset would
 * then look for its slot in an empty list; a pair ask joining a room would be given a slot it
 * never agreed to chirp in. Both produce a plausible schedule for a run nobody is running.
 */
internal const val CASE_ROOM = "C94"

/** SyncActivity's own, and the one AlignmentAnalysis is written around. */
internal const val STAGGER_NANOS = 500_000_000L

/**
 * How far out the plan puts the first chirp: the sink's warm-up and the gap after it, plus
 * enough for the sink to have received the plan and started. See CalibrationSchedule.
 */
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
internal const val CLOCK_INTERVAL_MILLIS = 250L

internal const val PLAN_LEAD_NANOS = 7_000_000_000L

/**
 * How long the exchange runs before anything is scheduled against it: one window's worth.
 *
 * Derived rather than chosen - the window size times the cadence - because the property
 * being waited for is structural. Below a full window the estimator is still answering
 * from a growing population and its answer moves as it grows, which C1 measured at 8.1 ms
 * across twenty seconds and paid for in the whole run.
 */
internal const val CLOCK_FILL_NANOS =
    ClockOffsetEstimator.DEFAULT_WINDOW * CLOCK_INTERVAL_MILLIS * 1_000_000L

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
    distanceOnly: Boolean = false,
    room: Boolean = false
): String? = when {
    // A room is a whole different shape of run - one window, every handset in it - and pairing
    // it with any of the three below describes no run, for the reason they do not pair with
    // each other. Named first so that asking for two things at once is refused before either
    // of them is silently picked.
    room && (verifying || allowSlowLink || distanceOnly) -> null
    room -> CASE_ROOM
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
 * The chirp schedule an arm runs, and the two waits that come before it.
 *
 * Not [com.soundmesh.core.CalibrationTiming], which is the instants one handset acts on once a
 * plan exists. This is what goes into the plan.
 *
 * One value rather than four knobs on a command line. Everything the distance arm shortens could
 * have been passed in - and then four numbers spread over two `am start`s would have had to agree
 * with each other, with nothing checking that they did. That is the shape that swapped an arm on
 * this project once and left nothing in the result to say which one had run: the case names the
 * arm, and every number the arm needs comes out of the name.
 */
internal data class ArmSchedule(
    val repeats: Int,
    val intervalNanos: Long,
    val staggerNanos: Long,
    val planLeadNanos: Long,
    val clockFillNanos: Long
)

/**
 * What [caseId] runs when the command line says nothing, which is what a listener always gets.
 *
 * Only [CASE_DISTANCE] departs from the shipped schedule, and what it drops is what its answer
 * does not depend on. The separation comes out of the half difference of the two recordings,
 * where a clock error enters both sides with the same sign and cancels - so the fill wait, whose
 * whole purpose is an offset accurate enough to align by, buys the distance nothing. The lead
 * comes down with it or the saving is spent waiting, and the repeats and the interval come down
 * because a person is standing still holding a phone to their ear for the whole of it.
 *
 * What does not come down is the stagger, and under it the interval: a pair is searched for over
 * the stagger with the recording-start uncertainty added either side, so windows begin to touch
 * at stagger plus twice that - 1.5 s. Two seconds is one notch above the floor rather than on it.
 * Swept on hardware 09-10 at 5000, 3000, 2000 and 1500 against the shipped schedule, two runs
 * each: every arm's median separation landed between 18.9 and 21.1 cm, a spread of 2.2 cm across
 * all ten runs, where what reads the answer needs 30. The floor is where the arithmetic says it
 * is, and the cost of the last notch of margin is 1.1 s.
 *
 * The lead is the one number here that is short on purpose rather than because it can be:
 * [com.soundmesh.core.CalibrationSchedule] opens the warm-up 5.5 s before the first chirp, so a
 * two second lead runs no warm-up at all. That settles the renderer's output depth compensation,
 * which the alignment is made of and the separation is not - emission jitter enters both
 * recordings with the same sign and cancels in the half difference. The same sweep shows it:
 * the short arms' alignment wandered -1.45 to +0.83 ms while the shipped schedule sat at +0.07
 * and +0.28, and their separations agreed with it to a centimetre anyway. An arm that measured
 * alignment could not make this trade.
 */
internal fun defaultTimingFor(caseId: String?): ArmSchedule = when (caseId) {
    // A room runs the distance arm's schedule, and for the distance arm's reason: what a room
    // uniquely answers is where the handsets are, and a separation is the half difference of
    // two recordings, where the clock enters both sides with the same sign and cancels. The
    // alignment numbers a room also produces are read with the same suspicion a C93's are -
    // see [keepsCorrection], which refuses to let either of them near the stored constant.
    //
    // The interval here is a floor rather than the value that runs: a room's window grows with
    // the handsets in it, and [roomIntervalNanos] is what widens it. See there for why.
    CASE_DISTANCE, CASE_ROOM -> ArmSchedule(
        repeats = DISTANCE_REPEATS,
        intervalNanos = DISTANCE_INTERVAL_NANOS,
        staggerNanos = STAGGER_NANOS,
        planLeadNanos = DISTANCE_PLAN_LEAD_NANOS,
        clockFillNanos = 0L
    )
    else -> ArmSchedule(
        repeats = CHIRP_REPEATS,
        intervalNanos = CHIRP_INTERVAL_NANOS,
        staggerNanos = STAGGER_NANOS,
        planLeadNanos = PLAN_LEAD_NANOS,
        clockFillNanos = CLOCK_FILL_NANOS
    )
}

/**
 * How far apart two repeats of a room of [slots] have to sit.
 *
 * [ArmSchedule]'s interval is the floor, and the room is what pushes it up. Every slot is
 * looked for half a second either side of where the schedule put it, so two repeats whose
 * search windows touch put one handset's chirp inside two searches at once - and the
 * correlation then tells the two apart by which is louder, which is a property of the room
 * rather than of the schedule.
 *
 * Derived at plan time rather than written down as a constant, because the room's size is not
 * known until everybody has asked. A room of three costs nothing over the floor; a room of
 * eleven pays for itself. [com.soundmesh.core.CalibrationSchedule] refuses a plan whose two
 * numbers do not fit, which is this arithmetic checked at the far end rather than trusted.
 */
internal fun roomIntervalNanos(floorNanos: Long, slots: Int, staggerNanos: Long): Long =
    maxOf(floorNanos, (slots - 1) * staggerNanos + ROOM_REPEAT_GAP_NANOS)

/**
 * The quiet between the last handset of one repeat and the first of the next.
 *
 * A whole search window either side - [com.soundmesh.core.CalibrationWindow] opens half a
 * second of uncertainty at each end - plus the chirp itself, which is 120 ms. 1.5 s is the
 * same number [com.soundmesh.core.CalibrationSchedule.GAP_NANOS] is, and for the same reason:
 * three times the radius the correlation searches.
 */
internal const val ROOM_REPEAT_GAP_NANOS = 1_500_000_000L

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
internal fun keepsCorrection(caseId: String): Boolean =
    caseId != CASE_DISTANCE && caseId != CASE_ROOM

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
/**
 * The median of what the repeats said about how far apart a pair fired, or null if none said.
 *
 * Deliberately not [measuredSeparationMetres] with a different field. That function refuses a
 * pair whose answer slides as the threshold slides, and refuses one whose repeats disagree by
 * more than 30 cm - both bounds are about a distance, in metres, and neither has been shown to
 * mean anything about this. Borrowing them would put a number past a gate that was never aimed
 * at it. This is read, compared against constants measured the long way, and only then trusted.
 */
internal fun measuredFiringOffsetMs(pairs: List<FacingPair?>): Double? {
    val millis = pairs.filterNotNull().map { it.alignmentErrorMs }
    return if (millis.isEmpty()) null else medianOf(millis)
}

/** The same, at the leading edge, which is where the other half of these readings is taken. */
internal fun measuredEdgeFiringOffsetMs(pairs: List<FacingPair?>): Double? {
    val millis = pairs.filterNotNull().mapNotNull { it.firingOffsetMs }
    return if (millis.isEmpty()) null else medianOf(millis)
}

/**
 * What this pair may be offered to the handset that is not the host, in microseconds, or null.
 *
 * The same reading as [measuredEdgeFiringOffsetMs], with the two refusals that stand between a
 * diagnostic number and one a handset will apply to every note it plays for the rest of the
 * evening. Neither threshold is new, and that is deliberate: every constant in this project that
 * was invented rather than derived or measured has had to be argued for twice.
 *
 * The spread across repeats is held to [AlignmentVerdict.MAX_SINGLE_MS] - the bound the pair flow
 * already applies to one reading of this same quantity, in the same units. It is being used more
 * strictly here than there, since a spread of two readings can be twice either one is error, and
 * that direction is the safe one: a borrowed bound that only ever refuses cannot manufacture an
 * offer, it can only withhold one.
 *
 * The size is held to [CalibrationUpdate.MAX_OFFSET_MICROS], which is derived rather than chosen -
 * beyond it the correction moves a chirp out of the window the next run would have to measure it
 * in, so a handset carrying one could never be measured again.
 *
 * What is deliberately NOT borrowed is anything [measuredSeparationMetres] refuses on. Those two
 * bounds are about a distance, in metres, and on 2026-09-13 the one pair whose distance was
 * refused still reported a firing offset - the two halves of one reading fail independently,
 * because the half difference is taken at the leading edge and the half sum at the loudest lag.
 */
internal fun offerableFiringOffsetMicros(pairs: List<FacingPair?>): Long? {
    val millis = pairs.filterNotNull().mapNotNull { it.firingOffsetMs }
    if (millis.isEmpty()) return null
    if (millis.max() - millis.min() > AlignmentVerdict.MAX_SINGLE_MS) return null
    val micros = (medianOf(millis) * 1000).roundToLong()
    return if (abs(micros) >= CalibrationUpdate.MAX_OFFSET_MICROS) null else micros
}

internal fun measuredSeparationMetres(pairs: List<FacingPair?>): Double? {
    // A pair whose answer slides when the threshold slides has not found a direct sound, and it
    // is wrong in a way the agreement test below cannot see: measured 09-11, three runs with a
    // body between the handsets agreed with themselves to 0.35 m and were 1.3 m out, while the
    // same geometry with a clear line of sight agreed to 0.07 m and was right. Across eighteen
    // pairs the two cases did not overlap - 0.05-0.52 m clear against 1.06-2.59 m blocked.
    // A null spread means nothing was swept, which is every arm but the distance one.
    val metres = pairs.filterNotNull()
        .filter { (it.separationSpreadMetres ?: 0.0) <= STEADY_ENOUGH_SPREAD_METRES }
        .map { it.separationMetres }
    if (metres.isEmpty()) return null
    val middle = medianOf(metres)
    // A stored distance is replaced, not averaged, so one unreadable run can wipe out a good
    // measurement - and this used to be guarded by the alignment verdict, by accident, which is
    // what removing that guard exposed: three pairs measured through a muted handset wrote 17.04
    // metres over a real 0.19. The pairs' own agreement is what the guard should have been all
    // along, and the bound is what reads the answer rather than what the correlator can do:
    // 30 cm, the precedence threshold's 1 ms in metres. Measured 09-10, ten good runs sat at a
    // median absolute deviation of 0 to 2.1 cm and the runs through the muted handset at 19 cm
    // and up - so this is a floor under gross failure, not a precision gate.
    if (medianOf(metres.map { abs(it - middle) }) > SEPARATION_AGREEMENT_METRES) return null
    // A negative separation is not a small distance, it is the two sides disagreeing about which
    // of them is nearer, which no room can produce.
    return middle.takeIf { it > 0.0 }
}

/**
 * The separation this run may keep on disk, or null when it measured none it can vouch for.
 *
 * Narrower than [measuredSeparationMetres] on purpose. That one answers what the run measured,
 * and this one answers whether a drawing may be called wrong on the strength of it - which is
 * the only thing a stored distance is ever used for. [RoomCheck] compares two of them as a
 * ratio at a margin of [RoomCheck.CLEARLY_LONGER], so it accuses a correct drawing as soon as
 * two distances' errors differ by that margin squared, 2.25, and by less than that as the
 * drawing grows more decisive. A distance read from the loudest lag is nowhere near that
 * bound: measured 09-11, nine readings of one unchanged two metre gap taken with a clear line
 * of sight spanned 5.11 to 11.86 m, a ratio of 2.32. Read from the first arrival the same nine
 * recordings spanned 1.97 to 2.19, a ratio of 1.11.
 *
 * A non-null spread is what says the first arrival was read, and the sweep is the same pass
 * that finds the onset. Asking the pair rather than the case id keeps the two from drifting
 * apart - the reading rule is what matters here, not which name the run was started under.
 *
 * Every run sweeps now. It used to be only the arm a command line could start, which left the
 * shipped flow keeping nothing at all: somebody pressing calibrate ran the arm that aligns, so
 * the room check sat idle rather than firing on correct drawings. The sweep moved to every arm
 * once the two readings stopped competing for the same answer - see
 * [AlignmentAnalysis.combineFacing], which takes the alignment from the loudest lag and the
 * distance from the leading edge out of one pass.
 */
/**
 * Which of a room's measured pairs are still separations when one handset was held overhead.
 *
 * The ones that handset is an end of are not: they are how far the person was from each of the
 * others, and the handset itself is about to be put back somewhere else entirely. The rest are
 * untouched by where it was held - two handsets on a table are the same distance apart whoever
 * is holding a third - so an overhead round measures those for free and there is no reason to
 * throw them away.
 *
 * Dropped rather than written somewhere else, because the field is keyed by two handsets and the
 * listener is not one. The per-handset files beside it are where those go.
 */
internal fun roomFieldToStore(
    separationMetres: Map<Pair<String, String>, Double?>,
    hostId: String,
    overhead: Boolean
): Map<Pair<String, String>, Double?> =
    if (!overhead) separationMetres
    else separationMetres.filterKeys { it.first != hostId && it.second != hostId }

/**
 * Whether what a round measured is worth putting in place of whatever is stored.
 *
 * The stored field is replaced rather than merged, because a merge would mix two layouts and
 * there is no way afterwards to tell which distance came from which. That is right, and on 09-13
 * it destroyed a good measurement: a four-handset room had been measured at 12:59, and at 13:03
 * an overhead round ran with only one handset present. An overhead round measures where a
 * listener is, so by design it keeps only the pairs the host is not an end of - and with one
 * handset present there were none. An empty field replaced six good distances, and the drawing
 * that is fitted from them then had three edges for four handsets, which does not hold a shape.
 *
 * Replacing something with nothing is not an observation, it is the absence of one.
 */
internal fun saysSomethingAboutTheRoom(toStore: Map<Pair<String, String>, Double?>): Boolean =
    toStore.any { it.value != null }

internal fun separationToStore(pairs: List<FacingPair?>): Double? =
    if (pairs.filterNotNull().none { it.separationSpreadMetres != null }) null
    else measuredSeparationMetres(pairs)

/** How far apart the pairs of one run may sit and still be read as one distance. */
internal const val SEPARATION_AGREEMENT_METRES = 0.30

/**
 * How far a pair may move across the threshold sweep and still be used.
 *
 * Set between the two measured populations rather than at either edge, and deliberately nearer
 * the bad one: the cost is not symmetric. Rejecting a good pair costs a repeat - seventeen
 * seconds - while accepting a bad one writes a distance that is metres out and says nothing.
 * At 1.0 m the 09-11 samples rejected 5 of 36 good pairs and all 9 bad ones, with nothing
 * missed.
 */
internal const val STEADY_ENOUGH_SPREAD_METRES = 1.0

private fun medianOf(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
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
        // Beside the answer, not instead of it: a pair that was left out of the stored distance
        // still has to be readable afterwards, or a run that measured nothing looks the same as a
        // run that measured something and was overruled.
        "\"separationSpreadMetres\":${numbers(combined.pairs.map { it?.separationSpreadMetres })}," +
        "\"verdict\":${verdictJson(combined.verdict)}," +
        "\"emission\":{\"hostMs\":${numbers(hostOwn)},\"sinkMs\":${numbers(sinkOwn)}," +
        "\"hostSeenBySinkMs\":${numbers(hostSeenBySink)},\"sinkSeenByHostMs\":${numbers(sinkSeenByHost)}," +
        "\"hostSpreadMs\":${number(EmissionDeviation.spreadMs(hostOwn))}," +
        "\"sinkSpreadMs\":${number(EmissionDeviation.spreadMs(sinkOwn))}}}"
}

/**
 * What a room measured: how far apart every pair in it turned out to be.
 *
 * Keyed by name rather than by slot, because a slot is what the analysis speaks and a name is
 * what everything after it does - and the plan is the only thing holding both. A pair that could
 * not be read is present with a null rather than absent, so a room that answered fewer pairs
 * than it has is never mistaken for a room with fewer handsets in it.
 *
 * [repeats] is how many windows every one of them was measured over, which is the smallest any
 * handset delivered: a pair is only as repeated as the less-heard of its two ends.
 */
internal data class RoomField(
    val separationMetres: Map<Pair<String, String>, Double?>,
    /**
     * How far apart each pair fired, in milliseconds - the other half of the same readings.
     *
     * The half difference of the two directions is the flight time and the half sum is this, so
     * a round that measured the room measured this at the same instant, off the same chirps. It
     * is carried out rather than dropped because the only other way to learn it costs a minute
     * per handset with somebody walking to each one - and on 2026-09-13 two handsets nobody had
     * walked to played a whole afternoon tens of milliseconds out, found by ear.
     *
     * Diagnostic until it is checked against handsets whose constant was measured properly: it
     * is folded by the median alone, with none of the refusals [measuredSeparationMetres]
     * applies, and the half sum takes the loudest lag where the half difference takes the
     * leading edge - which is the half that has never been looked at.
     */
    val alignmentErrorMs: Map<Pair<String, String>, Double?>,
    /**
     * The same half sum read off the leading edge instead of the loudest lag.
     *
     * Both are carried because 09-13 measured the loudest-lag one missing closure by 1.35 to
     * 6.45 ms - a firing offset is a property of three handsets at once, so (A,B) + (B,C) has
     * to equal (A,C), and that test needs no reference constant at all. Closing is necessary
     * and not sufficient: a consistently wrong reading closes too, which is why the answer is
     * still checked against constants measured the long way.
     */
    val edgeFiringOffsetMs: Map<Pair<String, String>, Double?>,
    /**
     * The same reading again, in microseconds, for the pairs it may actually be handed out for.
     *
     * Kept beside the diagnostic maps rather than derived from them, because the refusals in
     * [offerableFiringOffsetMicros] read the repeats and the median has already thrown those away.
     * A null here against a number in [edgeFiringOffsetMs] is the interesting case: the pair was
     * read and the reading was not steady enough to act on.
     */
    val offerableOffsetMicros: Map<Pair<String, String>, Long?>,
    val repeats: Int
)

/**
 * Turns what every handset heard into the room's distances.
 *
 * One pass per repeat, then the repeats folded per pair by [measuredSeparationMetres] - the same
 * function, with the same median and the same two refusals, that a pair's run already answers
 * with. A room is more pairs of the same measurement, not a different one, and a second way of
 * reading it would be free to disagree with the first with nothing to notice it by.
 *
 * [heardBySlot] is keyed by the slot the plan gave each handset, which is also the name
 * [com.soundmesh.core.AlignmentAnalysis.facingPairs] answers in, and the only place the two are
 * translated is here.
 */
internal fun roomField(
    plan: CalibrationPlan,
    heardBySlot: Map<Int, List<List<ChirpArrival?>>>,
    edgeShares: List<Double>
): RoomField {
    val repeats = heardBySlot.values.minOfOrNull { it.size } ?: 0
    val slotFrames = (plan.staggerNanos * ChirpGenerator.SAMPLE_RATE / 1_000_000_000L).toInt()
    val byPair = LinkedHashMap<Pair<Int, Int>, MutableList<FacingPair?>>()
    for (repeat in 0 until repeats) {
        val answered = AlignmentAnalysis.facingPairs(
            heardBySlot.mapValues { it.value[repeat] },
            slotFrames,
            ChirpGenerator.SAMPLE_RATE,
            edgeShares
        )
        for (entry in answered) byPair.getOrPut(entry.key) { ArrayList() }.add(entry.value)
    }
    val metres = LinkedHashMap<Pair<String, String>, Double?>()
    val firing = LinkedHashMap<Pair<String, String>, Double?>()
    val edgeFiring = LinkedHashMap<Pair<String, String>, Double?>()
    val offerable = LinkedHashMap<Pair<String, String>, Long?>()
    for (entry in byPair) {
        val names = plan.slotIds[entry.key.first] to plan.slotIds[entry.key.second]
        metres[names] = measuredSeparationMetres(entry.value)
        firing[names] = measuredFiringOffsetMs(entry.value)
        edgeFiring[names] = measuredEdgeFiringOffsetMs(entry.value)
        offerable[names] = offerableFiringOffsetMicros(entry.value)
    }
    return RoomField(metres, firing, edgeFiring, offerable, repeats)
}

/**
 * What this round can offer [peerId] as an approximate correction against [hostId], or null.
 *
 * Signed the way the handset that applies it needs it. A pair answers the quantity
 * `e(second) - e(first)` - see [AlignmentAnalysis.facingPairs], which hands the later slot to
 * `combineFacing` as the host - and a stored correction is the same quantity with the host
 * second. The host takes the last slot in every room this builds, so the key is already that way
 * round; the other branch is there because a sign that is right by arrangement rather than by
 * construction is one refactor away from being silently backwards, and backwards here doubles
 * the error instead of removing it.
 */
internal fun offeredTo(field: RoomField, hostId: String, peerId: String): Long? {
    if (peerId == hostId) return null
    for (entry in field.offerableOffsetMicros) {
        val micros = entry.value ?: continue
        if (entry.key.first == peerId && entry.key.second == hostId) return micros
        if (entry.key.first == hostId && entry.key.second == peerId) return -micros
    }
    return null
}

/** How many of the room's answered pairs [peerId] is one end of. */
internal fun pairsReadableFor(field: RoomField, peerId: String): Int =
    field.separationMetres.count { (names, metres) ->
        metres != null && (names.first == peerId || names.second == peerId)
    }

/**
 * The room's report, filed by the host because it is the only handset that ever holds the room.
 *
 * [heardFrom] names who actually delivered rather than who was scheduled, because those are
 * different facts and a room that lost a handset has to say which one. The slot list is written
 * out whole for the same reason it is in the plan: a reader given only a count would have to
 * assume an order.
 */
internal fun roomReportJson(
    plan: CalibrationPlan,
    field: RoomField,
    heardFrom: List<String>,
    planLeadNanos: Long
): String =
    "{\"role\":\"HOST\",\"caseId\":\"${plan.caseId}\",\"hostId\":\"${plan.hostId}\"," +
        "\"planLeadNanos\":$planLeadNanos,\"staggerNanos\":${plan.staggerNanos}," +
        "\"chirpIntervalNanos\":${plan.intervalNanos},\"repeats\":${field.repeats}," +
        "\"slotIds\":[" + plan.slotIds.joinToString(",") { "\"$it\"" } + "]," +
        "\"heardFrom\":[" + heardFrom.joinToString(",") { "\"$it\"" } + "]," +
        "\"pairs\":[" + field.separationMetres.entries.joinToString(",") { entry ->
            "{\"a\":\"${entry.key.first}\",\"b\":\"${entry.key.second}\"," +
                "\"separationMetres\":${number(entry.value)}}"
        } + "]}"

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

    /** One timeline, shared with every other part of the app. See [EventLog]. */
    private val events: EventLog by lazy { EventLog(filesDir) }
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
    private var timing = defaultTimingFor(null)

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
                            approximate = approximateCalibration(),
                            observations = stored?.observations ?: 0
                        ),
                        actions = PeerCalibrateActions(
                            calibrate = { begin(verifying = false, serveMany = true, allowSlowLink = false) },
                            verify = { begin(verifying = true, serveMany = true, allowSlowLink = false) },
                            forget = { forget() },
                            stop = { stopServing() },
                            measureRoom = { restartAsRoom(overhead = false) },
                            measureOverhead = { restartAsRoom(overhead = true) }
                        )
                    )
                }
            }
        }
        if (intent.getBooleanExtra("auto", false)) beginFrom(intent)
    }

    /**
     * Starts a room round by handing this screen a fresh intent instead of a fresh argument.
     *
     * The intent is this class's record of which arm is running: [roomAsked] and [overhead] are
     * read off it at five places between naming the case and filing the answer, and onNewIntent
     * already replaces it. Threading two more flags through begin() would have put a second
     * answer to "which arm is this" beside the one that exists, which is the fault [which arm
     * ran] keeps costing this project a session at a time.
     */
    private fun restartAsRoom(overhead: Boolean) {
        if (running) return
        startActivity(
            Intent(this, PeerCalibrateActivity::class.java)
                .putExtra("role", role()?.name)
                .putExtra("room", true)
                .putExtra("overhead", overhead)
                .putExtra("auto", true)
        )
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
    /**
     * The schedule this round runs: the arm's own defaults, with anything the command line asked
     * for on top.
     *
     * Every number here is reachable, and every one of them is written into the report by the side
     * that honours it. A knob that cannot be turned cannot be swept, and the interval is the one
     * whose floor had to be found by sweeping - the arithmetic said 1.5 s and the handsets said
     * otherwise.
     */
    private fun timingFor(caseId: String?): ArmSchedule {
        val shipped = defaultTimingFor(caseId)
        return shipped.copy(
            repeats = intent.getIntExtra("chirp_repeats", shipped.repeats),
            intervalNanos = millisExtra("chirp_interval_millis", shipped.intervalNanos),
            planLeadNanos = millisExtra("plan_lead_millis", shipped.planLeadNanos),
            clockFillNanos = millisExtra("clock_fill_millis", shipped.clockFillNanos)
        )
    }

    private fun millisExtra(name: String, fallbackNanos: Long): Long =
        intent.getIntExtra(name, (fallbackNanos / 1_000_000L).toInt()) * 1_000_000L

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
     * `--ez room true` measures the whole room in one window instead of one pair at a time.
     *
     * On screen since 09-11, under "几台一起量": the room panel draws the lengths and moves the
     * icons onto them, so the answer this produces is now something a person can look at.
     * [restartAsRoom] is how the button reaches this, and the command line still does too.
     *
     * Needed on every handset in the room: the host gathers under it and each sink asks under
     * it. A handset left without it runs the pair arm, and the two flows refuse each other's
     * cases rather than producing a plausible schedule for a run nobody is running. That is why
     * the button is on both roles' screens and the wording tells everybody to press it.
     */
    private fun roomAsked(): Boolean = intent.getBooleanExtra("room", false)

    /**
     * `--ez overhead true` says this handset is being held above somebody's head, not standing
     * where it will play.
     *
     * It changes nothing about the measurement and everything about where the answer is filed.
     * The distances this handset is an end of are the listener's - the one thing in the room
     * nobody has ever been able to measure - and the ones it is not an end of are ordinary
     * separations, unaffected by where this handset happens to be. Filed together they would be
     * the same two names meaning two different things, and the second round would erase the first.
     *
     * Only the handset being held needs it: it is the only one that writes anything, and only
     * the host can be it - so the button that sets this is offered to the host alone.
     */
    private fun overhead(): Boolean = intent.getBooleanExtra("overhead", false)

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
                    // The lock above is not enough on its own; what keeps a radio awake is having
                    // something to receive. See keepingAwake for the three numbers that say so.
                    val poke: RouterPoke? = routerPokeOf(this)
                    // Said out loud because a poke that reaches nobody measures exactly like no
                    // poke at all: the first version of this aimed at an unreachable gateway and
                    // a whole round went by looking like evidence that power save does not matter.
                    keepingAwake(poke, answered = { answered ->
                        events.write(
                            when {
                                poke == null || answered == null ->
                                    "radio-awake: this handset named no IPv4 router, so nothing is keeping it awake"
                                answered -> "radio-awake: " + poke.router.hostAddress + " answered"
                                else -> "radio-awake: " + poke.router.hostAddress + " did not answer"
                            }
                        )
                    }) {
                        when (role()) {
                            // A room and a pair are different runs rather than a wider and a
                            // narrower one: this host gathers everybody and hands out one
                            // schedule naming all of them, where the pair host serves a queue.
                            CalibrationRole.HOST ->
                                if (roomAsked()) measureAsRoom() else measureAsHost(serveMany)
                            CalibrationRole.SINK -> measureAsSink(verifying, allowSlowLink)
                            null -> show(getString(R.string.pair_calibrate_no_role))
                        }
                    }
                }
            }.onFailure {
                Log.e(LOG_TAG, "the pair calibration did not finish", it)
                events.write("calibration-failed ${it.javaClass.simpleName}: ${it.message}")
                show(getString(R.string.pair_calibrate_failed, it.javaClass.simpleName))
            }
            running = false
            handler.post {
                state = state.copy(running = false)
                putItselfAway()
            }
        }, "SoundMeshPeerCalibrate").start()
    }

    /**
     * Goes back where it came from, for a round nobody opened this screen to watch.
     *
     * A handset that got here because its host said so has to leave the same way. Left sitting on
     * a finished result it is holding the clock port - which the next round and the next session
     * both need - and it is not standing by either, because the channel only lives on the home
     * screen. So the host asks the room to measure again and nothing answers, then asks it to
     * play and nothing answers, and every one of those looks like the network. Reported on 09-11
     * as a room that measured once and then would not do anything at all.
     *
     * A few seconds late, so whatever the round has to say is on screen long enough to read.
     */
    private fun putItselfAway() {
        if (!intent.getBooleanExtra("sent", false)) return
        handler.postDelayed({ if (!running) finish() }, LINGER_MILLIS)
    }

    /**
     * A round nobody is looking at is a round nobody wants.
     *
     * Without this, leaving this screen with the back button leaves the run going: it holds the
     * clock port, the room port and the plan port, and the next thing to want any of them - a
     * session on the home screen, or a second round from a fresh instance of this screen - fails
     * to bind. The screen it fails on is a new instance with nothing running, so it says there is
     * no calibration in flight while the ports say otherwise. That is 09-11, exactly: "回到主页,
     * 点击播放,显示放不了,让我停止对时。但是我到对时页面发现并没有正在对时".
     */
    override fun onDestroy() {
        if (isFinishing) stopServing()
        super.onDestroy()
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
     * The room's part: gather everybody, hand out one schedule, chirp in this handset's own
     * slot, then combine every pair out of what they all heard.
     *
     * Not [measureAsHost] with more handsets in it. That one serves a queue - one sink asks,
     * gets a plan naming the two of them, and is answered before the next is let in. A room's
     * schedule names every slot, so it does not exist until everybody has asked; and the pair
     * between two sinks is made of two deliveries that neither of them can combine.
     */
    private fun measureAsRoom() {
        val hostId = HostIdentity(filesDir).current()
        val clockServer = ClockSyncServer(SyncActivity.CLOCK_PORT)
        val roomServer = RoomResultServer(ROOM_PORT)
        val planServer = CalibrationPlanServer(PLAN_PORT)
        // Reachable from the stop button, which ends the wait by closing the socket this
        // thread is parked in accept() on.
        hostPlanServer = planServer
        try {
            clockServer.start()
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
            val told = RoomCommands.send(
                if (overhead()) RoomCommand.MEASURE_OVERHEAD else RoomCommand.MEASURE_ROOM
            )
            Log.i(
                LOG_TAG,
                "the room servers are up: clock ${SyncActivity.CLOCK_PORT}, " +
                    "room $ROOM_PORT, plan $PLAN_PORT"
            )
            show(
                if (told == 0) getString(R.string.pair_calibrate_room_told_nobody)
                else getString(R.string.pair_calibrate_room_waiting, told, ROOM_WINDOW_MILLIS / 1000)
            )
            events.write(
                "room-gathering opened as ${if (overhead()) "overhead" else "room"}, " +
                    "told $told handsets, waiting up to ${ROOM_WINDOW_MILLIS / 1000}s"
            )
            timing = timingFor(CASE_ROOM)
            val plan = planServer.awaitRoom(
                PLAN_WAIT_MILLIS,
                ROOM_SETTLE_MILLIS,
                ROOM_WINDOW_MILLIS,
                onJoined = { joined ->
                    events.write("room-joined $joined of $told told")
                    show(getString(R.string.pair_calibrate_room_joined, joined, told))
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
            } ?: return show(
                if (stopping) getString(R.string.pair_calibrate_stopping)
                else getString(R.string.pair_calibrate_failed, planServer.failureCode ?: "ROOM_LOST")
            )
            show(getString(R.string.pair_calibrate_running))
            val ownSlot = plan.slotIds.indexOf(hostId)
            val run = PeerCalibrationRunner(
                runStore = RunStore(filesDir),
                caseId = plan.caseId,
                role = CalibrationRole.HOST,
                plan = plan,
                ownSlot = ownSlot,
                hostNanosNow = { System.nanoTime() },
                audioSource = audioSource(),
                edgeShares = AlignmentAnalysis.DISTANCE_EDGE_SHARES
            ).run()
            // Written before anything is combined, so a room that loses everybody still leaves
            // this handset's own hearing of it on disk.
            File(RunStore(filesDir).prepareRun(plan.caseId), hostArtifact(hostId)).writeText(run.json)
            fileAttempt("HOST-${plan.caseId}-$hostId", run.json)
            Log.i(LOG_TAG, run.json)
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
            val toStore = roomFieldToStore(field.separationMetres, hostId, overhead())
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
                    if (overhead()) StoredListenerDistance(filesDir, peer).write(metres)
                    else StoredSeparation(filesDir, peer).write(metres)
                }
            }
            show(getString(
                R.string.pair_calibrate_room_done,
                plan.slotIds.size,
                field.separationMetres.count { it.value != null },
                field.separationMetres.size
            ))
        } finally {
            hostPlanServer = null
            planServer.stop()
            roomServer.stop()
            clockServer.stop()
            events.write("room-ports released")
            Log.i(LOG_TAG, "the room servers are down; the clock port is free again")
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
            audioSource = audioSource(),
            edgeShares = AlignmentAnalysis.DISTANCE_EDGE_SHARES
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
            // check is not worth a failed calibration. Shown and kept are two questions: what
            // this run measured goes on the screen for the person who ran it, and only what it
            // can vouch for goes on disk for a check nobody will be watching.
            val keep = separationToStore(combined.pairs)
            if (keep != null) runCatching {
                if (overhead()) StoredListenerDistance(filesDir, sinkId).write(keep)
                else StoredSeparation(filesDir, sinkId).write(keep)
            }
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
        val caseId = calibrationCase(verifying, allowSlowLink, distanceOnly(), roomAsked())
            ?: return show(getString(R.string.pair_calibrate_failed, "ARMS_COMBINED"))
        timing = timingFor(caseId)
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
            // Which chirp of the window is this handset's, straight out of the plan that named
            // it. A room the host built without this handset in it is not a room this handset
            // can read: it would have no anchor to read its own recording against.
            var ownSlot: Int? = null
            if (plan.caseId == CASE_ROOM) {
                val slot = plan.slotIds.indexOf(sinkId)
                if (slot < 0) {
                    return show(getString(R.string.pair_calibrate_failed, "ROOM_WITHOUT_THIS_HANDSET"))
                }
                ownSlot = slot
            }
            show(getString(R.string.pair_calibrate_running))
            val run = PeerCalibrationRunner(
                runStore = RunStore(filesDir),
                caseId = caseId,
                role = CalibrationRole.SINK,
                plan = plan,
                ownSlot = ownSlot,
                // The correction is subtracted from this handset's view of host time, exactly as
                // SinkSession applies it, so a verification tests it where the product puts it.
                hostNanosNow = {
                    System.nanoTime() +
                        (clockClient.currentEstimate() ?: converged).offsetNanos -
                        appliedMicros * 1_000L
                },
                offsetNanosNow = { (clockClient.currentEstimate() ?: converged).offsetNanos },
                audioSource = audioSource(),
                edgeShares = AlignmentAnalysis.DISTANCE_EDGE_SHARES
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
            // A room's delivery is this handset's hearing rather than a pair's arithmetic, and
            // the run ends here: the field belongs to the host, which is the only handset that
            // ever holds the whole room. What comes back is what the room made of this one.
            if (ownSlot != null) {
                val room = RoomResultClient(paired.address, ROOM_PORT)
                    .exchange(RoomResultMessage(plan.caseId, sinkId, ownSlot, run.arrivalsByRepeat))
                // The host offers this to everybody who delivered; whether to keep it is decided
                // here, and only here, because only this handset knows what it already carries.
                // A measurement outranks the offer and is left alone - the offer is what stands
                // in for one until somebody has a minute to walk to this phone.
                val kept = room.approximateOffsetMicros?.takeIf { stored == null }?.also {
                    runCatching { StoredApproximateCalibration(filesDir, paired.hostId).write(it) }
                }
                return show(
                    if (kept == null) getString(
                        R.string.pair_calibrate_room_sink_done, room.handsets, room.ownPairsReadable
                    ) else getString(
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
            // The measurement is here now, so the guess goes. Read order alone would hide it
            // rather than remove it, and a guess nothing reads is a guess nothing checks either.
            runCatching { StoredApproximateCalibration(filesDir, paired.hostId).forget() }
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

    /** What a room round left for this peer when nobody had measured it, read the same way. */
    private fun approximateCalibration(): Long? =
        PairedHost(filesDir).read()?.let { StoredApproximateCalibration(filesDir, it.hostId).read() }

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
        PairedHost(filesDir).read()?.let {
            StoredCalibration(filesDir, it.hostId).forget()
            // Both, because this button means "as if this pair had never been measured", and a
            // handset that quietly carried on correcting off a room round would not be that.
            StoredApproximateCalibration(filesDir, it.hostId).forget()
        }
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
    /**
     * What a handset is called on this screen: its number, the same one the room draws on it.
     *
     * Six characters of the real name was what this showed, against the room's four, and the
     * two were never reconciled - so one screen's handset and the other screen's handset looked
     * like different strings to anybody comparing them. The colour that completes this name
     * cannot reach here: pairing runs over its own channel, between two handsets, with no room
     * around them to have assigned one.
     */
    private fun shortName(sinkId: String): String = "${PeerBadge.numberOf(sinkId)}"

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
        const val PLAN_PORT = 45126

        /** How long the host holds the screen open waiting for somebody to pick up the other phone. */
        const val PLAN_WAIT_MILLIS = 300_000

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

        /** Next after the plan's 45126, and held only while a room is being measured. */
        const val ROOM_PORT = 45127

        /**
         * How long a round started by the host stays on screen before it puts itself away.
         *
         * Long enough to read what it says and short enough that nobody is waiting on it. The
         * host reports the whole room anyway; this is only the one handset saying how it got on.
         */
        const val LINGER_MILLIS = 3_000L

        /**
         * How long the room waits between one handset asking and the next, before deciding
         * nobody else is coming.
         *
         * The gap between two people pressing two buttons, not anything about the link. Short
         * enough that a room of two is not held open pointlessly, long enough that somebody
         * reaching across a table for the third phone is not left out.
         */
        const val ROOM_SETTLE_MILLIS = 8_000

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
