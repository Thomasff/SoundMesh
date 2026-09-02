package com.soundmesh.core

/**
 * One block of PCM with the instant it must leave the speakers, expressed on the host clock.
 *
 * Equality compares the payload by content: a generated data class would compare the array by
 * identity, which silently breaks every queue and test that holds chunks.
 */
class AudioChunk(val sequence: Int, val playAtHostNanos: Long, val pcm: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is AudioChunk &&
            sequence == other.sequence &&
            playAtHostNanos == other.playAtHostNanos &&
            pcm.contentEquals(other.pcm)

    override fun hashCode(): Int =
        (sequence * 31 + playAtHostNanos.hashCode()) * 31 + pcm.contentHashCode()

    override fun toString(): String = "AudioChunk(seq=$sequence, playAt=$playAtHostNanos, bytes=${pcm.size})"
}
