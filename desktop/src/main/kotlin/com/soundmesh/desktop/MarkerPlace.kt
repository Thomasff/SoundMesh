package com.soundmesh.desktop

import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.MarkerPlay
import com.soundmesh.core.MarkerPlayCodec
import com.soundmesh.probe.WavFileReader
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where streamed playback sits against the chirp path's ladder, read off one recording.
 *
 * O18 answered half of it: the streamed path does not inherit the quantised ladder O17 measured on
 * chirp chunks. It could not answer the other half, and the reason was structural - it had no
 * schedule to measure against, so it removed a straight line through the arrivals instead, and
 * that line took the absolute offset with it. "Which level does playback sit on" *is* that offset.
 *
 * This reads the schedule itself. The handset writes down the instant it aimed each marker at
 * ([MarkerPlayCodec]), and its chirps follow the same stream in the same run, on the same clock.
 * So both paths are measured against instants from one timebase, and everything between that
 * timebase and this recording - the two clocks' offset, the handset's output latency, the flight
 * across the room, this machine's capture latency - is one constant that lands in the fit's
 * intercept and cancels in the comparison the run exists to make.
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.MarkerPlaceKt \
 *       <recording.wav> <sync.json>
 *
 * **What the answer looks like.** The line is fitted through the markers, so their mean residual
 * is zero by construction and the chirps are read against it. O17 measured the chirp path's levels
 * at 0 / +52 / +104 frames with 69 / 26 / 5 percent of draws, so its mean sits about 19 frames
 * above its floor. If the chirps' floor comes out near zero, streamed playback sits on level 0 and
 * the product should take the floor of its five chirps; if their mean comes out near zero, it sits
 * where the median already estimates and the product should not change.
 *
 * **This does not attribute the ladder to anything.** O17 could not say which of the two machines
 * stepped, because both emitted and the ladder lives in their difference. Here only the handset
 * emits, so nothing about attribution is being claimed either - what is compared is two paths
 * through one handset, which is the question the product actually turns on.
 */
