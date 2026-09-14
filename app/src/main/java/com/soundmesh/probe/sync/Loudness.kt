package com.soundmesh.probe.sync

/**
 * How loud a slice of 16-bit PCM is, from 0 (silence) to 1 (full scale).
 *
 * RMS rather than peak, because this drives a light somebody is looking at: a peak meter spends
 * most of a song pinned, and the thing worth seeing from across a room is whether this phone is
 * producing anything at all.
 *
 * The odd trailing byte of a truncated final chunk is dropped rather than read as half a sample.
 */
internal fun loudnessOf(pcm: ByteArray, offset: Int, length: Int): Float {
    val samples = length / 2
    if (samples <= 0) return 0f
    var sum = 0.0
    for (i in 0 until samples) {
        val at = offset + i * 2
        val value = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort().toInt()
        sum += value.toDouble() * value.toDouble()
    }
    return (Math.sqrt(sum / samples) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
}
