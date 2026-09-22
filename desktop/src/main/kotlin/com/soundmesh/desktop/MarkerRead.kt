package com.soundmesh.desktop

import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.probe.WavFileWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Records a handset streaming markers and reports the spacing between them.
 *
 * Answers the one question left open by O17: the quantised ladder - three levels, 52 frames a
 * step - was measured on chirp chunks, which are released exactly. The pair constant the product
 * stores is measured there and then spent on streamed chunks, which carry a 48 frame trim deadband
 * and sit under a drift controller. If the streamed path jumps too, the product's estimator should
 * keep taking a centre; if it holds one level, the estimator should take the floor.
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.MarkerReadKt \
 *       <seconds> <out.wav> [strideChunks] [framesPerChunk]
 *
 * Start the handset first: `marker_stride_chunks` on the probe's host run puts the same sweep the
 * chirp path submits into the generator's output every `strideChunks` chunks.
 *
 * **Reads gaps, never absolute instants.** No clock exchange happens here and none is wanted: a
 * marker's absolute arrival carries the two machines' clock offset, the capture chain's latency
 * and the distance between them, none of which this needs. The spacing between consecutive markers
 * carries none of those - they are constant across one recording and cancel in a difference. What
 * survives is the placement error of each release, which is the quantity under test.
 *
 * Deliberately not a line fit through the arrivals. The two clocks' rate difference would be the
 * slope, and a straight line through a staircase reports a slope the data never had - the same trap
 * that once produced a 183 ppm reading out of six discrete jumps. A difference of neighbours has no
 * slope to get wrong.
 */