fun main(args: Array<String>) {
    val wavPath = args.getOrNull(0) ?: error("usage: <recording.wav> <sync.json>")
    val jsonPath = args.getOrNull(1) ?: error("the handset's sync.json carries the schedule")

    val report = File(jsonPath).readText()
    val allPlays = MarkerPlayCodec.decode(report)
    if (allPlays.size < MINIMUM_MARKERS) {
        println("the run reports ${allPlays.size} markers. Was marker_stride_chunks set, and is this " +
            "a build that writes ${MarkerPlayCodec.FIELD}?")
        return
    }
    val hostChirpAt = longField(report, "hostChirpAtHostNanos")
        ?: run { println("no hostChirpAtHostNanos in the report: nothing to compare the markers against."); return }
    val chirpRepeats = (longField(report, "chirpRepeats") ?: 1L).toInt()
    val chirpIntervalNanos = longField(report, "chirpIntervalNanos") ?: 0L
    val alignmentOffsetMicros = longField(report, "alignmentOffsetMicros") ?: 0L
    // A standing correction would move the chirps and not the markers, which is exactly the
    // quantity being read. Refused rather than subtracted: the run should simply not apply one.
    if (alignmentOffsetMicros != 0L) {
        println("this run applied a standing alignment offset of $alignmentOffsetMicros us, which " +
            "moves the chirps and not the markers. Re-run without it.")
        return
    }

    val rate = WavFileReader.sampleRateOf(File(wavPath))
    check(rate == ChirpGenerator.SAMPLE_RATE) {
        "the recording is at $rate Hz and the sweep is at ${ChirpGenerator.SAMPLE_RATE} Hz"
    }
    val mono = WavFileReader.readMono(File(wavPath))
    val chirp = ChirpGenerator.generateMono()
    println("wav    : ${mono.size} samples (${"%.1f".format(mono.size.toDouble() / rate)} s) at $rate Hz")
    println("report : ${allPlays.size} markers, $chirpRepeats chirps " +
        "${"%.1f".format(chirpIntervalNanos / 1e9)} s apart")

    // The first sweep is searched for across the whole span up to the next marker, because nothing
    // yet ties the handset's clock to this recording and any window that wide holds exactly one
    // sweep. Which marker it is is decided below, not assumed here.
    val firstSpan = framesBetween(allPlays[0].playAtHostNanos, allPlays[1].playAtHostNanos, rate)
    val first = ChirpCorrelator.findFirstArrival(mono, chirp, 0, firstSpan)
    if (first == null || first.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) {
        println("no marker in the first $firstSpan samples. Was the handset already streaming when " +
            "this recording started?")
        return
    }
    // Which marker that first sweep is. It is marker 0 only if this machine was recording before
    // the handset started streaming, and a run where it was not looks exactly like a good one: the
    // markers are evenly spaced, so a whole table shifted by two of them steps along perfectly and
    // the misfit surfaces only at the very end. It cost a 600 second round. The witness is the last
    // scheduled marker - under a wrong anchor it is predicted past where the stream ever ran, so
    // only the right one finds a sweep there.
    val anchor = (0..MAX_ANCHOR_SLIP).firstOrNull { candidate ->
        if (candidate >= allPlays.size) return@firstOrNull false
        val last = allPlays.size - 1
        val at = indexOf(first) +
            framesBetween(allPlays[candidate].playAtHostNanos, allPlays[last].playAtHostNanos, rate)
        if (at + WINDOW_SAMPLES + chirp.size >= mono.size) return@firstOrNull false
        val witness = ChirpCorrelator.findFirstArrival(
            mono, chirp, at - WINDOW_SAMPLES, at + WINDOW_SAMPLES
        )
        witness != null && witness.ratio >= ChirpCorrelator.MIN_TRUSTWORTHY_RATIO
    }
    if (anchor == null) {
        println("the first sweep in this recording cannot be matched to any of the run's markers: " +
            "no anchor within $MAX_ANCHOR_SLIP of it puts a sweep where the last one was scheduled. " +
            "Start this machine's recording before the handset, in the same breath as the launch.")
        return
    }
    if (anchor > 0) {
        println("anchor : the first sweep here is marker $anchor, not marker 0 - this recording " +
            "started after the handset did. The last scheduled marker is where it says it is.")
    }
    val plays = allPlays.drop(anchor)
    // Stepped from the previous *measured* arrival by the gap the handset actually scheduled -
    // not by a nominal stride. Two clocks' rate difference then never accumulates into the window,
    // and a window far narrower than the stride is what keeps a neighbouring sweep out of it.
    //
    // A marker the correlator cannot find is skipped rather than fatal. Refusing the whole round
    // on one unreadable sweep threw away 119 good ones on the first long run; the round's own
    // check is that nearly all of them were found, which needs a count and not an exception. The
    // window widens by one stride per consecutive miss, because the step has to reach across them.
    val arrivals = ArrayList<Int?>()
    arrivals.add(indexOf(first))
    var lastGood = 0
    var missing = 0
    for (k in 1 until plays.size) {
        val from = plays[lastGood].playAtHostNanos
        val expected = arrivals[lastGood]!! + framesBetween(from, plays[k].playAtHostNanos, rate)
        val window = WINDOW_SAMPLES * (k - lastGood)
        if (expected + window + chirp.size >= mono.size) {
            println("the recording ends before marker $k. Record through the chirps.")
            return
        }
        val next = ChirpCorrelator.findFirstArrival(mono, chirp, expected - window, expected + window)
        if (next == null || next.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) {
            arrivals.add(null)
            missing++
            continue
        }
        arrivals.add(indexOf(next))
        lastGood = k
    }
    val read = arrivals.indices.filter { arrivals[it] != null }
    println("found  : ${read.size} of ${plays.size} markers the run scheduled" +
        if (missing > 0) ", $missing the correlator could not read" else "")
    if (read.size < plays.size - (plays.size / MISSING_SHARE)) {
        println("Too many missing to call this the same run. Nothing below would be about the same " +
            "markers.")
        return
    }

    // The line. x is the schedule in samples of the handset's clock, so the slope is the two
    // clocks' rate ratio and the intercept is everything constant between the two machines.
    val xs = read.map { framesBetween(plays[0].playAtHostNanos, plays[it].playAtHostNanos, rate).toDouble() }
    val found = read.map { arrivals[it]!! }
    val fit = Line.through(xs, found.map { it.toDouble() })
    val markerResidual = xs.indices.map { found[it] - fit.at(xs[it]) }
    println("slope  : ${"%.9f".format(fit.slope)} (${"%.1f".format((fit.slope - 1) * 1e6)} ppm, " +
        "this machine's clock against the handset's)")

    // A line read past its last point is a line trusted outside its data. Fitting on the first half
    // and predicting the second measures that trust on this very recording, over a longer reach
    // than the chirps ask for - and it is judged against its own standard error, not against a
    // frame count. Judged against 13 frames it reported a broken line on data that turned out to be
    // white noise: half a run's markers extrapolated over the other half carry a standard error of
    // about 30 frames all by themselves, so the threshold was inside the statistic's own scatter.
    val half = xs.size / 2
    val halfFit = Line.through(xs.take(half), found.take(half).map { it.toDouble() })
    val reach = (half until xs.size).map { found[it] - halfFit.at(xs[it]) }
    val reachMean = reach.average()
    val reachError = halfFit.errorAt(xs.drop(half).average())
    println("reach  : fitting on the first $half markers and predicting the rest is off by a mean " +
        "of ${"%.1f".format(reachMean)} frames, against a standard error of " +
        "${"%.1f".format(reachError)}")
    if (abs(reachMean) > REACH_SIGMAS * reachError) {
        println("       : that is $REACH_SIGMAS standard errors or more. The arrivals are not on one " +
            "line, so treat everything below as a shape and not a level.")
    }

    // Printed, not only summarised. A summary of a residual answers "how wide is it" and the
    // question the reach check just raised is "what shape is it" - the two are different, and
    // reading a slope off a shape is how a staircase once reported 183 ppm.
    println()
    println("  #   second   sample   residual")
    xs.indices.forEach {
        println("%3d %8.1f %8d %10.1f".format(
            read[it], xs[it] / ChirpGenerator.SAMPLE_RATE, found[it], markerResidual[it]
        ))
    }
    describe("markers", markerResidual)

    // The chirps, read against the markers' own line. Nothing is re-fitted: the whole point is
    // that both paths are measured from one intercept, so the difference between them is free of
    // it. The window is the same 30 ms - wide enough for the ladder several times over.
    val chirpXs = (0 until chirpRepeats).map {
        framesBetween(plays[0].playAtHostNanos, hostChirpAt + it * chirpIntervalNanos, rate).toDouble()
    }
    // Kept with the instant it was scheduled for, because a chirp that could not be read must not
    // shift the rest of the table onto the wrong instants.
    val chirpFound = ArrayList<Pair<Double, Double>>()
    var missed = 0
    chirpXs.forEach { x ->
        val expected = fit.at(x).toInt()
        if (expected - WINDOW_SAMPLES < 0 || expected + WINDOW_SAMPLES + chirp.size >= mono.size) {
            missed++
            return@forEach
        }
        val found = ChirpCorrelator.findFirstArrival(
            mono, chirp, expected - WINDOW_SAMPLES, expected + WINDOW_SAMPLES
        )
        if (found == null || found.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) {
            missed++
            return@forEach
        }
        chirpFound.add(x to (indexOf(found) - fit.at(x)))
    }
    println()
    val chirpResidual = chirpFound.map { it.second }
    println("chirps : ${chirpResidual.size} of $chirpRepeats read${if (missed > 0) ", $missed missing" else ""}")
    if (chirpResidual.size < MINIMUM_CHIRPS) {
        println("Too few to locate a floor. The comparison needs the chirps from this same round.")
        return
    }
    println()
    println("  #   second   residual")
    chirpResidual.indices.forEach {
        println("%3d %8.1f %10.1f".format(it, chirpFound[it].first / ChirpGenerator.SAMPLE_RATE, chirpResidual[it]))
    }
    describe("chirps ", chirpResidual)

    // The answer. Both numbers are the chirp path's, measured from the streamed path's mean, so
    // "near zero" means streamed playback sits there. The floor is taken as the mean of the lowest
    // level rather than the single lowest draw: one draw of a scatter is biased low by most of a
    // standard deviation, and it is the level's position that the product's floor estimator is
    // trying to hit.
    val sorted = chirpResidual.sorted()
    val (floor, floorCount) = levelAt(sorted)
    val chirpMean = chirpResidual.average()
    // What it costs to read the markers' line where the chirps are. Shared by both candidates, and
    // printed on its own because it is the one term a longer run with denser markers shrinks.
    val lineError = fit.errorAt(chirpFound.map { it.first }.average())
    val floorError = sqrt(lineError * lineError + spreadOf(sorted.take(floorCount)).let { it * it } / floorCount)
    val meanError = sqrt(lineError * lineError + spreadOf(chirpResidual).let { it * it } / chirpResidual.size)
    println()
    println("answer : reading the markers' line where the chirps are costs " +
        "${"%.1f".format(lineError)} frames")
    println("       : the chirp path's floor sits ${"%.1f".format(floor)} +-${"%.1f".format(floorError)} " +
        "frames (${millisOf(floor)}) from where streamed playback sits, on $floorCount of " +
        "${sorted.size} draws")
    println("       : its mean sits ${"%.1f".format(chirpMean)} +-${"%.1f".format(meanError)} frames " +
        "(${millisOf(chirpMean)}) away")
    val floorFits = abs(floor) <= DECISION_SIGMAS * floorError
    val meanFits = abs(chirpMean) <= DECISION_SIGMAS * meanError
    println()
    when {
        floorFits && !meanFits ->
            println("reading: streamed playback sits on the chirp path's lowest level. The pair " +
                "constant is measured on chirps and spent on streamed audio, so the estimator " +
                "should take the floor of its five - change AlignmentVerdict.")
        meanFits && !floorFits ->
            println("reading: streamed playback sits where the chirp path averages, not on its " +
                "floor. The median estimator is estimating the right thing - keep it, and write " +
                "this down as the reason.")
        floorFits && meanFits ->
            println("reading: both candidates are inside the error bars, so this round does not " +
                "choose between them. What shrinks it is denser markers and more chirps in one " +
                "round, or pooling the difference across rounds.")
        else ->
            println("reading: streamed playback sits on neither - the floor and the mean are both " +
                "$DECISION_SIGMAS standard errors away. Something is moving that neither candidate " +
                "describes. Report it and change nothing.")
    }
    println()
    println("note   : this compares two paths through one handset. It says nothing about which " +
        "machine the ladder belongs to, which this arrangement cannot see either.")
    // One line a pooling script can read. Printed rather than left to be transcribed: one repeat
    // once needed two numbers copied by hand through a shell script, and a measurement whose
    // inputs are transcribed has a transcription in it.
    println()
    println("pool   : floor=%.2f floorErr=%.2f mean=%.2f meanErr=%.2f lineErr=%.2f ".format(
        floor, floorError, chirpMean, meanError, lineError
    ) + "floorDraws=$floorCount chirps=${chirpResidual.size} markers=${read.size} " +
        "markerSd=%.2f".format(spreadOf(markerResidual)))
}

