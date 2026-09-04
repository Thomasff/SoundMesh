package com.soundmesh.probe

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavFileReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun write(samples: ShortArray, channelCount: Int = 1): File {
        val file = folder.newFile("recording.wav")
        val bytes = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            bytes[index * 2] = (samples[index].toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = (samples[index].toInt() shr 8).toByte()
        }
        WavFileWriter(file, 48000, channelCount).use { it.writePcm(bytes, bytes.size) }
        return file
    }

    /**
     * Read against write rather than against a hand-built header: the pair has to agree, and a
     * fixture would only prove the reader agrees with whatever the fixture's author believed.
     */
    @Test
    fun readsBackExactlyWhatTheWriterWrote() {
        val samples = shortArrayOf(0, 1, -1, 32767, -32768, 1234, -4321)

        assertArrayEquals(samples, WavFileReader.readMono(write(samples)))
    }

    @Test
    fun readsARecordingWithNoSamplesAsEmpty() {
        assertEquals(0, WavFileReader.readMono(write(shortArrayOf())).size)
    }

    @Test
    fun refusesAFileThatIsNotAWavAtAll() {
        val file = folder.newFile("notes.txt")
        file.writeText("this is not a recording of anything")

        val failure = runCatching { WavFileReader.readMono(file) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun refusesARecordingThatIsNotSingleChannel() {
        val failure = runCatching { WavFileReader.readMono(write(shortArrayOf(1, 2, 3, 4), channelCount = 2)) }
            .exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun reportsTheSampleRateItFound() {
        assertEquals(48000, WavFileReader.sampleRateOf(write(shortArrayOf(1, 2, 3))))
    }
}
