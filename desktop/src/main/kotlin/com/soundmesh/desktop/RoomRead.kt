package com.soundmesh.desktop

import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.probe.WavFileReader
import java.io.File

/**
 * Reads a handset's room recording and says how far this machine's sound ran behind its schedule.
 *
 * Two chirps are in the file. The handset put one out on a grid instant of its own clock, and this
 * machine put one out [com.soundmesh.core.RoomGrid.PEER_SLOT_NANOS] later, aimed at the same grid
 * through the clock offset. Both travelled the same microphone, the same capture chain and the
 * same room, seconds apart.
 *
 *   java -cp "<lib>" com.soundmesh.desktop.RoomReadKt \
 *       <calibration.wav> <handsetChirpAtHostNanos> <pcConsumedAtHostNanos> <recordingStartedAtHostNanos>
 *
 * **What comes out.** Writing A for the handset's instant, P for the instant this machine's engine
 * consumed its chirp's first frame, and s for where each arrived in the recording:
 *
 *     residual = (s_pc - s_handset) / rate - (P - A)
 *              = (this machine's output delay - the handset's)
 *              + (the distance from this speaker to that microphone - the handset's own few
 *                 centimetres) / the speed of sound
 *
 * The microphone's own input latency and the instant the recording opened are common to the two
 * arrivals and leave exactly, which is why nothing here has to trust either.
 *
 * **The residual on its own is not the output delay constant and must not be read as one.** It
 * carries the handset's output delay and the distance between the two machines, neither of which
 * this program knows. What it is for is the difference between two runs: move the handset a
 * measured distance along the line between them and everything but that distance cancels, so the
 * change in the residual has to come back equal to the distance over the speed of sound. That is a
 * criterion whose answer a tape measure already knows, and it is the only thing in this
 * arrangement that a wrong constant anywhere cannot fake.
 *
 * **Both searches are windowed, and the handset's goes first.** Two identical chirps in one file
 * is exactly the shape that makes a whole-file maximum answer confidently about the wrong one -
 * the same mistake that once put an answer a full interval out with an infinite confidence ratio.
 * So the handset's arrival is found inside the half second the recording's opening instant is good
 * for, and this machine's is then found in a narrow window measured from it.
 */
fun main(args: Array<String>) {
    if (args.size < 4) {
        error(
            "usage: <calibration.wav> <handsetChirpAtHostNanos> <pcConsumedAtHostNanos> " +
                "<recordingStartedAtHostNanos>"
        )
    }
    val file = File(args[0])
    val handsetAsked = args[1].toLong()
    val pcConsumed = args[2].toLong()
    val recordingStarted = args[3].toLong()

    val recorded = WavFileReader.readMono(file)
    val rate = WavFileReader.sampleRateOf(file)
    check(rate == ChirpGenerator.SAMPLE_RATE) {
        "the recording is at $rate Hz and the chirp is at ${ChirpGenerator.SAMPLE_RATE} Hz"
    }
    val reference = ChirpGenerator.generateMono()
    println("file   : ${file.name}, ${recorded.size} samples at $rate Hz " +
        "(${"%.2f".format(recorded.size.toDouble() / rate)} s)")

    val handsetExpected = samplesBetween(recordingStarted, handsetAsked, rate)
    val handset = ChirpCorrelator.findArrival(
        recorded,
        reference,
        handsetExpected - OPENING_UNCERTAINTY_SAMPLES,
        handsetExpected + OPENING_UNCERTAINTY_SAMPLES
    ) ?: run { println("the handset's chirp is not in the file where the opening instant puts it"); return }
    report("handset", handset, handsetExpected)

    // Measured from the arrival just found rather than from the opening instant: the half second
    // of slack in that instant is already spent, and spending it twice would make this window
    // wide enough to reach the other chirp.
    val pcExpected = handset.index + samplesBetween(handsetAsked, pcConsumed, rate)
    val pc = ChirpCorrelator.findArrival(
        recorded,
        reference,
        pcExpected - SLOT_UNCERTAINTY_SAMPLES,
        pcExpected + SLOT_UNCERTAINTY_SAMPLES,
        ONSET_SHARES
    ) ?: run { println("this machine's chirp is not in the file where the schedule puts it"); return }
    report("pc     ", pc, pcExpected)
    // Where the energy first rose, as well as where it peaked. A confidence ratio answers "is
    // this noise", and the failure this guards against is a real but wrong arrival: a reflection
    // is a true sound off a true surface and rates as confidently as the direct path. Anything
    // that first reaches most of the peak well before the peak means the loudest path is not the
    // shortest one, and the shortest is what a distance measurement is about.
    println(
        "  onset: " + ONSET_SHARES.indices.joinToString(", ") {
            "${"%.0f".format(ONSET_SHARES[it] * 100)}% at " +
                "${millis((pc.edgeIndices[it] - pc.index).toLong() * 1_000_000_000L / rate)}"
        }
    )

    val heardApartNanos = (pc.index - handset.index).toLong() * 1_000_000_000L / rate
    val askedApartNanos = pcConsumed - handsetAsked
    val residual = heardApartNanos - askedApartNanos
    println()
    println("apart  : heard ${millis(heardApartNanos)}, asked ${millis(askedApartNanos)}")
    println("residual: ${millis(residual)}")
    println()
    println(
        "Not an output delay on its own - it holds the handset's own constant and the distance " +
            "between the two machines. Run this at two placements and difference them: the change " +
            "has to be the distance moved over ${"%.0f".format(SPEED_OF_SOUND)} m/s, and " +
            "${millis(residual)} of change is ${"%.3f".format(residual / 1e9 * SPEED_OF_SOUND)} m."
    )
}

private fun report(who: String, arrival: com.soundmesh.core.ChirpArrival, expected: Int) {
    println(
        "$who: sample ${arrival.index} (${"%+d".format(arrival.index - expected)} from expected), " +
            "ratio ${"%.1f".format(arrival.ratio)}" +
            (if (arrival.atSearchEdge) ", AT THE SEARCH EDGE - widen the window" else "") +
            (if (arrival.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) ", BELOW THE TRUSTWORTHY RATIO" else "")
    )
}

private fun samplesBetween(fromNanos: Long, toNanos: Long, rate: Int): Int =
    Math.round((toNanos - fromNanos).toDouble() / 1e9 * rate).toInt()

private fun millis(nanos: Long): String = "%.3f ms".format(nanos / 1e6)

/**
 * How far the recording's opening instant can be out.
 *
 * [com.soundmesh.probe.sync.CalibrationRunner.startedAtHostNanos] is read after the capture
 * device has opened and its own KDoc calls half a second ample, so half a second is what this
 * window allows either way.
 */
private val OPENING_UNCERTAINTY_SAMPLES = ChirpGenerator.SAMPLE_RATE / 2

/**
 * How far this machine's chirp can be from where the schedule puts it, once the handset's is
 * found: the clock offset's own error over WiFi, a few milliseconds, with room to spare and
 * nowhere near the second and a half to the other chirp.
 */
private val SLOT_UNCERTAINTY_SAMPLES = ChirpGenerator.SAMPLE_RATE / 20

/**
 * Shares of the peak whose first crossing is reported, so an arrival that peaks later than it
 * starts is visible rather than assumed away.
 */
private val ONSET_SHARES = listOf(0.3, 0.5, 0.7, 0.9)

/** Dry air at about twenty degrees. A degree is 0.17%, which over a metre is five microseconds. */
private const val SPEED_OF_SOUND = 343.0
