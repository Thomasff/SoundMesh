package com.soundmesh.desktop

import com.soundmesh.core.ChirpGenerator

/**
 * The whole of the Windows client so far: proof that it can be built at all.
 *
 * Three questions in one run, each with an answer that is already known, so this either matches
 * or it does not:
 *
 *   - does core come over untouched?          5760 frames, and the chirp's own numbers
 *   - does Kotlin reach the foreign API?      QPC frequency 10000000
 *   - does that reach WASAPI?                 48000 Hz 2 ch 32 bit float
 *
 * Nothing here opens an audio stream, so nothing here makes a sound.
 */
fun main() {
    val chirp = ChirpGenerator.generateMono()
    val peak = chirp.maxOf { if (it < 0) -it.toInt() else it.toInt() }
    var energy = 0.0
    for (s in chirp) energy += s.toDouble() * s.toDouble()

    println("core   : ChirpGenerator ${ChirpGenerator.SAMPLE_RATE} Hz, ${ChirpGenerator.DURATION_MS} ms")
    println("core   : ${chirp.size} frames, peak $peak, rms ${"%.1f".format(Math.sqrt(energy / chirp.size))}")
    println("ffm    : QPC frequency ${WindowsAudio.qpcFrequency()} Hz")
    println("wasapi : ${WindowsAudio.defaultRenderMixFormat()}")
}
