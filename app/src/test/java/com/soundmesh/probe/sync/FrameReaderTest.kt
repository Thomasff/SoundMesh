package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameReaderTest {
    private fun chunk(sequence: Int) = AudioChunk(sequence, sequence * 1000L, ByteArray(64) { sequence.toByte() })

    @Test
    fun readsBackToBackFramesFromOneStream() {
        val stream = ByteArrayInputStream(ChunkCodec.encode(chunk(1)) + ChunkCodec.encode(chunk(2)))
        val reader = FrameReader(stream)

        assertEquals(chunk(1), reader.readChunk())
        assertEquals(chunk(2), reader.readChunk())
    }

    @Test
    fun returnsNullAtACleanEndOfStream() {
        assertNull(FrameReader(ByteArrayInputStream(ByteArray(0))).readChunk())
    }

    @Test
    fun returnsNullWhenTheStreamStopsMidFrame() {
        val truncated = ChunkCodec.encode(chunk(1)).copyOfRange(0, 30)

        assertNull(FrameReader(ByteArrayInputStream(truncated)).readChunk())
    }
}
