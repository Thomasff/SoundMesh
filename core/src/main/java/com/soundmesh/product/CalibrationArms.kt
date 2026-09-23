package com.soundmesh.product

import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.AlignmentVerdict
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.FacingPair
import com.soundmesh.core.CalibrationUpdate
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.EmissionDeviation
import com.soundmesh.core.LinkQuality
import com.soundmesh.core.PairedAlignment
import com.soundmesh.core.RunVerdict
import com.soundmesh.probe.sync.StoredCalibration
import java.util.Locale
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
const val MAX_FOLD_STEP_MICROS = 5_000L

/**
 * RunStore accepts `[A-Z][0-9]+` and never clears a directory it is handed, so a case id names a
 * place on disk rather than a run. Every letter is already spoken for by an archived series, and
 * C1 landed on top of one: the first hardware run overwrote the calibration.wav an earlier
 * alignment run had left in runs/C1 on both handsets. Ninety and up is past the end of every
 * series the harness has recorded.
 */
const val CASE_MEASURE = "C90"
const val CASE_VERIFY = "C91"

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
const val CASE_SLOW_LINK = "C92"

/**
 * The distance-only arm: a separation, and deliberately nothing else.
 *
 * Its own case rather than a flag on C90 because it writes into the same places a calibration
 * does and must be tellable apart there afterwards - its own directory under the run store, its
 * own name on every file. See [timingFor] for what it drops and [keepsCorrection] for what it
 * refuses to touch.
 */
const val CASE_DISTANCE = "C93"

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
const val CASE_ROOM = "C94"

/** SyncActivity's own, and the one AlignmentAnalysis is written around. */
const val STAGGER_NANOS = 500_000_000L

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
const val CLOCK_INTERVAL_MILLIS = 250L

const val PLAN_LEAD_NANOS = 7_000_000_000L

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

/**
 * Five pairs. CalibrationUpdate.usable needs a cluster of at least two, and three is not
 * enough to trust a cluster mean: O60, O61 and O62 were the same binary run back to back
 * without the phones being touched, and came out +1.323, -0.927 and +0.097 ms.
 */
const val CHIRP_REPEATS = 5

/**
 * Five seconds, run-sync's own floor: a slice of the recording has to hold the record
 * lead, the stagger and the sweep, with the input latency and start jitter on top.
 */
const val CHIRP_INTERVAL_NANOS = 5_000_000_000L

/**
 * Three pairs, against the five a calibration takes.
 *
 * Five is what a cluster mean needs, and a distance has no cluster: it is one number per pair
 * with an outlier rate the archive puts near one in six, which a median over three handles. What
 * three buys over one is that a single missed chirp costs the run a sample instead of the answer.
 */
const val DISTANCE_REPEATS = 3

/**
 * Two seconds, against the five second interval a calibration runs.
 *
 * The floor is [STAGGER_NANOS] plus twice the recording-start uncertainty, which is 1.5 s, and
 * the floor is where consecutive search windows touch rather than where they overlap. Two seconds
 * takes the margin instead of arguing about it, and the whole schedule is four seconds long.
 */
const val DISTANCE_INTERVAL_NANOS = 2_000_000_000L

/**
 * Two seconds of lead, against seven.
 *
 * Seven covers the sink's warm-up and the gap after it while the estimator is also filling a
 * window; with no window to fill, what is left is the plan's round trip and the recording opening.
 * Two seconds is the shortest lead this screen has been run at on hardware - measured 09-10 - so
 * it is a value with a run behind it rather than the smallest number that looks plausible.
 */
