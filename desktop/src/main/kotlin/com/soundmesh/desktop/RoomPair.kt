package com.soundmesh.desktop

import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.probe.WavFileReader
import java.io.File
import kotlin.math.abs

/**
 * Reads both machines' recordings of one exchange, so the room drops out of the answer.
 *
 * Two machines, two chirps, and now two recordings: each machine hears its own chirp and the other
 * machine's. Writing A for the instant the handset's chirp left its scheduler and P for the instant
 * this machine's engine consumed its own chirp - both on the handset's clock - and s for where each
 * arrival sits in each file:
 *
 *     delta_handset = (s_pc_in_handset - s_handset_in_handset) / rate - (P - A)
 *                   = (pc delay + this speaker to that microphone)
 *                   - (handset delay + the handset's own speaker to its own microphone)
 *
 *     delta_pc      = (s_handset_in_pc - s_pc_in_pc) / rate - (A - P)
 *                   = (handset delay + that speaker to this microphone)
 *                   - (pc delay + this machine's own speaker to its own microphone)
 *
 * Each machine's input latency and the instant its recording opened are common to the two arrivals
 * in its own file and leave exactly, which is why nothing here has to trust either.
 *
 * **What the difference kills.** The two crossing paths are not the same length - one runs from
 * this speaker to that microphone, the other the other way between different points on the same two
 * cases - so they do not cancel term for term. What cancels is everything that scales with how far
 * apart the machines are. Write r for the line between the two microphones and v for each device's
 * own microphone-to-speaker vector; for a separation much larger than a device, the two paths are
 * `|r| - u.v_pc` and `|r| + u.v_handset`, so
 *
 *     their difference = 2 * (pc delay - handset delay) - u.(v_pc + v_handset) - selves
 *
 * and `|r|` is gone. What is left is fixed by the two cases and their facing, is bounded by the
 * two on-device speaker-to-microphone separations, and **does not grow with distance**. That last
 * part is the criterion: run this at two very different separations and the pair constant has to
 * come back the same. A term that scales with the room cannot survive that, and no confidence
 * ratio can see one.
 *
 * **Why this is worth the second recording.** Read from one recording alone the answer carries the
 * whole separation, and taking that out meant measuring it with a tape - which in turn assumed the
 * two placements and the speaker were on one line. That premise was falsified by a run at 115 cm
 * whose direct path was obstructed: the same displacement read 16.5 cm long, and nothing in the
 * report could see it, because a blocked direct path still delivers a real, confident arrival.
 * Here the separation is not measured, estimated or assumed, and the residue that replaces it is
 * a property of two pieces of hardware rather than of the room they are in.
 *
 * **The separation is the other criterion.** The sum keeps what the difference threw away, so with
 * the machines a measured distance apart the sum has to come back equal to it. It is the one
 * quantity in here whose answer is already known, and a wrong constant anywhere cannot fake it.
 *
 * **The self paths are arguments, not constants**, because this program has no way to know which
 * two machines it is reading. Left out they are zero and both answers are wrong by a stated
 * amount, which the report says rather than printing a number that looks finished.
 *
 *   java -cp "<lib>" com.soundmesh.desktop.RoomPairKt \
 *       <handset.wav> <pc.wav> <handsetChirpAtHostNanos> <pcConsumedAtHostNanos> \
 *       <handsetRecordingStartedAtHostNanos> <pcRenderIndex> [handsetSelfCm] [pcSelfCm]
 *
 * [RoomReadKt] is the one-sided reading this supersedes, kept because the archived runs were
 * measured with it and a re-read has to be comparable with them.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        error(
            "usage: <handset.wav> <pc-shot.txt> <handsetRecordingStartedAtHostNanos> " +
                "[handsetSelfCm] [pcSelfCm] [edgeShare]"
        )
    }
    val handsetFile = File(args[0])
    val shot = RoomShot.read(File(args[1]))
    val handsetOpened = args[2].toLong()
    val handsetSelfNanos = centimetresToNanos(args.getOrNull(3)?.toDouble())
    val pcSelfNanos = centimetresToNanos(args.getOrNull(4)?.toDouble())
    // Which rule reads an arrival. Left out, every chirp is read at its own peak. Given, every
    // chirp is read the way the product reads one for a distance: the first lag whose score
    // reaches this share of the loudest lag's. The point of the argument is that the same
    // recordings can be re-read both ways, so the two rules can be ranked against a tape rather
    // than against each other.
    val edgeShare = args.getOrNull(5)?.toDouble()

    val reference = ChirpGenerator.generateMono()
    val rate = ChirpGenerator.SAMPLE_RATE
    val handsetTrack = read(handsetFile, rate)
    val pcTrack = read(shot.wav, rate)
    val strideSamples = samples(shot.repeatNanos, rate)

    // The first repeat of each file is found against that file's own opening instant, which is the
    // only anchor either of them has and is good to about half a second. Every later repeat is
    // found against the first one measured rather than against the opening again: the opening's
    // error is the same for all of them, so anchoring on a measured arrival replaces half a second
    // of slack with the clock drift across one window - about twenty samples over the whole eight.
    println()
    val handsetOwnFirst = find(
        handsetTrack, reference, samples(shot.grid - handsetOpened, rate), OPENING_SLACK,
        "handset chirp #0", edgeShare
    ) ?: return
    val pcOwnFirst = find(
        pcTrack, reference, shot.renderIndex[0], OPENING_SLACK, "pc chirp #0", edgeShare
    ) ?: return
    // What the capture chain adds between the engine consuming a frame and the microphone hearing
    // it. Unknown, never used as a measurement, and constant across the window - so measuring it
    // once on the first repeat is what lets every later one be searched tightly.
    val captureLag = pcOwnFirst.index - shot.renderIndex[0]

    val pairs = ArrayList<Double>()
    val flights = ArrayList<Double>()
    val rows = ArrayList<String>()
    for (repeat in shot.consumed.indices) {
        val asked = shot.grid + repeat * shot.repeatNanos
        val consumed = shot.consumed[repeat]
        // The gap the two sides agreed on for this repeat, in samples. Read off this repeat's own
        // consumed instant rather than assumed equal to the first's: the schedule is exact to a
        // couple of microseconds, but an assumption that the repeats are evenly spaced is one this
        // program would never find out was wrong.
        val apartSamples = samples(consumed - asked, rate)

        println()
        println("--- repeat $repeat ---")
        val handsetOwn =
            if (repeat == 0) handsetOwnFirst
            else find(
                handsetTrack, reference, handsetOwnFirst.index + repeat * strideSamples, SLOT_SLACK,
                "handset chirp", edgeShare
            ) ?: continue
        val pcInHandset = find(
            handsetTrack, reference, handsetOwn.index + apartSamples, SLOT_SLACK, "pc chirp", edgeShare
        ) ?: continue
        val pcOwn =
            if (repeat == 0) pcOwnFirst
            else find(
                pcTrack, reference, shot.renderIndex[repeat] + captureLag, SLOT_SLACK, "pc chirp",
                edgeShare
            ) ?: continue
        val handsetInPc = find(
            pcTrack, reference, pcOwn.index - apartSamples, SLOT_SLACK, "handset chirp", edgeShare
        ) ?: continue

        val deltaHandset = nanos(pcInHandset.index - handsetOwn.index, rate) - (consumed - asked)
        val deltaPc = nanos(handsetInPc.index - pcOwn.index, rate) - (asked - consumed)
        val flight = (deltaHandset + deltaPc).toDouble() / 2 + (handsetSelfNanos + pcSelfNanos) / 2
        val pair = (deltaHandset - deltaPc).toDouble() / 2 + (handsetSelfNanos - pcSelfNanos) / 2
        pairs.add(pair / 1e6)
        flights.add(flight / 1e6)
        rows.add(
            "  #$repeat  pair ${"%+8.3f".format(pair / 1e6)} ms   " +
                "separation ${"%6.1f".format(flight / 1e9 * SPEED_OF_SOUND * 100)} cm   " +
                "(handset ${millis(deltaHandset)}, pc ${millis(deltaPc)})"
        )
    }

    println()
    println("--- every repeat ---")
    rows.forEach { println(it) }
    if (pairs.isEmpty()) {
        println("nothing was readable. There is no answer in this pair of files.")
        return
    }

    println()
    println(
        "pair constant: ${"%+.3f".format(floorOf(pairs))} ms" +
            "  (the mean of the $FLOOR_COUNT lowest of ${pairs.size})"
    )
    println(
        "  <- this machine's output delay minus the handset's. A floor and not a centre, because " +
            "what disturbs it only ever pushes it up - see FLOOR_COUNT."
    )
    report("  the window  ", pairs, "ms") { "%+.3f".format(it) }
    report("separation   ", flights.map { it / 1000 * SPEED_OF_SOUND * 100 }, "cm") { "%.1f".format(it) }
    println(
        "  <- a criterion, not an output, and the half that emission jitter cannot reach. Its " +
            "spread here is this analysis's own, with the room and the schedule held still."
    )
    if (handsetSelfNanos == 0.0 || pcSelfNanos == 0.0) {
        println()
        println(
            "Both answers are missing a speaker-to-own-microphone path that was not given. Each " +
                "is a ruler measurement on one device's case: pass them as the last two arguments " +
                "in centimetres. Ten centimetres unaccounted for is 0.146 ms on either answer."
        )
    }
}

/**
 * The middle of [values], how far they spread, and the widest step inside them.
 *
 * The widest step is printed because a spread cannot tell two shapes apart that want different
 * answers. Readings scattered about a middle want their median; readings sitting on two levels
 * want to be reported as two levels, and a median of them is a number none of them took. The
 * handset this was written against steps about 1.3 ms between emissions, so which of the two is
 * happening is the first thing a reader needs and the last thing a summary statistic says.
 */