/** The plain standard deviation, or zero when there is not enough to have one. */
private fun spreadOf(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
}

/** Everything both residual tables say about themselves, so the two can be read side by side. */
private fun describe(what: String, residual: List<Double>) {
    val mean = residual.average()
    val spread = sqrt(residual.sumOf { (it - mean) * (it - mean) } / residual.size)
    println()
    println("$what: n=${residual.size}  mean ${"%.1f".format(mean)}  sd ${"%.1f".format(spread)} frames " +
        "(${millisOf(spread)})  range ${"%.0f".format(residual.min())} .. ${"%.0f".format(residual.max())}")
    // A ladder shows as residuals piling near multiples of the step. Against what a spread with no
    // levels in it gives: uniform over one bin averages a quarter of the bin. Asking instead
    // whether the departure is "moderate" accepts nearly anything, which is how O18's first two
    // criteria both passed on data that had no levels in it.
    val toStep = residual.map {
        val r = ((it % QUANTUM_FRAMES) + QUANTUM_FRAMES) % QUANTUM_FRAMES
        abs(if (r > QUANTUM_FRAMES / 2.0) r - QUANTUM_FRAMES else r)
    }
    println("$what: mean distance to a multiple of $QUANTUM_FRAMES is ${"%.1f".format(toStep.average())} " +
        "frames; no levels at all gives ${"%.1f".format(QUANTUM_FRAMES / 4.0)}, levels give near 0")
    val sorted = residual.sorted()
    val holes = sorted.indices.drop(1)
        .map { sorted[it] - sorted[it - 1] to sorted[it] }
        .sortedByDescending { it.first }
    println("$what: widest holes " + holes.take(3).joinToString(", ") {
        "${"%.1f".format(it.first)} @ ${"%.0f".format(it.second)}"
    })
}

