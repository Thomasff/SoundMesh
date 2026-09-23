package com.soundmesh.desktop

import java.io.ByteArrayInputStream
import java.io.File
import java.net.DatagramSocket
import java.net.ServerSocket
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * A quiet ramp at the stream's own format, so nothing about it needs converting.
 *
 * Thirty seconds by default: the host plays a file once and then stops the room, and most tests
 * need a room that is still playing while they look at it.
 */
internal fun writeTestWav(file: File, seconds: Double = 30.0): File {
    val frames = (48_000 * seconds).toInt()
    val pcm = ByteArray(frames * 2 * 2)
    for (sample in 0 until frames * 2) {
        val value = (sample % 200 - 100)
        pcm[sample * 2] = (value and 0xFF).toByte()
        pcm[sample * 2 + 1] = (value shr 8).toByte()
    }
    val format = AudioFormat(48_000f, 16, 2, true, false)
    AudioInputStream(ByteArrayInputStream(pcm), format, frames.toLong())
        .use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, file) }
    return file
}

/** Speakers that are a straight line through the origin, and say whether they were closed. */
internal class FakeSpeakers {
    val output = FakeOutput()
    @Volatile var closed = false
    fun open(): Speakers = Speakers(output, "fake") { closed = true }
}

internal fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }
internal fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

internal fun eventually(millis: Long = 10_000L, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + millis * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(20)
    }
    return condition()
}