/**
 * How many of a window's lowest readings the pair constant is taken from.
 *
 * A floor rather than a centre, because what disturbs this half only ever pushes it one way: a
 * chirp that leaves its speaker early raises the half sum, and nothing lowers it. Measured
 * 2026-09-22, eight folded windows at one placement with nothing touched between them - the
 * median of a window moved 1.489 ms from round to round and its mean 1.468, while the mean of
 * the three lowest moved 0.628.
 *
 * [com.soundmesh.core.AlignmentVerdict.judge] scores 1.468 ms on those same windows, because its
 * rejection is symmetric about the median and its width is the median deviation: a second level
 * occupying a third of the window widens that width instead of being rejected by it. Five of the
 * eight windows came back with nothing rejected at all.
 *
 * Three rather than one because a single reading carries the whole of the level's own noise,
 * about 0.146 ms; three rather than five because a window has been seen with five high draws.
 */
private const val FLOOR_COUNT = 3

private fun floorOf(values: List<Double>): Double = values.sorted().take(FLOOR_COUNT).average()

private fun report(what: String, values: List<Double>, unit: String, say: (Double) -> String) {
    val sorted = values.sorted()
    val middle = sorted[sorted.size / 2]
    val deviations = sorted.map { abs(it - middle) }.sorted()
    println(
        "$what: median ${say(middle)} $unit, spread ${say(sorted.last() - sorted.first())}, " +
            "mad ${say(deviations[deviations.size / 2])}, from ${values.size}"
    )
    if (sorted.size < 3) return
    var gapAt = 1
    for (index in 2 until sorted.size) {
        if (sorted[index] - sorted[index - 1] > sorted[gapAt] - sorted[gapAt - 1]) gapAt = index
    }
    val gap = sorted[gapAt] - sorted[gapAt - 1]
    val spread = sorted.last() - sorted.first()
    // Half the spread in one step is not a scatter any more. Deliberately descriptive: it says
    // what the readings look like and leaves the deciding to whoever reads them.
    if (spread > 0 && gap > spread / 2) {
        println(
            "  two levels ${say(gap)} $unit apart: " +
                "${sorted.take(gapAt).joinToString(", ") { say(it) }} | " +
                sorted.drop(gapAt).joinToString(", ") { say(it) }
        )
    }
}

