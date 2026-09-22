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
 * **Reads relative placement, never absolute instants.** No clock exchange happens here and none
 * is wanted for this question: a marker's absolute arrival carries the two machines' clock offset,
 * the capture chain's latency and the distance between them. All three are constant across one
 * recording, so removing a straight line removes all three at once, and what survives is the
 * placement error of each release - the quantity under test.
 *
 * The slope of that line is the two clocks' rate difference, and it is estimated three ways and
 * printed three ways on purpose. A straight line through a staircase reports a slope the data never
 * had - the trap that once produced a 183 ppm reading out of six discrete jumps - and the endpoint
 * estimate parting from the fit is what says the shape is not a line.
 *
 * What it reads is the residual, not the gap column. Differencing white noise gives a lag-1
 * autocorrelation of -0.5 on its own, so a gap column whose sign alternates says nothing about the
 * device; the alternation is the difference operator. A ladder would be visible as residuals piling
 * near multiples of the step.
 *
 * **And the line took the mean with it.** Which level playback sits on is exactly that mean, so
 * choosing between the floor and the mean for the product's stored constant needs an absolute
 * offset this cannot produce. This answers whether the level is redrawn, and nothing more.
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
        val medianGap = gaps.sorted()[gaps.size / 2]
        // Three slope estimates, printed together. The two machines' sample rates differ, so the
        // arrivals sit on a line whose slope is not exactly the stride; that slope has to come off
        // before anything is read. Reported three ways because the endpoint estimate disagreeing
        // with the fit is the signal that the shape is not a line at all - a straight line through
        // a staircase once produced a 183 ppm reading out of six discrete jumps.
        val endpointSlope = (arrivals.last().index - arrivals.first().index).toDouble() / (arrivals.size - 1)
        val meanK = (arrivals.size - 1) / 2.0
        val meanIndex = arrivals.map { it.index.toDouble() }.average()
        var cov = 0.0
        var varK = 0.0
        arrivals.forEachIndexed { k, arrival ->
            cov += (k - meanK) * (arrival.index - meanIndex)
            varK += (k - meanK) * (k - meanK)
        }
        val fitSlope = cov / varK
        println()
        println("slope  : median gap $medianGap, endpoint ${"%.1f".format(endpointSlope)}, " +
            "fit ${"%.1f".format(fitSlope)} (${"%.1f".format((fitSlope / strideSamples - 1) * 1e6)} ppm)")
        if (abs(endpointSlope - fitSlope) > SLOPE_DISAGREEMENT_SAMPLES) {
            println("       : those two disagree. The arrivals are not on a line, so read the " +
                "residual below as a shape, not as a scatter.")
        }

        // Residual against that line. This is the per-release placement error, and it is what the
        // ladder would be visible in - not the gaps. Differencing white noise gives a lag-1
        // autocorrelation of -0.5 all by itself, so a gap column whose sign alternates says
        // nothing at all; the alternation is the difference operator, not the device.
        val residual = arrivals.mapIndexed { k, arrival -> arrival.index - (meanIndex + fitSlope * (k - meanK)) }
        println()
        println("  #   sample      gap   residual   ratio  edge")
        arrivals.forEachIndexed { index, arrival ->
            val gap = if (index == 0) null else arrival.index - arrivals[index - 1].index
            println(
                "%3d %9d %8s %10.1f %7.0f  %s".format(
                    index, arrival.index, gap?.toString() ?: "-", residual[index], arrival.ratio,
                    if (arrival.atSearchEdge) "EDGE" else ""
                )
            )
        }

        val mean = residual.average()
        val spread = Math.sqrt(residual.sumOf { (it - mean) * (it - mean) } / residual.size)
        var autoNum = 0.0
        var autoDen = 0.0
        residual.forEachIndexed { index, value ->
            autoDen += (value - mean) * (value - mean)
            if (index > 0) autoNum += (value - mean) * (residual[index - 1] - mean)
        }
        println()
        println("residual: sd ${"%.1f".format(spread)} frames (${millisOf(spread.roundToInt())}), " +
            "range ${"%.0f".format(residual.min())} .. ${"%.0f".format(residual.max())}")
        println("        : lag-1 autocorrelation ${"%.3f".format(autoNum / autoDen)} " +
            "(near 0 means each release draws its own error)")

        // The quantisation test. Asking "is the departure moderate" accepts almost anything and is
        // not a test - a band of +-26 either side of 52 covers every deviation from 26 to 78
        // frames. A ladder shows as residuals piling up near multiples of the step, so the
        // statistic is the distance to the nearest multiple, against what a spread with no levels
        // in it would give: a uniform spread over one 52 wide bin averages a quarter of the bin.
        val toStep = residual.map {
            val r = ((it % QUANTUM_FRAMES) + QUANTUM_FRAMES) % QUANTUM_FRAMES
            abs(if (r > QUANTUM_FRAMES / 2.0) r - QUANTUM_FRAMES else r)
        }
        val meanToStep = toStep.average()
        val uniformReference = QUANTUM_FRAMES / 4.0
        println()
        println("ladder  : mean distance to the nearest multiple of $QUANTUM_FRAMES is " +
            "${"%.1f".format(meanToStep)} frames; no levels at all would give " +
            "${"%.1f".format(uniformReference)}, levels would give something near 0")
        println()
        when {
            meanToStep < uniformReference / 2 ->
                println("reading: the streamed path jumps on the same step. The product's constant " +
                    "should keep taking a centre, and a ${millisOf(QUANTUM_FRAMES)} step needs its " +
                    "own audibility check.")
            spread <= DEADBAND_FRAMES / 2.0 ->
                println("reading: no levels, and the scatter fits inside the deadband's own band. " +
                    "The streamed path does not inherit the ladder.")
            else ->
                println("reading: no levels, but the scatter is wider than the deadband alone " +
                    "explains. The ladder is ruled out; what sets the width is not.")
        }
        // Said on every run, because the strongest thing this measurement does is also what it
        // cannot do. Removing the line removed the mean with it, and the mean is exactly "which
        // level does playback sit on". Choosing between the floor and the mean for the product's
        // constant needs that absolute offset, which needs a clock exchange - this reads only
        // whether the level is redrawn, not which one it is.
        println()
        println("note   : the detrend removed the absolute offset along with the slope, so this " +
            "says whether the level is redrawn and never which level it is.")
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

/**
 * Endpoint and fit slopes further apart than this say the arrivals are not on a line. One sample
 * per interval, which at a 240000 sample stride is 4 ppm - well inside the two clocks rate
 * difference and well outside what a straight line through a real line would show.
 */
private const val SLOPE_DISAGREEMENT_SAMPLES = 1.0

/** `SyncRenderer.TRIM_DEADBAND_FRAMES`, which the desktop module does not depend on. */
private const val DEADBAND_FRAMES = 48

/** Below this the recording is not of a room, and nothing in it should be read as a measurement. */
private const val AUDIBLE_PEAK = 200
