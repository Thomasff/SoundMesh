package com.soundmesh.probe

import java.io.File

/**
 * Reads back a little-endian PCM16 WAV written by the probe's WavFileWriter.
 *
 * The counterpart to the writer, and it exists because the measurement moved onto the device: the
 * calibration recording used to be pulled to a PC and correlated there, so nothing on the handset
 * ever had to open one. It sits in core rather than beside the writer because both ends read these
 * files now: the Windows client pulls a handset's recording and correlates it with the same code,
 * and an analysis that opened the bytes its own way would not be comparable with any archived run.
 *
 * The header is parsed rather than skipped. Every file this reads is one this app wrote, so a
 * fixed 44 byte skip would work today and would fail silently the day it does not - a recording
 * read at the wrong offset still correlates, still reports a confident arrival, and moves every
 * number by however many bytes were missed.
 */
object WavFileReader {
    /** All samples of a single channel recording. */
    fun readMono(file: File): ShortArray {
        val bytes = file.readBytes()
        val format = parse(bytes)
        require(format.channelCount == 1) { "expected a mono recording, found ${format.channelCount} channels" }
        val out = ShortArray(format.dataBytes / 2)
        for (index in out.indices) {
            val at = format.dataAt + index * 2
            out[index] = ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    fun sampleRateOf(file: File): Int = parse(file.readBytes()).sampleRate

    private class Format(val sampleRate: Int, val channelCount: Int, val dataAt: Int, val dataBytes: Int)

    /**
     * Walks the RIFF chunks to the data, rather than assuming where it starts.
     *
     * Chunk sizes are unsigned 32-bit and a truncated file will claim more data than it holds - an
     * interrupted recording is exactly the case worth surviving - so the declared size is clamped
     * to what is actually there instead of trusted.
     */
    private fun parse(bytes: ByteArray): Format {
        require(bytes.size >= 12) { "not a WAV file: too short to hold a RIFF header" }
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var sampleRate = 0
        var channelCount = 0
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = ascii(bytes, at, 4)
            val declared = intLe(bytes, at + 4).toLong() and 0xFFFF_FFFFL
            val body = at + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                require(shortLe(bytes, body) == 1) { "only uncompressed PCM is supported" }
                channelCount = shortLe(bytes, body + 2)
                sampleRate = intLe(bytes, body + 4)
                require(shortLe(bytes, body + 14) == 16) { "only 16-bit samples are supported" }
            }
            if (id == "data") {
                require(sampleRate > 0) { "not a WAV file: data chunk arrived before fmt" }
                val available = bytes.size - body
                val size = minOf(declared, available.toLong()).toInt()
                return Format(sampleRate, channelCount, body, size - size % 2)
            }
            // Chunks are word aligned, and an odd size carries a pad byte that is not counted.
            at = body + declared.toInt() + (declared.toInt() and 1)
        }
        throw IllegalArgumentException("not a WAV file: no data chunk")
    }

    private fun ascii(bytes: ByteArray, at: Int, length: Int) = String(bytes, at, length, Charsets.US_ASCII)
    private fun shortLe(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    private fun intLe(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
