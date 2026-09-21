package com.soundmesh.desktop

import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Experiment seven: does the machine hear itself where it said it would put the sound?
 *
 * The renderer can put a chirp out at a stated tick - experiment six measured that to a handful of
 * microseconds. Nothing yet says the other half of a calibration works here: a capture stream whose
 * samples carry times on the same clock, close enough that a difference between them is a
 * measurement rather than a guess.
 *
 * Loopback is why this can be checked on one machine. The stream comes off the *render* endpoint,
 * so what arrives is the mix the engine was about to hand the hardware - the same samples, with no
 * speaker, no air and no microphone. That makes one of the two numbers below a perfect criterion:
 *
 *  - **The spacing between chirps is known exactly.** It is a number this file chose. Whatever
 *    constant sits between the two streams cancels out of a difference, so the heard spacing has to
 *    come back equal to the asked-for spacing, to the frame. A disagreement is this layer's fault
 *    and nothing else's.
 *
 *  - **The offset itself is not known.** How far the loopback tap sits from the frame the clock is
 *    counting is a property of the engine that nobody has measured here. So it is reported rather
 *    than judged, and the thing to look at is whether it is the same for every chirp and the same
 *    between runs. A constant can be subtracted; a wandering one cannot.
 *
 * The correlator and the chirp are core's, unchanged - the same code the handsets have been
 * measuring with since the first run. That is the point of running them here rather than writing
 * something to match: a Windows answer produced by a different analysis would not be comparable
 * with any archived number, and comparability is the whole reason this client exists.
 *
 * Run it off the installed distribution's lib directory, the way experiment six's arms were run:
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.LoopbackKt [shots]
 */
fun main(args: Array<String>) {
    val shots = if (args.isNotEmpty()) args[0].toInt() else 4
    val packetCsv = if (args.size > 1 && args[1] != "-") args[1] else null
    val leadSeconds = 1.5
    val gapSeconds = 0.8
    val tailSeconds = 1.6
    val searchMillis = 300.0
    val renderBufferMillis = if (args.size > 2) args[2].toLong() else 200L

    val chirp = ChirpGenerator.generateMono()

    WasapiRenderer(renderBufferMillis).use { renderer ->
        WasapiCapture(loopback = true).use { capture ->
            val rate = renderer.format.sampleRate
            val qpf = renderer.qpcFrequency

            println("render : ${renderer.format}")
            println("capture: ${capture.format}  (loopback, buffer ${capture.bufferFrames} frames)")
            check(capture.format.sampleRate == rate) {
                "loopback came back at ${capture.format.sampleRate} Hz against the render " +
                    "endpoint's $rate Hz, which should be impossible on one device"
            }
            println("clock  : QPC ${qpf} Hz, audio clock ${renderer.clockFrequency}")
            println("buffer : render ${renderer.bufferFrames} frames (${renderBufferMillis} ms asked)")
            println()

            Wasapi.timeBeginPeriod(1)
            try {
                // Capture first: a loopback stream started after the chirp was scheduled could
                // begin inside it, and a chirp cut in half correlates to something that looks
                // like an arrival.
                capture.start()
                val startedAt = renderer.now()
                renderer.start()
                val runningAt = renderer.now()

                val anchor = renderer.sampleClock()
                println(
                    "start  : Start() to a moving clock took " +
                        "${"%.1f".format((runningAt - startedAt) * 1000.0 / qpf)} ms; " +
                        "anchor frame ${anchor.frames} stamped ${anchor.qpcPosition}, " +
                        "now ${renderer.now()}, written ${renderer.framesWritten()}"
                )
                val gapFrames = (rate * gapSeconds).toLong()
                val first = renderer.frameAt(
                    renderer.now() + (qpf * leadSeconds).toLong(), anchor
                )
                val frames = LongArray(shots) { first + it * gapFrames }
                for (frame in frames) renderer.schedule(chirp, frame)

                println(
                    "playing $shots chirps, $gapFrames frames apart " +
                        "(${"%.3f".format(gapFrames * 1000.0 / rate)} ms), listening back ..."
                )

                val until = renderer.qpcAt(frames.last() + chirp.size, anchor) +
                    (qpf * tailSeconds).toLong()
                while (renderer.now() < until) Thread.sleep(20)

                // A second reading of the same clock, taken after seconds of running rather than
                // in the first few milliseconds of it. Experiment six found that a reading taken
                // too early is silently wrong by milliseconds, and the fix it got - waiting for
                // the position to move at all - is only known to be necessary, not sufficient.
                val late = renderer.sampleClock()

                // Capture before renderer: the engine stops running when nothing is rendering,
                // and the tail of the last chirp is still inside it.
                capture.stop()
                renderer.stop()

                val recording = capture.take()
                packetCsv?.let { writePackets(it, recording) }
                report(recording, renderer, anchor, late, frames, chirp, searchMillis)
            } finally {
                Wasapi.timeEndPeriod(1)
            }
        }
    }
}

