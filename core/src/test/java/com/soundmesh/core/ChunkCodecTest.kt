package com.soundmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkCodecTest {
    private val pcm = ByteArray(3840) { (it % 251).toByte() }

    @Test
    fun roundTripsEveryFieldOfAChunk() {
        val original = AudioChunk(sequence = 12345, playAtHostNanos = 9_876_543_210_123L, pcm = pcm)

        val decoded = ChunkCodec.decode(ChunkCodec.encode(original))

        assertEquals(12345, decoded.sequence)
        assertEquals(9_876_543_210_123L, decoded.playAtHostNanos)
        assertArrayEquals(pcm, decoded.pcm)
    }

    @Test
    fun keepsTheHeaderSmallEnoughForTheStatedOverhead() {
        val encoded = ChunkCodec.encode(AudioChunk(0, 0, pcm))

        assertEquals(16, ChunkCodec.HEADER_BYTES)
        assertEquals(3856, encoded.size)
        // 16 bytes of header on 3840 bytes of audio is 0.41 percent.
        assertEquals(true, ChunkCodec.HEADER_BYTES.toDouble() / encoded.size < 0.005)
    }

    @Test
    fun readsTheAdvertisedPayloadLengthFromTheFourBytePrefix() {
        val encoded = ChunkCodec.encode(AudioChunk(0, 0, pcm))

        assertEquals(3852, ChunkCodec.payloadLength(encoded.copyOfRange(0, 4)))
    }

    @Test
    fun rejectsAFrameTooShortToHoldAHeader() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ChunkCodec.decode(ByteArray(12))
        }

        assertEquals(true, error.message!!.contains("too short"))
    }

    @Test
    fun rejectsAFrameWhoseLengthPrefixDisagreesWithItsSize() {
        val encoded = ChunkCodec.encode(AudioChunk(0, 0, pcm))
        val truncated = encoded.copyOfRange(0, encoded.size - 100)

        assertThrows(IllegalArgumentException::class.java) { ChunkCodec.decode(truncated) }
    }

    @Test
    fun rejectsAnAdvertisedLengthNoSaneSenderWouldProduce() {
        val hostile = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F)

        assertThrows(IllegalArgumentException::class.java) { ChunkCodec.payloadLength(hostile) }
    }

    @Test
    fun comparesChunksByContentSoTestsAndQueuesBehave() {
        val left = AudioChunk(1, 2, byteArrayOf(1, 2, 3))
        val right = AudioChunk(1, 2, byteArrayOf(1, 2, 3))

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }
}
