package com.soundmesh.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * Stands in for the capture source while the sync path is being proved.
 *
 * Phase is derived from the absolute frame index rather than from a running counter, so any
 * device asking for any range gets bytes that join seamlessly onto its neighbours.
 */
class TonePcmSource(private val toneHz: Int = 480, private val channelCount: Int = 2) {
    fun fill(frameIndex: Long, frames: Int): ByteArray {
        val pcm = ByteArray(frames * channelCount * 2)
        var at = 0
        for (offset in 0 until frames) {
            val phase = 2.0 * PI * toneHz * ((frameIndex + offset).toDouble() / SAMPLE_RATE)
            val value = (sin(phase) * AMPLITUDE).toInt().toShort()
            repeat(channelCount) {
                pcm[at++] = (value.toInt() and 0xFF).toByte()
                pcm[at++] = (value.toInt() shr 8).toByte()
            }
        }
        return pcm
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val AMPLITUDE = 6000.0
    }
}
