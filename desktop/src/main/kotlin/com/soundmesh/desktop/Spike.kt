package com.soundmesh.desktop

import com.soundmesh.core.ChirpGenerator
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The Windows client so far: proof that it can be built, and that it can put a sound out at a
 * stated moment.
 *
 * The first three lines are experiment five's criterion and have not changed - each is a number
 * that was already known, so they either match or they do not:
 *
 *   - does core come over untouched?          5760 frames, and the chirp's own numbers
 *   - does Kotlin reach the foreign API?      QPC frequency 10000000
 *   - does that reach WASAPI?                 48000 Hz 2 ch 32 bit float
 *
 * Everything after them is new, and **this one makes a sound**: three chirps, each scheduled for
 * a stated tick, while an independent sampler reads the engine's clock.
 *
 * With a path argument it also writes the probe's CSV, so the archived analyzer at
 * docs/feasibility-results/data/2026-09-21-windows-audio-clock/probe/analyze.cjs reads this run
 * and the eight arms of experiment four off one ruler.
 *
 *   ./gradlew :desktop:run --args="[seconds] [out.csv]"
 */
fun main(args: Array<String>) {
    val seconds = if (args.isNotEmpty()) args[0].toDouble() else 4.0
    val csvPath = if (args.size > 1 && args[1] != "-") args[1] else null
    val leadSeconds = 1.5
    val gapSeconds = 0.7
    val shots = 3
    val intervalMs = 5.0
    val warmupSeconds = 0.5

    val chirp = ChirpGenerator.generateMono()
    val peak = chirp.maxOf { if (it < 0) -it.toInt() else it.toInt() }
    var energy = 0.0
    for (s in chirp) energy += s.toDouble() * s.toDouble()

    println("core   : ChirpGenerator ${ChirpGenerator.SAMPLE_RATE} Hz, ${ChirpGenerator.DURATION_MS} ms")
    println("core   : ${chirp.size} frames, peak $peak, rms ${"%.1f".format(sqrt(energy / chirp.size))}")
    println("ffm    : QPC frequency ${WindowsAudio.qpcFrequency()} Hz")

    WasapiRenderer().use { renderer ->
        println("wasapi : ${renderer.format}")

        val rate = renderer.format.sampleRate
        val qpf = renderer.qpcFrequency
        println(
            "device : buffer ${renderer.bufferFrames} frames " +
                "(${"%.1f".format(renderer.bufferFrames * 1000.0 / rate)} ms), " +
                "latency ${"%.3f".format(renderer.streamLatencyHns / 10000.0)} ms, " +
                "period ${"%.3f".format(renderer.defaultPeriodHns / 10000.0)} ms"
        )
        println(
            "clock  : frequency ${renderer.clockFrequency}" +
                if (renderer.clockFrequency == rate.toLong()) "  (== sample rate, counts frames)"
                else "  (!= sample rate $rate, counts bytes or ticks)"
        )
        println()

        // Without this a one-millisecond sleep waits for the default 15.625 ms tick, and the
        // sampler's own interval becomes the most interesting thing in the data.
        Wasapi.timeBeginPeriod(1)
        try {
            renderer.start()

            // start() does not return until the engine is consuming, so this reading describes
            // a stream that is really running - which is the whole of what made the schedule work.
            val t0 = renderer.now()
            val anchor = renderer.sampleClock()
            val deadlines = LongArray(shots) { t0 + (qpf * (leadSeconds + gapSeconds * it)).toLong() }
            val frames = LongArray(shots) { renderer.frameAt(deadlines[it], anchor) }
            for (i in 0 until shots) renderer.schedule(chirp, frames[i])

            println(
                "playing $shots chirps, ${"%.1f".format(gapSeconds)} s apart, " +
                    "first in ${"%.1f".format(leadSeconds)} s, sampling for ${"%.1f".format(seconds)} s ..."
            )

            val run = collect(renderer, qpf, intervalMs, t0 + (qpf * seconds).toLong())
            renderer.stop()

            csvPath?.let { write(it, run, renderer, intervalMs) }

            val kept = run.samples.filter { it.qpcBefore >= t0 + qpf * warmupSeconds }
            report(kept, frames, deadlines, qpf, rate, run.samples.size, run.skipped)
        } finally {
            Wasapi.timeEndPeriod(1)
        }
    }
}

private class Run(val samples: List<ClockSample>, val skipped: Long)

/**
 * Reads the clock on a fixed interval until [stopAt].
 *
 * A missed deadline is skipped, never fired late. Catching up turns one scheduling hiccup into a
 * burst of back-to-back reads, and section 6 of windows-audio-clock.md is the record of exactly
 * that producing a clean-looking result that was entirely the sampler's doing.
 */