fun main(args: Array<String>) {
    val seconds = args.getOrNull(0)?.toDouble() ?: 60.0
    val outPath = args.getOrNull(1) ?: error("usage: <seconds> <out.wav> [strideChunks] [framesPerChunk]")
    val strideChunks = args.getOrNull(2)?.toInt() ?: 250
    val framesPerChunk = args.getOrNull(3)?.toInt() ?: 960

    val chirp = ChirpGenerator.generateMono()
    val strideSamples = strideChunks * framesPerChunk

    WasapiCapture(raw = true).use { capture ->
        println("capture: ${capture.deviceName ?: "(unnamed)"} - ${capture.format}, RAW")
        // The sweep is correlated against the recording sample for sample, so an endpoint at
        // another rate is reading a different signal from the one being looked for.
        check(capture.format.sampleRate == ChirpGenerator.SAMPLE_RATE) {
            "the capture endpoint is at ${capture.format.sampleRate} Hz and the sweep is at " +
                "${ChirpGenerator.SAMPLE_RATE} Hz"
        }
        println("stride : $strideChunks chunks of $framesPerChunk = $strideSamples samples " +
            "(${"%.3f".format(strideSamples.toDouble() / ChirpGenerator.SAMPLE_RATE)} s)")
        println("reading: $seconds s, playing nothing")

        capture.start()
        Thread.sleep((seconds * 1000).toLong())
        capture.stop()

        val recording = capture.take()
        val mono = recording.mono
        val file = File(outPath)
        WavFileWriter(file, recording.format.sampleRate, 1).use { writer ->
            val bytes = ByteArray(mono.size * 2)
            mono.forEachIndexed { index, sample ->
                bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
                bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
            }
            writer.writePcm(bytes, bytes.size)
        }
        val peak = mono.maxOfOrNull { abs(it.toInt()) } ?: 0
        println("heard  : ${mono.size} samples, peak $peak of ${Short.MAX_VALUE}")
        println("wrote  : ${file.absolutePath}")
        if (peak < AUDIBLE_PEAK) {
            println("       : that is not a room. Check the capture endpoint before reading anything.")
            return
        }
        println()

        // The first marker is searched for across one whole stride, because any window that wide
        // holds exactly one of them and nothing says which. Every later one is searched in a narrow
        // window around where the previous one puts it. Stepping from the previous *measured*
        // arrival rather than from the first keeps the two clocks' rate difference from
        // accumulating into the window, and a window far narrower than the stride is what stops a
        // global maximum from answering with the wrong repeat - four identical sweeps once cost a
        // whole interval that way, at an infinite confidence ratio.
        val arrivals = ArrayList<ChirpArrivalAt>()
        val first = ChirpCorrelator.findFirstArrival(mono, chirp, 0, strideSamples)
        if (first == null || first.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) {
            println("no marker in the first $strideSamples samples " +
                "(${first?.let { "ratio ${"%.0f".format(it.ratio)}" } ?: "nothing correlated"}).")
            println("Is the handset running with marker_stride_chunks set, and is the stride right?")
            return
        }
        arrivals.add(ChirpArrivalAt(first.firstArrivalIndex ?: first.index, first.ratio, first.atSearchEdge))

        while (true) {
            val expected = arrivals.last().index + strideSamples
            val from = expected - WINDOW_SAMPLES
            val to = expected + WINDOW_SAMPLES
            if (to + chirp.size >= mono.size) break
            val next = ChirpCorrelator.findFirstArrival(mono, chirp, from, to)
            if (next == null || next.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) {
                println("marker ${arrivals.size} missing near $expected " +
                    "(${next?.let { "ratio ${"%.0f".format(it.ratio)}" } ?: "nothing correlated"})")
                break
            }
            arrivals.add(ChirpArrivalAt(next.firstArrivalIndex ?: next.index, next.ratio, next.atSearchEdge))
        }

        println("found  : ${arrivals.size} markers")
        if (arrivals.size < 3) {
            println("Too few to say anything about the spacing. Record longer.")
            return
        }

        val gaps = arrivals.zipWithNext { a, b -> b.index - a.index }
        val median = gaps.sorted()[gaps.size / 2]
        println()
        println("  #   sample      gap   gap-median   ratio  edge")
        arrivals.forEachIndexed { index, arrival ->
            val gap = if (index == 0) null else arrival.index - arrivals[index - 1].index
            println(
                "%3d %9d %8s %12s %7.0f  %s".format(
                    index, arrival.index,
                    gap?.toString() ?: "-",
                    gap?.let { (it - median).toString() } ?: "-",
                    arrival.ratio,
                    if (arrival.atSearchEdge) "EDGE" else ""
                )
            )
        }

        val lifts = gaps.map { it - median }
        val worst = lifts.maxOf { abs(it) }
        val quantised = lifts.count { abs(abs(it) - QUANTUM_FRAMES) <= QUANTUM_TOLERANCE }
        println()
        println("gaps   : median $median samples, spread ${lifts.min()} .. ${lifts.max()}")
        println("       : ${millisOf(worst)} worst departure from the median")
        println("       : $quantised of ${lifts.size} sit within $QUANTUM_TOLERANCE frames of " +
            "+-$QUANTUM_FRAMES, the step O17 measured on the chirp path")
        println()
        // Stated as the two readings this can produce rather than as a verdict, because the
        // deadband puts a band of its own into every gap: a streamed chunk released under
        // TRIM_DEADBAND_FRAMES late is written whole and heard that late, so up to 48 frames of
        // one-sided lateness is by design and is not the ladder. Only a gap near the 52 frame step
        // can be the ladder, and only a spread inside the deadband's own band can rule it out.
        when {
            quantised > 0 ->
                println("reading: the streamed path jumps too. The product's constant should keep " +
                    "taking a centre, and a ${millisOf(QUANTUM_FRAMES)} step needs its own audibility check.")
            worst <= DEADBAND_FRAMES ->
                println("reading: every gap sits inside the 48 frame deadband's own band and none " +
                    "near the 52 frame step. The streamed path does not inherit the ladder, so the " +
                    "product's constant should take the floor.")
            else ->
                println("reading: neither - the departures are larger than the deadband can explain " +
                    "and not at the step either. Report the distribution, do not pick a branch.")
        }
    }
}

private data class ChirpArrivalAt(val index: Int, val ratio: Double, val atSearchEdge: Boolean)

private fun millisOf(frames: Int): String =
    "%.3f ms".format(frames.toDouble() * 1000.0 / ChirpGenerator.SAMPLE_RATE)

/**
 * 30 ms either side of where the previous marker puts the next one. Wide enough for the whole of
 * the deadband, the ladder and any credible clock drift over one stride; far narrower than the
 * stride, which is what keeps a second sweep out of the window.
 */
private const val WINDOW_SAMPLES = 1440

/** The step O17 measured on the chirp path: 52 +- 1 frames, three levels. */
private const val QUANTUM_FRAMES = 52

/** Half a step. Anything closer to 52 than to 0 counts as being at the step. */
private const val QUANTUM_TOLERANCE = 26

/** `SyncRenderer.TRIM_DEADBAND_FRAMES`, which the desktop module does not depend on. */
private const val DEADBAND_FRAMES = 48

/** Below this the recording is not of a room, and nothing in it should be read as a measurement. */
private const val AUDIBLE_PEAK = 200