/**
 * What the PC left beside its recording: the instants only it observed.
 *
 * A file rather than arguments. With one repeat the two numbers went along the command line
 * through a shell script; with [com.soundmesh.core.RoomGrid.REPEATS] there are sixteen, and every
 * one of them is an instant this analysis cannot derive and cannot check. Transcribed numbers are
 * the one input to a measurement that fails silently and reads perfectly.
 */
private class RoomShot(
    val wav: File,
    val grid: Long,
    val repeatNanos: Long,
    val consumed: List<Long>,
    val renderIndex: List<Int>
) {
    companion object {
        fun read(file: File): RoomShot {
            val fields = file.readLines().filter { it.isNotBlank() }.associate {
                val at = it.indexOf('=')
                require(at > 0) { "not a shot file line: $it" }
                it.substring(0, at) to it.substring(at + 1)
            }
            fun field(name: String) = fields[name] ?: error("${file.name} has no $name")
            val consumed = field("consumed").split(",").map { it.toLong() }
            val renderIndex = field("renderIndex").split(",").map { it.toInt() }
            require(consumed.size == renderIndex.size) {
                "${file.name} has ${consumed.size} instants and ${renderIndex.size} indices"
            }
            return RoomShot(
                wav = File(file.parentFile, field("wav")),
                grid = field("grid").toLong(),
                repeatNanos = field("repeatNanos").toLong(),
                consumed = consumed,
                renderIndex = renderIndex
            )
        }
    }
}