const val DISTANCE_PLAN_LEAD_NANOS = 2_000_000_000L

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
fun calibrationCase(
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
data class ArmSchedule(
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
fun defaultTimingFor(caseId: String?): ArmSchedule = when (caseId) {
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
fun roomIntervalNanos(floorNanos: Long, slots: Int, staggerNanos: Long): Long =
    maxOf(floorNanos, (slots - 1) * staggerNanos + ROOM_REPEAT_GAP_NANOS)

/**
 * The quiet between the last handset of one repeat and the first of the next.
 *
 * A whole search window either side - [com.soundmesh.core.CalibrationWindow] opens half a
 * second of uncertainty at each end - plus the chirp itself, which is 120 ms. 1.5 s is the
 * same number [com.soundmesh.core.CalibrationSchedule.GAP_NANOS] is, and for the same reason:
 * three times the radius the correlation searches.
 */
const val ROOM_REPEAT_GAP_NANOS = 1_500_000_000L

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
fun keepsCorrection(caseId: String): Boolean =
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
fun measuredFiringOffsetMs(pairs: List<FacingPair?>): Double? {
    val millis = pairs.filterNotNull().map { it.alignmentErrorMs }
    return if (millis.isEmpty()) null else medianOf(millis)
}

/** The same, at the leading edge, which is where the other half of these readings is taken. */
fun measuredEdgeFiringOffsetMs(pairs: List<FacingPair?>): Double? {
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
fun offerableFiringOffsetMicros(pairs: List<FacingPair?>): Long? {
    val millis = pairs.filterNotNull().mapNotNull { it.firingOffsetMs }
    if (millis.isEmpty()) return null
    if (millis.max() - millis.min() > AlignmentVerdict.MAX_SINGLE_MS) return null
    val micros = (medianOf(millis) * 1000).roundToLong()
    return if (abs(micros) >= CalibrationUpdate.MAX_OFFSET_MICROS) null else micros
}

fun measuredSeparationMetres(pairs: List<FacingPair?>): Double? {
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
fun roomFieldToStore(
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
fun saysSomethingAboutTheRoom(toStore: Map<Pair<String, String>, Double?>): Boolean =
    toStore.any { it.value != null }

fun separationToStore(pairs: List<FacingPair?>): Double? =
    if (pairs.filterNotNull().none { it.separationSpreadMetres != null }) null
    else measuredSeparationMetres(pairs)

/** How far apart the pairs of one run may sit and still be read as one distance. */
const val SEPARATION_AGREEMENT_METRES = 0.30

/**
 * How far a pair may move across the threshold sweep and still be used.
 *
 * Set between the two measured populations rather than at either edge, and deliberately nearer
 * the bad one: the cost is not symmetric. Rejecting a good pair costs a repeat - seventeen
 * seconds - while accepting a bad one writes a distance that is metres out and says nothing.
 * At 1.0 m the 09-11 samples rejected 5 of 36 good pairs and all 9 bad ones, with nothing
 * missed.
 */
const val STEADY_ENOUGH_SPREAD_METRES = 1.0

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
 * Measured on device rather than reasoned about.
 */
fun foldsIntoStoredCalibration(
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
fun clockReportJson(
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
fun pairedReportJson(
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
data class RoomField(
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
fun roomField(
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
fun offeredTo(field: RoomField, hostId: String, peerId: String): Long? {
    if (peerId == hostId) return null
    for (entry in field.offerableOffsetMicros) {
        val micros = entry.value ?: continue
        if (entry.key.first == peerId && entry.key.second == hostId) return micros
        if (entry.key.first == hostId && entry.key.second == peerId) return -micros
    }
    return null
}

/**
 * The last four of a handset's name, which is what the logs have always shown.
 *
 * Not a good answer and knowingly so: these names are hexadecimal and nobody can read one out
 * loud, so "dcfa did not come" still leaves somebody walking to each phone to find out which one
 * that is. The proper fix is an identity a person can say, and it is not built. Until it is, this
 * at least matches what the event log prints, so the two can be lined up.
 */
fun shortName(peerId: String): String = peerId.takeLast(4)

/** How many of the room's answered pairs [peerId] is one end of. */
fun pairsReadableFor(field: RoomField, peerId: String): Int =
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
fun roomReportJson(
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
fun refusedRunJson(caseId: String, refusal: String, clock: String): String =
    "{\"role\":\"SINK\",\"caseId\":\"$caseId\",\"pairs\":[],\"renderer\":null," +
        "\"refusal\":\"$refusal\",\"clock\":$clock}"

/** The run's own report with [clock] spliced in beside it, under the name the analysis reads. */
fun withClockReport(runJson: String, clock: String): String =
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

/**
 * Waits up to [budgetMillis] for [ready], and answers whether it came true inside that.
 *
 * A bounded look rather than a fixed sleep: the thing waited for is usually already true, and a
 * wait that costs its budget whether or not it was needed gets removed by the next person to
 * notice a screen sitting still for no reason.
 */
fun awaitBriefly(
    budgetMillis: Long,
    nowNanos: () -> Long = System::nanoTime,
    rest: (Long) -> Unit = { Thread.sleep(it) },
    ready: () -> Boolean
): Boolean {
    val until = nowNanos() + budgetMillis * 1_000_000L
    while (!ready()) {
        if (nowNanos() >= until) return false
        rest(BRIEF_POLL_MILLIS)
    }
    return true
}

private const val BRIEF_POLL_MILLIS = 50L
