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
    val plays = MarkerPlayCodec.decode(report)
    if (plays.size < MINIMUM_MARKERS) {
        println("the run reports ${plays.size} markers. Was marker_stride_chunks set, and is this " +
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
    println("report : ${plays.size} markers, $chirpRepeats chirps " +
        "${"%.1f".format(chirpIntervalNanos / 1e9)} s apart")

    // Marker 0 is searched for across the whole span up to marker 1, because nothing yet ties the
    // handset's clock to this recording and any window that wide holds exactly one sweep. Start
    // this machine's recording before the handset so that the first sweep in the file is marker 0;
    // the count check below is what says that actually happened.
    val firstSpan = framesBetween(plays[0].playAtHostNanos, plays[1].playAtHostNanos, rate)
    val first = ChirpCorrelator.findFirstArrival(mono, chirp, 0, firstSpan)
    if (first == null || first.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) {
        println("no marker in the first $firstSpan samples. Was the handset already streaming when " +
            "this recording started?")
        return
    }
    val arrivals = ArrayList<Int>()
    arrivals.add(indexOf(first))
    // Stepped from the previous *measured* arrival by the gap the handset actually scheduled -
    // not by a nominal stride. Two clocks' rate difference then never accumulates into the window,
    // and a window far narrower than the stride is what keeps a neighbouring sweep out of it.
    for (k in 1 until plays.size) {
        val expected = arrivals[k - 1] + framesBetween(plays[k - 1].playAtHostNanos, plays[k].playAtHostNanos, rate)
        if (expected + WINDOW_SAMPLES + chirp.size >= mono.size) {
            println("the recording ends before marker $k. Record through the chirps.")
            return
        }
        val next = ChirpCorrelator.findFirstArrival(
            mono, chirp, expected - WINDOW_SAMPLES, expected + WINDOW_SAMPLES
        )
        if (next == null || next.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) {
            println("marker $k missing near $expected. The run and the recording have parted; " +
                "nothing below would be about the same markers.")
            return
        }
        arrivals.add(indexOf(next))
    }
    println("found  : all ${arrivals.size} markers the run scheduled")

    // The line. x is the schedule in samples of the handset's clock, so the slope is the two
    // clocks' rate ratio and the intercept is everything constant between the two machines.
    val xs = plays.map { framesBetween(plays[0].playAtHostNanos, it.playAtHostNanos, rate).toDouble() }
    val fit = Line.through(xs, arrivals.map { it.toDouble() })
    val markerResidual = xs.indices.map { arrivals[it] - fit.at(xs[it]) }
    println("slope  : ${"%.9f".format(fit.slope)} (${"%.1f".format((fit.slope - 1) * 1e6)} ppm, " +
        "this machine's clock against the handset's)")

    // A line fitted on 400 seconds and then read 80 seconds past its last point is a line trusted
    // outside its data. Fitting on the first half and predicting the second measures that trust on
    // this very recording, over a longer reach than the chirps ask for.
    val half = xs.size / 2
    val halfFit = Line.through(xs.take(half), arrivals.take(half).map { it.toDouble() })
    val reachMean = (half until xs.size).map { arrivals[it] - halfFit.at(xs[it]) }.average()
    println("reach  : fitting on the first $half markers and predicting the rest is off by a mean " +
        "of ${"%.1f".format(reachMean)} frames (${millisOf(reachMean)})")
    if (abs(reachMean) > REACH_TOLERANCE_FRAMES) {
        println("       : that is too much to read an ${QUANTUM_FRAMES}-frame step through. The " +
            "arrivals are not on one line, so treat everything below as a shape and not a level.")
    }

    describe("markers", markerResidual)

    // The chirps, read against the markers' own line. Nothing is re-fitted: the whole point is
    // that both paths are measured from one intercept, so the difference between them is free of
    // it. The window is the same 30 ms - wide enough for the ladder several times over.
    val chirpXs = (0 until chirpRepeats).map {
        framesBetween(plays[0].playAtHostNanos, hostChirpAt + it * chirpIntervalNanos, rate).toDouble()
    }
    val chirpResidual = ArrayList<Double>()
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
        chirpResidual.add(indexOf(found) - fit.at(x))
    }
    println()
    println("chirps : ${chirpResidual.size} of $chirpRepeats read${if (missed > 0) ", $missed missing" else ""}")
    if (chirpResidual.size < MINIMUM_CHIRPS) {
        println("Too few to locate a floor. The comparison needs the chirps from this same round.")
        return
    }
    describe("chirps ", chirpResidual)

    // The answer. Both numbers are the chirp path's, measured from the streamed path's mean, so
    // "near zero" means streamed playback sits there. The floor is taken as the mean of the lowest
    // level rather than the single lowest draw: one draw of a scatter is biased low by most of a
    // standard deviation, and it is the level's position that the product's floor estimator is
    // trying to hit.
    val sorted = chirpResidual.sorted()
    val levelZero = levelAt(sorted)
    val chirpMean = chirpResidual.average()
    println()
    println("answer : the chirp path's floor sits ${"%.1f".format(levelZero.first)} frames " +
        "(${millisOf(levelZero.first)}) from where streamed playback sits, on ${levelZero.second} " +
        "of ${sorted.size} draws")
    println("       : its mean sits ${"%.1f".format(chirpMean)} frames (${millisOf(chirpMean)}) away")
    println()
    when {
        abs(levelZero.first) < abs(chirpMean) - DECISION_MARGIN_FRAMES ->
            println("reading: streamed playback sits on the chirp path's lowest level. The pair " +
                "constant is measured on chirps and spent on streamed audio, so the estimator " +
                "should take the floor of its five - change AlignmentVerdict.")
        abs(chirpMean) < abs(levelZero.first) - DECISION_MARGIN_FRAMES ->
            println("reading: streamed playback sits where the chirp path averages, not on its " +
                "floor. The median estimator is estimating the right thing - keep it, and write " +
                "this down as the reason.")
        else ->
            println("reading: the floor and the mean are not far enough apart here to choose " +
                "between them (${"%.1f".format(abs(abs(levelZero.first) - abs(chirpMean)))} frames, " +
                "against a margin of $DECISION_MARGIN_FRAMES). More chirps in one round, or a " +
                "round where the ladder is better populated.")
    }
    println()
    println("note   : this compares two paths through one handset. It says nothing about which " +
        "machine the ladder belongs to, which this arrangement cannot see either.")
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

/** A least squares line, kept as one object so the chirps are read through the markers' own. */
private class Line(val intercept: Double, val slope: Double) {
    fun at(x: Double): Double = intercept + slope * x

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
            return Line(meanY - slope * meanX, slope)
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

/** 30 ms either side of where the schedule puts a sweep. See MarkerRead for why this width. */
private const val WINDOW_SAMPLES = 1440

/** The step O17 measured on the chirp path: 52 +- 1 frames, three levels. */
private const val QUANTUM_FRAMES = 52

/** Half a step: wider than any scatter inside a level, narrower than the gap between two. */
private const val LEVEL_HOLE_FRAMES = 26.0

/**
 * How far the half-fit may miss the second half before the line is not one line.
 *
 * A quarter of the step. The extrapolation the chirps ask for is shorter than this check's, so a
 * check that passes here leaves the chirps a margin smaller again.
 */
private const val REACH_TOLERANCE_FRAMES = 13.0

/**
 * How far apart the two candidates must be before the run picks one.
 *
 * O17 puts the chirp path's mean about 19 frames above its floor, and the two ends of this
 * comparison carry a few frames of their own noise each. Half the separation leaves room for that
 * and still refuses to choose on a coin toss.
 */
private const val DECISION_MARGIN_FRAMES = 9.0

/** Below this the line the chirps are read against is not worth fitting. */
private const val MINIMUM_MARKERS = 8

/** Below this there is no floor to locate, only a lowest draw. */
private const val MINIMUM_CHIRPS = 8
