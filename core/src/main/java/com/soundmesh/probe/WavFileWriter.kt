package com.soundmesh.probe

import java.io.File
import java.io.RandomAccessFile

/**
 * Writes a little-endian PCM16 WAV file and finalizes its sizes on [close].
 *
 * In core beside [WavFileReader] because both ends write these files now. The Windows client
 * records the same exchange a handset records and one analysis reads the two files together, so a
 * second writer over there would be a second header layout for the reader to survive - and a
 * recording read at the wrong offset still correlates and still reports a confident arrival.
 */
class WavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channelCount: Int
) : AutoCloseable {
    private val output: RandomAccessFile
    private var dataBytes = 0L
    private var closed = false

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
        val blockAlign = channelCount.toLong() * PCM16_BYTES_PER_SAMPLE
        require(blockAlign <= UInt16_MAX) { "channelCount is too large" }
        require(sampleRate.toLong() * blockAlign <= UInt32_MAX) { "byte rate is too large" }

        output = RandomAccessFile(file, "rw")
        output.setLength(0)
        writeHeader(riffSize = 0, dataSize = 0)
    }

    fun writePcm(bytes: ByteArray, length: Int) {
        check(!closed) { "WavFileWriter is closed" }
        require(length in 0..bytes.size) { "length must be between 0 and bytes.size" }
        require(length % PCM16_BYTES_PER_SAMPLE == 0) { "PCM16 data must have an even byte count" }
        require(dataBytes + length <= MAX_WAV_DATA_BYTES) { "WAV data exceeds RIFF's 4 GiB size field" }
        output.write(bytes, 0, length)
        dataBytes += length
    }

    override fun close() {
        if (closed) return
        try {
            output.fd.sync()
            output.seek(0)
            writeHeader(riffSize = WAV_HEADER_SIZE - 8L + dataBytes, dataSize = dataBytes)
            output.fd.sync()
        } finally {
            closed = true
            output.close()
        }
    }

    private fun writeHeader(riffSize: Long, dataSize: Long) {
        val blockAlign = channelCount * PCM16_BYTES_PER_SAMPLE
        val byteRate = sampleRate.toLong() * blockAlign
        writeAscii("RIFF")
        writeIntLe(riffSize)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeIntLe(16)
        writeShortLe(1)
        writeShortLe(channelCount)
        writeIntLe(sampleRate.toLong())
        writeIntLe(byteRate)
        writeShortLe(blockAlign)
        writeShortLe(16)
        writeAscii("data")
        writeIntLe(dataSize)
    }

    private fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
    private fun writeShortLe(value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }
    private fun writeIntLe(value: Long) {
        repeat(4) { shift -> output.write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }

    private companion object {
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val WAV_HEADER_SIZE = 44L
        const val UInt16_MAX = 0xffffL
        const val UInt32_MAX = 0xffff_ffffL
        const val MAX_WAV_DATA_BYTES = UInt32_MAX - (WAV_HEADER_SIZE - 8L)
    }
}