/**
 * Every packet as it arrived, so a gap can be looked at rather than reasoned about.
 *
 * `gap` is what the engine's own position counter says is missing in front of this packet. It is
 * the field the recording's timeline is built on, and the only way to tell a lost frame from a
 * counter that jumped for some other reason is to read the two stamps beside it.
 */
private fun writePackets(path: String, recording: Recording) {
    val sb = StringBuilder(recording.packets.size * 48 + 128)
    sb.append("i,device_position,qpc_position,frames,flags,gap\n")
    var expected = recording.firstDevicePosition
    recording.packets.forEachIndexed { i, p ->
        sb.append(i).append(',')
            .append(p.devicePosition).append(',')
            .append(p.qpcPosition).append(',')
            .append(p.frames).append(',')
            .append(p.flags).append(',')
            .append(p.devicePosition - expected).append('\n')
        expected = p.devicePosition + p.frames
    }
    java.nio.file.Files.write(
        java.nio.file.Path.of(path),
        sb.toString().toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
    )
    println("wrote $path")
}

private fun report(
    recording: Recording,
    renderer: WasapiRenderer,
    anchor: ClockSample,
    late: ClockSample,
    frames: LongArray,
    chirp: ShortArray,
    searchMillis: Double
) {
    val rate = recording.format.sampleRate
    val qpf = recording.qpcFrequency
    val discontinuities = recording.packets.count {
        it.flags and Wasapi.BUFFERFLAGS_DISCONTINUITY != 0
    }
    val silent = recording.packets.count { it.flags and Wasapi.BUFFERFLAGS_SILENT != 0 }
    val peak = recording.mono.maxOf { abs(it.toInt()) }

    println()
    println(
        "heard  : ${recording.mono.size} frames " +
            "(${"%.2f".format(recording.mono.size.toDouble() / rate)} s) in " +
            "${recording.packets.size} packets, ${recording.skippedFrames} frames skipped${if (recording.filled) " and filled" else ""}, " +
            "$discontinuities discontinuities, $silent silent packets"
    )
    println("level  : peak $peak of 32767, chirp was generated at 12000")
    if (peak < 100) {
        println("         nothing came back - the endpoint is muted, or the mix is silent")
        return
    }

    // Where the recording is not digital silence, before anything correlates anything. A
    // correlator asked a question always answers it, and the first three runs of this experiment
    // were answered entirely out of the search window's edge. This cannot be.
    val bursts = ArrayList<IntArray>()
    for (i in recording.mono.indices) {
        if (abs(recording.mono[i].toInt()) <= 100) continue
        // Joined across a quiet stretch shorter than a chirp, because a chirp sweeping up from a
        // kilohertz crosses zero often enough to look like several bursts otherwise.
        val last = bursts.lastOrNull()
        if (last != null && i - last[1] < chirp.size) last[1] = i else bursts.add(intArrayOf(i, i))
    }
    println(
        "sound  : ${bursts.size} bursts above 100" +
            bursts.joinToString("") { " [${it[0]}..${it[1]}]" }
    )

    // The two origins the whole answer hangs on, printed rather than inferred: one reading of the
    // render clock and one packet of the capture stream. Everything else in this run is a count
    // of frames from one of these two.
    val a = recording.packets.first()
    println(
        "anchors: render frame ${anchor.frames} at ${anchor.qpcPosition}, " +
            "capture index ${a.atIndex} (device ${a.devicePosition}) at ${a.qpcPosition}, " +
            "capture ${"%+.3f".format((a.qpcPosition - anchor.qpcPosition) * 1000.0 / qpf)} ms " +
            "before/after the render one"
    )

    val residual = recording.stampResidualTicks()
    if (residual.isNotEmpty()) {
        val sd = sqrt(residual.sumOf { it * it } / residual.size) * 1e6 / qpf
        val worst = residual.maxByOrNull { abs(it) }!! * 1e6 / qpf
        println(
            "stamps : ${residual.size} packets, sd ${"%.1f".format(sd)} us from a straight " +
                "line, worst ${"%+.1f".format(worst)} us"
        )
    }
    println()

    // Each chirp is looked for around where the clocks say it should be, and nowhere else.
    //
    // A single sweep of the whole recording was tried first and is the wrong instrument here: the
    // four chirps are the same signal, so their peaks are the same height, and which one comes out
    // largest is decided by noise. It picked the second chirp and answered +889 ms, an answer that
    // is exactly one 800 ms gap away from the truth and carries a confidence ratio of infinity.
    // The window is wide enough that a real pipeline delay fits inside it and narrow enough that
    // the neighbouring chirp does not.
    val searchFrames = (rate * searchMillis / 1000.0).toInt()
    check(searchFrames * 2 < (frames[1] - frames[0])) {
        "a window of $searchFrames frames either side reaches the next chirp"
    }
    val heard = frames.map { frame ->
        val expected = recording.indexAt(renderer.qpcAt(frame, anchor))
        ChirpCorrelator.findArrival(
            recorded = recording.mono,
            reference = chirp,
            searchFrom = expected - searchFrames,
            searchTo = expected + searchFrames
        )
    }

    heard.forEachIndexed { shot, arrival ->
        if (arrival == null) {
            println("chirp ${shot + 1}: not found in the search window")
            return@forEachIndexed
        }
        val offUs = (recording.qpcAt(arrival.index) - renderer.qpcAt(frames[shot], anchor)) *
            1e6 / qpf
        println(
            "chirp ${shot + 1}: index ${arrival.index}, peak ${"%.3g".format(arrival.peak)}, " +
                "ratio ${"%.0f".format(arrival.ratio)}" +
                (if (arrival.atSearchEdge) " AT SEARCH EDGE" else "") +
                ", ${"%+.3f".format(offUs / 1000.0)} ms from the tick it was scheduled for"
        )
    }
    println()

    val found = heard.filterNotNull()
    if (found.size < 2 || found.size != heard.size) {
        println("fewer than every chirp was found, so neither criterion can be answered")
        return
    }

    // The criterion with a known answer. Both ends of a spacing carry the same unknown constant,
    // so it cancels: what comes back has to be what was asked for.
    println("asked for the gaps below; a loopback stream has nothing that could change them")
    var worstGap = 0
    for (shot in 1 until found.size) {
        val askedFrames = frames[shot] - frames[shot - 1]
        val heardFrames = (found[shot].index - found[shot - 1].index).toLong()
        val off = (heardFrames - askedFrames).toInt()
        if (abs(off) > abs(worstGap)) worstGap = off
        println(
            "gap ${shot}: asked $askedFrames frames, heard $heardFrames, " +
                "${"%+d".format(off)} frames (${"%+.1f".format(off * 1e6 / rate)} us)"
        )
    }
    println(
        if (worstGap == 0) "         exact on every gap"
        else "         off by up to ${"%+d".format(worstGap)} frames - this layer is wrong"
    )
    println()

    // And the one that has no known answer, reported as what it is.
    // Four ways of asking the same question, differing only in which reading of which clock the
    // two timelines are pinned to. Within a run they all give the same spread; between runs, the
    // one that does not move is the one to build on.
    println("loopback constant: how far the tap sits from the frame the audio clock is counting")
    for ((name, offsets) in listOf(
        "first packet / early clock" to found.mapIndexed { shot, a ->
            (recording.qpcAt(a.index) - renderer.qpcAt(frames[shot], anchor)) * 1000.0 / qpf
        },
        "first packet / late clock" to found.mapIndexed { shot, a ->
            (recording.qpcAt(a.index) - renderer.qpcAt(frames[shot], late)) * 1000.0 / qpf
        },
        "fitted packets / early clock" to found.mapIndexed { shot, a ->
            (recording.fittedQpcAt(a.index) - renderer.qpcAt(frames[shot], anchor)) * 1000.0 / qpf
        },
        "fitted packets / late clock" to found.mapIndexed { shot, a ->
            (recording.fittedQpcAt(a.index) - renderer.qpcAt(frames[shot], late)) * 1000.0 / qpf
        }
    )) {
        println(
            "  ${name.padEnd(28)} ${"%+8.3f".format(offsets.average())} ms, " +
                "spread ${"%.3f".format(offsets.max() - offsets.min())} ms"
        )
    }
    println(
        "  clock read twice: frame ${anchor.frames} then ${late.frames}, " +
            "${"%.1f".format((late.qpcPosition - anchor.qpcPosition) * 1000.0 / qpf)} ms apart"
    )
    println()
    println("  It is NOT the output delay. Nothing here went through a speaker.")
}
