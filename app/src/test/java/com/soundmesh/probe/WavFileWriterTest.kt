package com.soundmesh.probe

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WavFileWriterTest {
    @Test
    fun writesPcmWavHeaderAndPatchesSizesOnClose() {
        withTemporaryFile { file ->
            WavFileWriter(file, sampleRate = 48_000, channelCount = 2).use { writer ->
                writer.writePcm(byteArrayOf(1, 2, 3, 4), 4)
            }

            val bytes = Files.readAllBytes(file.toPath())
            assertEquals(48L, bytes.size.toLong())
            assertEquals("RIFF", String(bytes, 0, 4, StandardCharsets.US_ASCII))
            assertEquals(40, readLittleEndianInt(bytes, 4))
            assertEquals("WAVE", String(bytes, 8, 4, StandardCharsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 12, 4, StandardCharsets.US_ASCII))
            assertEquals(16, readLittleEndianInt(bytes, 16))
            assertEquals(1, readLittleEndianShort(bytes, 20))
            assertEquals(2, readLittleEndianShort(bytes, 22))
            assertEquals(48_000, readLittleEndianInt(bytes, 24))
            assertEquals(192_000, readLittleEndianInt(bytes, 28))
            assertEquals(4, readLittleEndianShort(bytes, 32))
            assertEquals(16, readLittleEndianShort(bytes, 34))
            assertEquals("data", String(bytes, 36, 4, StandardCharsets.US_ASCII))
            assertEquals(4, readLittleEndianInt(bytes, 40))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.copyOfRange(44, 48))
        }
    }

    @Test
    fun rejectsOddPcm16ByteCount() {
        withTemporaryFile { file ->
            WavFileWriter(file, 48_000, 1).use { writer ->
                assertThrows(IllegalArgumentException::class.java) {
                    writer.writePcm(byteArrayOf(1, 2, 3), 3)
                }
            }
        }
    }

    @Test
    fun rejectsWritesAfterClose() {
        withTemporaryFile { file ->
            val writer = WavFileWriter(file, 48_000, 1)
            writer.close()

            assertThrows(IllegalStateException::class.java) {
                writer.writePcm(byteArrayOf(0, 0), 2)
            }
        }
    }

    private fun withTemporaryFile(block: (File) -> Unit) {
        val path = Files.createTempFile("soundmesh-wav-", ".wav")
        try {
            block(path.toFile())
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun readLittleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}
