package com.soundmesh.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The calibration signal. A linear sweep correlates as one sharp peak, which is what lets the
 * analysis pin an arrival to a sample; a plain tone would correlate as a ridge with no single
 * answer. The Hann window keeps the ends from splattering across the spectrum.
 */
object ChirpGenerator {
    const val SAMPLE_RATE = 48000
    const val DURATION_MS = 120
    const val FRAME_COUNT = SAMPLE_RATE * DURATION_MS / 1000
    private const val START_HZ = 1000.0
    private const val END_HZ = 8000.0
    private const val AMPLITUDE = 12000.0

    fun generateMono(): ShortArray {
        val chirp = ShortArray(FRAME_COUNT)
        val duration = DURATION_MS / 1000.0
        val sweepRate = (END_HZ - START_HZ) / duration
        for (index in 0 until FRAME_COUNT) {
            val time = index.toDouble() / SAMPLE_RATE
            val phase = 2.0 * PI * (START_HZ * time + sweepRate * time * time / 2.0)
            val window = 0.5 * (1.0 - cos(2.0 * PI * index / (FRAME_COUNT - 1)))
            chirp[index] = (sin(phase) * window * AMPLITUDE).toInt().toShort()
        }
        return chirp
    }
}