private fun read(file: File, rate: Int): ShortArray {
    val track = WavFileReader.readMono(file)
    val fileRate = WavFileReader.sampleRateOf(file)
    check(fileRate == rate) { "${file.name} is at $fileRate Hz and the chirp is at $rate Hz" }
    println(
        "${file.name}: ${track.size} samples " +
            "(${"%.2f".format(track.size.toDouble() / rate)} s)"
    )
    return track
}

/**
 * The chirp nearest [expected], searched no wider than [slack].
 *
 * Windowed, never over the whole file. Two identical chirps in one recording is exactly the shape
 * that makes a whole-file maximum answer confidently about the wrong one - once, with an infinite
 * confidence ratio and an answer a full interval out.
 *
 * First arrival, not loudest. A handset across a room reaches a laptop's microphone array weakly
 * enough that a reverberation cluster can be the louder sound: measured here at 10.7 ms late and
 * a confidence ratio of 369.
 */
private fun find(
    track: ShortArray,
    reference: ShortArray,
    expected: Int,
    slack: Int,
    what: String,
    edgeShare: Double? = null
): ChirpArrival? {
    val arrival = if (edgeShare == null) {
        ChirpCorrelator.findFirstArrival(
            track, reference, expected - slack, expected + slack, edgeShares = ONSET_SHARES
        )
    } else {
        // The product's rule, run rather than restated: the share is asked of the correlator the
        // same way [com.soundmesh.core.AlignmentAnalysis] asks for it, and the lag it names is
        // moved into [ChirpArrival.index] so the arithmetic below cannot tell the difference.
        ChirpCorrelator.findArrival(
            track, reference, expected - slack, expected + slack, listOf(edgeShare) + ONSET_SHARES
        )?.let { it.copy(index = it.edgeIndices.first(), edgeIndices = it.edgeIndices.drop(1)) }
    }
    if (arrival == null) {
        println("$what: nothing to search - the window falls outside the recording")
        return null
    }
    // Reported beside it whenever the two disagree, because the gap is the reverberation this
    // measurement is standing in the middle of, and a reader who cannot see it has no way to
    // know how much of the answer rested on picking the earlier one.
    val loudest = ChirpCorrelator.findArrival(track, reference, expected - slack, expected + slack)!!
    if (loudest.index != arrival.index) {
        println(
            "$what: the loudest lag is ${millis(nanos(loudest.index - arrival.index, ChirpGenerator.SAMPLE_RATE))} " +
                "later at ${"%.1f".format(loudest.ratio)} - reverberation beat the direct path here"
        )
    }
    println(
        "$what: sample ${arrival.index} (${"%+d".format(arrival.index - expected)} from expected), " +
            "ratio ${"%.1f".format(arrival.ratio)}" +
            (if (arrival.atSearchEdge) ", AT THE SEARCH EDGE - widen the window" else "") +
            (if (arrival.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) ", BELOW THE TRUSTWORTHY RATIO" else "")
    )
    // Where the energy first rose, as well as where it peaked. A confidence ratio answers "is this
    // noise", and the failure this guards against is a real but wrong arrival: a reflection is a
    // true sound off a true surface and rates as confidently as the direct path.
    println(
        "  onset: " + ONSET_SHARES.indices.joinToString(", ") {
            "${"%.0f".format(ONSET_SHARES[it] * 100)}% at " +
                millis(nanos(arrival.edgeIndices[it] - arrival.index, ChirpGenerator.SAMPLE_RATE))
        }
    )
    if (arrival.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) return null
    return arrival
}

private fun samples(nanos: Long, rate: Int): Int = Math.round(nanos.toDouble() / 1e9 * rate).toInt()

private fun nanos(samples: Int, rate: Int): Long = samples.toLong() * 1_000_000_000L / rate

private fun millis(nanos: Long): String = "%.3f ms".format(nanos / 1e6)

private fun centimetresToNanos(centimetres: Double?): Double =
    (centimetres ?: 0.0) / 100.0 / SPEED_OF_SOUND * 1e9

/** How far an opening instant, or a capture chain's own latency, can put an arrival out. */
private val OPENING_SLACK = ChirpGenerator.SAMPLE_RATE / 2

/**
 * How far the second chirp can be from where the agreed separation puts it, once the first is
 * found: the clock offset's own error, a few milliseconds, with room to spare and nowhere near
 * the second and a half to the other chirp.
 */
private val SLOT_SLACK = ChirpGenerator.SAMPLE_RATE / 20

/** Shares of the peak whose first crossing is reported, so a late peak is visible. */
private val ONSET_SHARES = listOf(0.5, 0.9)

/** Dry air at about twenty degrees. A degree is 0.17%, which over a metre is five microseconds. */
private const val SPEED_OF_SOUND = 343.0
