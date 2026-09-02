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
    const val CHANNELS = 2
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

    /**
     * The same sweep as stereo PCM16 little endian, cut into [framesPerChunk] frame chunks with
     * the last one padded to full length with silence.
     *
     * The chirp is submitted to the ordinary PlaybackScheduler rather than played on a private
     * AudioTrack, so it has to arrive in the shape the scheduler and renderer already handle.
     * That is the whole point of the calibration: what it measures then covers scheduling, the
     * getTimestamp based output depth compensation and drift correction, which is the part of
     * the pipeline the number is supposed to judge.
     */
    fun generateStereoChunks(framesPerChunk: Int): List<ByteArray> {
        require(framesPerChunk > 0) { "framesPerChunk must be positive" }
        val mono = generateMono()
        val chunkCount = (mono.size + framesPerChunk - 1) / framesPerChunk
        return (0 until chunkCount).map { chunkIndex ->
            val pcm = ByteArray(framesPerChunk * CHANNELS * 2)
            var at = 0
            for (offset in 0 until framesPerChunk) {
                val index = chunkIndex * framesPerChunk + offset
                val sample = if (index < mono.size) mono[index].toInt() else 0
                repeat(CHANNELS) {
                    pcm[at++] = (sample and 0xFF).toByte()
                    pcm[at++] = (sample shr 8).toByte()
                }
            }
            pcm
        }
    }
}