/**
 * The lowest level's position and how many draws sit on it.
 *
 * The level ends at the first hole wider than [LEVEL_HOLE_FRAMES], which O17 measured the room for:
 * its levels are 52 frames apart with 6 to 9 frames of scatter inside one, so a hole of 26 frames
 * cannot fall inside a level and cannot be missed between two. With no such hole every draw is one
 * level and the answer is the whole mean, which is the correct reading of a path with no ladder.
 */
private fun levelAt(sorted: List<Double>): Pair<Double, Int> {
    var end = sorted.size
    for (k in 1 until sorted.size) {
        if (sorted[k] - sorted[k - 1] > LEVEL_HOLE_FRAMES) {
            end = k
            break
        }
    }
    val level = sorted.take(end)
    return level.average() to level.size
}

/**
 * A least squares line, kept as one object so the chirps are read through the markers' own.
 *
 * It carries what it needs to say how well it knows itself somewhere. Reading a line outside its
 * data costs accuracy that grows with the reach, and a comparison stated without that cost invites
 * exactly the mistake this file already made once: a fixed frame threshold on a statistic whose own
 * standard error was larger than the threshold, which fires whatever the data does.
 */
private class Line(
    val intercept: Double,
    val slope: Double,
    private val meanX: Double,
    private val sxx: Double,
    private val count: Int,
    private val sigma: Double
) {
    fun at(x: Double): Double = intercept + slope * x

    /** How well this line knows its own value at [x] - the usual prediction standard error. */
    fun errorAt(x: Double): Double =
        sigma * sqrt(1.0 / count + (x - meanX) * (x - meanX) / sxx)

    companion object {
        fun through(xs: List<Double>, ys: List<Double>): Line {
            val meanX = xs.average()
            val meanY = ys.average()
            var cov = 0.0
            var varX = 0.0
            xs.indices.forEach {
                cov += (xs[it] - meanX) * (ys[it] - meanY)
                varX += (xs[it] - meanX) * (xs[it] - meanX)
            }
            val slope = cov / varX
            val intercept = meanY - slope * meanX
            // Two degrees of freedom go to the line itself, so a two point line has no spread to
            // report and says so with zero rather than with a division by zero.
            val spread = if (xs.size > 2) {
                sqrt(xs.indices.sumOf {
                    val e = ys[it] - (intercept + slope * xs[it])
                    e * e
                } / (xs.size - 2))
            } else {
                0.0
            }
            return Line(intercept, slope, meanX, varX, xs.size, spread)
        }
    }
}

