package com.soundmesh.core

/**
 * Wire format for one audio chunk. Big endian header, little endian PCM16 payload.
 * The magic number and protocol version live in the connection handshake rather than in
 * every chunk: TCP already delivers an ordered stream, so repeating them buys nothing.
 */
object ChunkCodec {
    const val LENGTH_PREFIX_BYTES = 4
    const val HEADER_BYTES = 16
    const val MAX_PAYLOAD_BYTES = 1 shl 20

    /**
     * The TCP port the chunk stream runs on.
     *
     * Here rather than on the screen that first used it, for the reason ClockPacket.DEFAULT_PORT
     * states: the two ends are two platforms now, and a second copy of this number fails in the
     * one way that costs a session to diagnose - both ends working perfectly and neither hearing
     * the other.
     */
    const val DEFAULT_PORT = 45124

    /**
     * Frames in one chunk, which is the other thing the two ends agree on without saying so.
     *
     * Nothing on the wire carries it: a sink sizes its own buffers from this and plays whatever
     * arrives, so a host that sent a different length would be heard rather than refused - at a
     * pace set by the sender while every counter on both sides stayed sane.
     */
    const val FRAMES_PER_CHUNK = 960

    fun encode(chunk: AudioChunk): ByteArray {
        val frame = ByteArray(HEADER_BYTES + chunk.pcm.size)
        writeInt(frame, 0, frame.size - LENGTH_PREFIX_BYTES)
        writeInt(frame, 4, chunk.sequence)
        writeLong(frame, 8, chunk.playAtHostNanos)
        chunk.pcm.copyInto(frame, HEADER_BYTES)
        return frame
    }

    fun decode(frame: ByteArray): AudioChunk {
        require(frame.size >= HEADER_BYTES) { "frame is too short to hold a header" }
        val advertised = readInt(frame, 0)
        require(advertised == frame.size - LENGTH_PREFIX_BYTES) { "frame length disagrees with its prefix" }
        return AudioChunk(
            sequence = readInt(frame, 4),
            playAtHostNanos = readLong(frame, 8),
            pcm = frame.copyOfRange(HEADER_BYTES, frame.size)
        )
    }

    /** Bytes still to read after the four byte prefix, validated before any buffer is allocated. */
    fun payloadLength(lengthPrefix: ByteArray): Int {
        require(lengthPrefix.size >= LENGTH_PREFIX_BYTES) { "length prefix is too short" }
        val length = readInt(lengthPrefix, 0)
        require(length in (HEADER_BYTES - LENGTH_PREFIX_BYTES)..MAX_PAYLOAD_BYTES) { "implausible frame length" }
        return length
    }

    private fun writeInt(target: ByteArray, at: Int, value: Int) {
        for (index in 0 until 4) target[at + index] = (value ushr (24 - index * 8)).toByte()
    }

    private fun writeLong(target: ByteArray, at: Int, value: Long) {
        for (index in 0 until 8) target[at + index] = (value ushr (56 - index * 8)).toByte()
    }

    private fun readInt(source: ByteArray, at: Int): Int {
        var value = 0
        for (index in 0 until 4) value = (value shl 8) or (source[at + index].toInt() and 0xFF)
        return value
    }

    private fun readLong(source: ByteArray, at: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (source[at + index].toLong() and 0xFF)
        return value
    }
}
