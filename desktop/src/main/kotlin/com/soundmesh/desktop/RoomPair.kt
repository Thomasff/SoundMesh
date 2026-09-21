package com.soundmesh.desktop

import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.probe.WavFileReader
import java.io.File

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
    if (args.size < 6) {
        error(
            "usage: <handset.wav> <pc.wav> <handsetChirpAtHostNanos> <pcConsumedAtHostNanos> " +
                "<handsetRecordingStartedAtHostNanos> <pcRenderIndex> [handsetSelfCm] [pcSelfCm]"
        )
    }
    val handsetFile = File(args[0])
    val pcFile = File(args[1])
    val asked = args[2].toLong()
    val consumed = args[3].toLong()
    val handsetOpened = args[4].toLong()
    val pcRenderIndex = args[5].toInt()
    val handsetSelfNanos = centimetresToNanos(args.getOrNull(6)?.toDouble())
    val pcSelfNanos = centimetresToNanos(args.getOrNull(7)?.toDouble())

    val reference = ChirpGenerator.generateMono()
    val rate = ChirpGenerator.SAMPLE_RATE
    val handsetTrack = read(handsetFile, rate)
    val pcTrack = read(pcFile, rate)

    // The separation the two sides agreed on, in samples. Both files are searched against it and
    // neither is searched against the other, so a failure to find one chirp cannot drag the other
    // window onto the wrong sound.
    val apartSamples = samples(consumed - asked, rate)

    println()
    println("--- the handset's recording ---")
    // The opening instant first, because it is the only anchor either file has, and it is good to
    // about half a second: CalibrationRunner reads it after the capture device has opened and its
    // own KDoc calls half a second ample.
    val handsetOwn = find(
        handsetTrack, reference, samples(asked - handsetOpened, rate), OPENING_SLACK, "handset chirp"
    ) ?: return
    val pcInHandset = find(
        handsetTrack, reference, handsetOwn.index + apartSamples, SLOT_SLACK, "pc chirp"
    ) ?: return

    println()
    println("--- this machine's recording ---")
    // Anchored on where the engine consumed the frame, which is earlier than the arrival by the
    // capture chain's own latency - an unknown this program never has to name, because the half
    // second of slack covers it and only the two arrivals' separation is used.
    val pcOwn = find(pcTrack, reference, pcRenderIndex, OPENING_SLACK, "pc chirp") ?: return
    val handsetInPc = find(
        pcTrack, reference, pcOwn.index - apartSamples, SLOT_SLACK, "handset chirp"
    ) ?: return

    val heardHandset = nanos(pcInHandset.index - handsetOwn.index, rate)
    val heardPc = nanos(handsetInPc.index - pcOwn.index, rate)
    val deltaHandset = heardHandset - (consumed - asked)
    val deltaPc = heardPc - (asked - consumed)

    println()
    println("asked apart            : ${millis(consumed - asked)}")
    println("heard apart (handset)  : ${millis(heardHandset)}  -> delta ${millis(deltaHandset)}")
    println("heard apart (pc)       : ${millis(heardPc)}  -> delta ${millis(deltaPc)}")

    val flightNanos = (deltaHandset + deltaPc).toDouble() / 2 + (handsetSelfNanos + pcSelfNanos) / 2
    val pairNanos = (deltaHandset - deltaPc).toDouble() / 2 + (handsetSelfNanos - pcSelfNanos) / 2

    println()
    println(
        "separation             : ${"%.1f".format(flightNanos / 1e9 * SPEED_OF_SOUND * 100)} cm " +
            "(${"%.3f".format(flightNanos / 1e6)} ms of flight)"
    )
    println("  <- a criterion, not an output. A tape measure already knows this one.")
    println(
        "pair constant          : ${"%.3f".format(pairNanos / 1e6)} ms, this machine's output " +
            "delay minus the handset's"
    )
    println(
        "  <- carries a residue of the two cases' own geometry, bounded by their speaker-to-" +
            "microphone separations and independent of the distance above. Run this again at a " +
            "very different separation: this number has to come back the same."
    )
    if (handsetSelfNanos == 0.0 || pcSelfNanos == 0.0) {
        println()
        println(
            "Both answers are missing a speaker-to-own-microphone path that was not given. Each " +
                "is a ruler measurement on one device's case: pass them as the last two arguments " +
                "in centimetres. Ten centimetres unaccounted for is 0.146 ms on either answer."
        )
    }

    crossCheck(handsetTrack, pcTrack, reference, rate, apartSamples, pairNanos, flightNanos)
}