/**
 * The first arrival where one was found, and the correlation peak otherwise.
 *
 * Across a room the loudest arrival is not the earliest - a reflection once won by 10.7 ms - so
 * the onset is what this measurement is made of wherever the correlator could find one.
 */
private fun indexOf(arrival: ChirpArrival): Int = arrival.firstArrivalIndex ?: arrival.index

private fun framesBetween(fromNanos: Long, toNanos: Long, rate: Int): Int =
    ((toNanos - fromNanos) * rate / 1_000_000_000L).toInt()

private fun longField(report: String, name: String): Long? =
    Regex("\"$name\":(-?\\d+)").find(report)?.groupValues?.get(1)?.toLong()

private fun millisOf(frames: Double): String =
    "%.3f ms".format(frames * 1000.0 / ChirpGenerator.SAMPLE_RATE)

/**
 * How many markers the first sweep in the recording may be past marker 0.
 *
 * Eight, which at a five second stride is forty seconds of launch slop - far more than starting the
 * recording and the handset in one breath ever costs, and far less than a stride count that would
 * start matching the wrong sweep. Every candidate is checked against the last scheduled marker, so
 * this only bounds the search; it does not decide anything.
 */
private const val MAX_ANCHOR_SLIP = 8

/** 30 ms either side of where the schedule puts a sweep. See MarkerRead for why this width. */
private const val WINDOW_SAMPLES = 1440

/** The step O17 measured on the chirp path: 52 +- 1 frames, three levels. */
private const val QUANTUM_FRAMES = 52

/** Half a step: wider than any scatter inside a level, narrower than the gap between two. */
private const val LEVEL_HOLE_FRAMES = 26.0

/**
 * How many of its own standard errors the half-fit may miss the second half by.
 *
 * Two, the usual reading of "not consistent with one line". A fixed frame count was the first
 * attempt and it was wrong in a way worth remembering: 13 frames, against a statistic whose own
 * standard error on a real run was 31, so it fired on white noise.
 */
private const val REACH_SIGMAS = 2.0

/**
 * How many standard errors from zero a candidate has to be before it is ruled out.
 *
 * Two. Both candidates are tested separately and all four outcomes are reachable, which the first
 * version of this criterion was not: it compared two absolute values against a fixed margin, so
 * "neither of them" had nowhere to appear at all.
 */
private const val DECISION_SIGMAS = 2.0

/** Below this the line the chirps are read against is not worth fitting. */
private const val MINIMUM_MARKERS = 8

/**
 * One in this many markers may go unread before the round is refused.
 *
 * A tenth. Far more than the one or two a long run loses to a correlation that will not clear the
 * trust ratio, and far fewer than a recording that has drifted off the run it claims to be of -
 * that failure loses them in a block, not one here and one there.
 */
private const val MISSING_SHARE = 10

/** Below this there is no floor to locate, only a lowest draw. */
private const val MINIMUM_CHIRPS = 8