private fun collect(
    renderer: WasapiRenderer,
    qpf: Long,
    intervalMs: Double,
    stopAt: Long
): Run {
    val step = (qpf * intervalMs / 1000.0).toLong()
    val slack = qpf / 500 // 2 ms
    val out = ArrayList<ClockSample>(16384)
    var deadline = renderer.now()
    var skipped = 0L

    while (true) {
        val now = renderer.now()
        if (deadline <= now) {
            val behind = (now - deadline) / step + 1
            deadline += behind * step
            skipped += behind - 1
        }
        if (deadline > stopAt) break

        while (true) {
            val t = renderer.now()
            if (t >= deadline) break
            if (deadline - t > slack) Thread.sleep(1) else Thread.onSpinWait()
        }
        deadline += step
        out.add(renderer.sampleClock())
    }
    return Run(out, skipped)
}

/** The probe's CSV, field for field, so the archived analyzer reads this run unchanged. */
private fun write(path: String, run: Run, r: WasapiRenderer, intervalMs: Double) {
    val sb = StringBuilder(run.samples.size * 72 + 512)
    sb.append("# device_rate=").append(r.format.sampleRate)
        .append(" clock_freq=").append(r.clockFrequency)
        .append(" qpc_freq=").append(r.qpcFrequency)
        .append(" buffer_frames=").append(r.bufferFrames)
        .append(" latency_hns=").append(r.streamLatencyHns)
        .append(" default_period_hns=").append(r.defaultPeriodHns)
        .append(" min_period_hns=").append(r.minimumPeriodHns)
        .append(" interval_ms=").append(intervalMs)
        .append(" skipped=").append(run.skipped)
        .append('\n')
    sb.append("i,qpc_before,qpc_after,position,qpc_position\n")
    run.samples.forEachIndexed { i, s ->
        sb.append(i).append(',')
            .append(s.qpcBefore).append(',')
            .append(s.qpcAfter).append(',')
            .append(s.position).append(',')
            .append(s.qpcPosition).append('\n')
    }
    Files.write(Path.of(path), sb.toString().toByteArray(StandardCharsets.US_ASCII))
    println("wrote $path")
}

/**
 * Says whether the run matched what was already known, and where each chirp actually landed.
 *
 * The landing times are **not** computed the way the schedule was. The schedule turned each
 * deadline into a frame using one instantaneous clock reading; this turns each frame back into a
 * tick using a least-squares fit over the whole run. Inverting the scheduler with the scheduler
 * would agree with itself no matter what it did - including if it had the position unit wrong.
 * Two different estimators of the same quantity can disagree, and how much they disagree is the
 * number worth printing.
 */
private fun report(
    samples: List<ClockSample>,
    frames: LongArray,
    deadlines: LongArray,
    qpf: Long,
    rate: Int,
    collected: Int,
    skipped: Long
) {
    if (samples.size < 32) {
        println("only ${samples.size} samples after warmup - nothing to fit")
        return
    }

    // qpcPosition against frames: the slope is ticks per frame, so qpf over it is the rate the
    // endpoint is really running at.
    val n = samples.size.toDouble()
    val mx = samples.sumOf { it.frames.toDouble() } / n
    val my = samples.sumOf { it.qpcPosition.toDouble() } / n
    var sxx = 0.0
    var sxy = 0.0
    for (s in samples) {
        val dx = s.frames - mx
        sxx += dx * dx
        sxy += dx * (s.qpcPosition - my)
    }
    val slope = sxy / sxx
    val intercept = my - slope * mx

    var sumSq = 0.0
    var worst = 0.0
    for (s in samples) {
        val r = (s.qpcPosition - (intercept + slope * s.frames)) / qpf * 1e6
        sumSq += r * r
        if (abs(r) > abs(worst)) worst = r
    }
    val sd = sqrt(sumSq / n)
    val fittedRate = qpf / slope
    val callUs = samples.map { (it.qpcAfter - it.qpcBefore) * 1e6 / qpf }.sorted()

    println()
    println("samples: $collected collected, ${samples.size} kept after warmup, $skipped deadlines skipped")
    println(
        "rate   : ${"%.3f".format(fittedRate)} Hz nominal $rate " +
            "(${"%+.2f".format((fittedRate / rate - 1.0) * 1e6)} ppm)"
    )
    println(
        "residual: sd ${"%.1f".format(sd)} us, worst ${"%+.1f".format(worst)} us, " +
            "GetPosition ${"%.1f".format(callUs[callUs.size / 2])} us median"
    )
    println("           expected from the probe's eight arms: 10-16 us silent, 13-15 us with a tone")
    println()

    for (i in frames.indices) {
        val landedAt = intercept + slope * frames[i]
        val offUs = (landedAt - deadlines[i]) / qpf * 1e6
        println(
            "chirp ${i + 1}: asked for frame ${frames[i]}, " +
                "fit puts it ${"%+.1f".format(offUs)} us from the tick it was asked for"
        )
    }
    println()
    println("A chirp's landing here is when the ENGINE consumed it, not when the air moved.")
    println("The distance between those two is this machine's output delay, and measuring it")
    println("takes a microphone in the room - it is step two of the Windows plan, not this.")
}