/**
 * The same two recordings read by the analysis the product ships, printed beside this program's.
 *
 * Not a second opinion for its own sake. [AlignmentAnalysis.combineFacing] is this same algebra -
 * its half sum is the firing offset and its half difference the flight time - so the two ought to
 * agree to the noise, and where they do not, one of the two is the number a handset will be told
 * to apply to every note it plays. Measured on the two archived rounds: they agreed to 0.050 ms at
 * 140 cm and parted by 0.498 ms at 90 cm, and at 90 cm it was the shipped reading that missed the
 * tape by 13 cm where this one missed it by 4.
 *
 * They differ in one place. Both take an early arrival rather than the loudest, but the shipped
 * one answers with the lag where the score first reaches a share of the peak, and this one answers
 * with the peak that crossing belongs to. A flank crossing moves with the flank, and a chirp from
 * a speaker a few centimetres away is sixty times louder than the one from across the room: its
 * share of the peak sits far down a skirt of case resonance more than a millisecond wide, and the
 * crossing slides along it. Profiled on the 90 cm round, the handset's own chirp was already at
 * 0.25 of its peak 1.2 ms early, so the 20% lag and the 30% lag sat 0.9 ms apart on one chirp
 * while the far chirp's two sat together.
 *
 * The spread across shares is printed for the half sum because nothing else prints it. The product
 * measures that spread on the half difference and refuses a distance on it; the number it hands a
 * handset is the half sum, and no gate has ever looked at that one's spread. On both archived
 * rounds it was about 1.5 ms, against a distance gate of 1.0 m - near 3 ms - that passed both.
 */
private fun crossCheck(
    handsetTrack: ShortArray,
    pcTrack: ShortArray,
    reference: ShortArray,
    rate: Int,
    apartSamples: Int,
    pairNanos: Double,
    flightNanos: Double
) {
    val shares = AlignmentAnalysis.DISTANCE_EDGE_SHARES
    // The handset chirps first, so this machine is the later slot, which is the side combineFacing
    // calls the host - see AlignmentAnalysis.facingPairs. Swapped, it answers every pair inside out.
    val sink = AlignmentAnalysis.read(
        handsetTrack, reference, apartSamples, SLOT_SLACK, 0.0, edgeShares = shares
    )
    val host = AlignmentAnalysis.read(
        pcTrack, reference, apartSamples, SLOT_SLACK, 0.0, edgeShares = shares
    )
    val pair = AlignmentAnalysis.combineFacing(host, sink)

    println()
    println("--- the same two files, read by the analysis the product ships ---")
    if (pair == null) {
        println("combineFacing found nothing it would vouch for: ${sink.confidence} / ${host.confidence}")
        return
    }
    val firing = pair.firingOffsetMs
    println(
        "pair constant (edge share ${"%.2f".format(shares[0])})  : " +
            (firing?.let { "${"%+.3f".format(it)} ms, ${"%+.3f".format(it - pairNanos / 1e6)} from above" }
                ?: "null - the two sides swept different shares")
    )
    println(
        "pair constant (loudest lag)      : ${"%+.3f".format(pair.alignmentErrorMs)} ms, " +
            "${"%+.3f".format(pair.alignmentErrorMs - pairNanos / 1e6)} from above"
    )
    println(
        "separation                       : ${"%.1f".format(pair.separationMetres * 100)} cm, " +
            "${"%+.1f".format(pair.separationMetres * 100 - flightNanos / 1e9 * SPEED_OF_SOUND * 100)} from above"
    )
    val halfSums = shares.indices.map { (sink.rawMsByShare[it] + host.rawMsByShare[it]) / 2 }
    println(
        "  across all of $shares the half sum spans " +
            "${"%.3f".format(halfSums.max() - halfSums.min())} ms and the half difference " +
            "${"%.2f".format(pair.separationSpreadMetres ?: 0.0)} m - only the second is gated"
    )
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
private fun find(track: ShortArray, reference: ShortArray, expected: Int, slack: Int, what: String): ChirpArrival? {
    val arrival = ChirpCorrelator.findFirstArrival(
        track, reference, expected - slack, expected + slack, edgeShares = ONSET_SHARES
    )
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
