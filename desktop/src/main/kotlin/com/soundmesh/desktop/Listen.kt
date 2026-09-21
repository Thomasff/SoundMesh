package com.soundmesh.desktop

import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import kotlin.math.abs

/**
 * Says what this machine's microphone actually hears, and plays nothing at all.
 *
 * Written because the question "can this machine hear the room" had no instrument. Every capture
 * run so far played a chirp in the same breath, so a quiet recording could not be told apart from
 * a recording of a machine that cannot hear itself - and on this machine those are two different
 * faults with two different consequences. Silence here means the capture path is dead. A quiet
 * recording that goes loud when somebody claps means the path is fine and something is removing
 * this machine's own output, which is what an echo canceller on a communications endpoint is for.
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.ListenKt [seconds] [loopback]
 *
 * The level profile is printed per tenth of a second rather than as one number for the run: a peak
 * over several seconds says nothing about whether the loud part was the clap or the fan, and where
 * the loud part sits is the whole of what distinguishes them.
 */
fun main(args: Array<String>) {
    val seconds = args.getOrNull(0)?.toDouble() ?: 6.0
    val loopback = args.getOrNull(1) == "loopback"

    WasapiCapture(loopback = loopback).use { capture ->
        println("capture: ${capture.deviceName ?: "(unnamed)"} - ${capture.format}")
        println("volume : ${capture.volume?.let { "${"%.0f".format(it.first * 100)}%${if (it.second) ", MUTED" else ""}" } ?: "(unknown)"}")
        println("source : ${if (loopback) "the render endpoint's loopback - what the engine mixed" else "the default recording endpoint - the room"}")
        println()
        println("listening for $seconds s, playing nothing ...")
        capture.start()
        Thread.sleep((seconds * 1000).toLong())
        capture.stop()

        val recording = capture.take()
        val mono = recording.mono
        val rate = recording.format.sampleRate
        val silent = recording.packets.count { it.flags and Wasapi.BUFFERFLAGS_SILENT != 0 }
        println(
            "heard  : ${mono.size} samples (${"%.2f".format(mono.size.toDouble() / rate)} s) in " +
                "${recording.packets.size} packets, $silent flagged silent"
        )

        val step = rate / 10
        val bars = (0 until mono.size / step).map { tenth ->
            var peak = 0
            for (index in tenth * step until (tenth + 1) * step) {
                val level = abs(mono[index].toInt())
                if (level > peak) peak = level
            }
            peak
        }
        val loudest = bars.maxOrNull() ?: 0
        println("peak   : $loudest of ${Short.MAX_VALUE}")
        // Per channel as well as after the averaging, because the averaging is a decision this
        // program is here to check: two channels of a microphone array that oppose each other
        // cancel into a mono track of almost nothing, and the mono track alone cannot tell that
        // apart from a microphone that heard nothing.
        println(
            "channels: " + recording.channelPeaks.mapIndexed { channel, peak ->
                "$channel at ${"%.0f".format(peak)}"
            }.joinToString(", ")
        )
        println()
        // A bar per tenth of a second, scaled to this run's own loudest tenth. Scaled to itself on
        // purpose: the question is where in the run the sound was, and a scale fixed to full range
        // would draw a flat empty line for every one of the quiet recordings this exists to read.
        bars.forEachIndexed { tenth, peak ->
            val width = if (loudest == 0) 0 else peak * 50 / loudest
            println(
                "${"%5.1f".format(tenth / 10.0)}s ${"%6d".format(peak)} " + "#".repeat(width)
            )
        }

        // Whether the project's own chirp is anywhere in there, searched over the whole recording.
        // A whole-file search is right here and nowhere else: this recording is not supposed to
        // hold two of them, so there is no repeated event for a maximum to pick the wrong one of.
        val arrival = ChirpCorrelator.findArrival(mono, ChirpGenerator.generateMono(), 0, mono.size)
        println()
        println(
            if (arrival == null) "chirp  : the recording is too short to search"
            else "chirp  : best match at ${"%.3f".format(arrival.index.toDouble() / rate)} s, " +
                "ratio ${"%.1f".format(arrival.ratio)} " +
                if (arrival.ratio < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO) "(below the trustworthy ratio - nothing found)"
                else "(a real arrival)"
        )
    }
}
