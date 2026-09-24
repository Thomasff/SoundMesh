package com.soundmesh.desktop

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Does muting a program in the volume mixer also silence what a process-loopback capture of it
 * hears? And turning it down - does the capture come back quieter by the same factor, and can
 * [AppCapture.gain] undo that without losing anything?
 *
 * The answer decides how a desktop host capturing an app keeps that app's own copy off the
 * speakers. It is asked of a child process this starts itself - PowerShell playing a quiet tone -
 * so no row but that child's is ever touched, and the endpoint's level is not touched at all.
 *
 * Each phase reports the tone's level against what was played, and how far below the tone
 * everything that is not the tone sits. The tone is 16-bit, so that second number cannot beat
 * what 16 bits give a tone this quiet.
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.AppCaptureProbeKt
 */
fun main() {
    val wav = File.createTempFile("soundmesh-tone", ".wav")
    wav.deleteOnExit()
    writeTone(wav, seconds = 25, amplitude = TONE_AMPLITUDE)

    val child = ProcessBuilder(
        "powershell", "-NoProfile", "-Command",
        "(New-Object Media.SoundPlayer '${wav.absolutePath}').PlaySync()"
    ).redirectErrorStream(true).start()
    val pid = child.pid()
    println("child  : pid $pid, tone $TONE_HZ Hz at amplitude $TONE_AMPLITUDE")

    try {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (AudioSessions.list().none { it.pid == pid && it.playing }) {
            check(System.nanoTime() < deadline) { "the child never showed up in the mixer" }
            Thread.sleep(100)
        }
        val originalVolume = AudioSessions.volume(pid) ?: 1f
        val originalMute = AudioSessions.isMuted(pid) ?: false
        println("mixer  : child's row at volume $originalVolume, muted $originalMute")

        val phases = listOf(
            Phase("normal", 3.0, level = originalVolume, mute = false),
            Phase("muted", 3.0, level = originalVolume, mute = true),
            Phase("unmuted", 2.0, level = originalVolume, mute = false),
            Phase("1% x100", 3.0, level = 0.01f, mute = false),
            Phase("0.1% x1000", 3.0, level = 0.001f, mute = false),
            Phase("back", 2.0, level = originalVolume, mute = false)
        )
        val stats = Array(phases.size) { ToneFit() }
        val frames = LongArray(phases.size)
        val current = java.util.concurrent.atomic.AtomicInteger(-1)
        val settledAt = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)

        AppCapture(CaptureTarget(pid)) { bytes, length ->
            val phase = current.get()
            if (phase >= 0) {
                frames[phase] += (length / AppCapture.BYTES_PER_FRAME).toLong()
                if (System.nanoTime() >= settledAt.get()) {
                    var i = 0
                    while (i < length) {
                        // Left channel only; both carry the same tone.
                        stats[phase].add(((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toDouble())
                        i += AppCapture.BYTES_PER_FRAME
                    }
                }
            }
        }.use { capture ->
            capture.start()
            try {
                for ((index, phase) in phases.withIndex()) {
                    AudioSessions.setMute(pid, phase.mute)
                    AudioSessions.setVolume(pid, phase.level)
                    capture.gain = originalVolume / phase.level
                    settledAt.set(System.nanoTime() + SETTLE_NANOS)
                    current.set(index)
                    Thread.sleep((phase.seconds * 1000).toLong())
                }
                current.set(-1)
            } finally {
                capture.stop()
                AudioSessions.setMute(pid, originalMute)
                AudioSessions.setVolume(pid, originalVolume)
            }
            println("capture: ${capture.framesDelivered} frames, ${capture.silentPackets} silent packets")
            println("mixer  : child's row put back to volume ${AudioSessions.volume(pid)}, muted ${AudioSessions.isMuted(pid)}")
        }

        println()
        println("phase         frames   tone dB   rest dB below tone")
        for ((index, phase) in phases.withIndex()) {
            val s = stats[index]
            println(
                "%-12s %7d   %7s   %s".format(
                    phase.name, frames[index], s.toneDb(TONE_AMPLITUDE), s.restBelowToneDb()
                )
            )
        }
    } finally {
        child.destroyForcibly()
    }
}

private class Phase(val name: String, val seconds: Double, val level: Float, val mute: Boolean)

/** Least squares on a sine and a cosine at the tone's frequency; what is left over is the rest. */
private class ToneFit {
    private var n = 0L
    private var ss = 0.0
    private var sc = 0.0
    private var xx = 0.0

    fun add(x: Double) {
        val w = 2 * PI * TONE_HZ * n / 48_000.0
        ss += x * sin(w)
        sc += x * cos(w)
        xx += x * x
        n++
    }

    private fun tonePower(): Double {
        val a = 2 * ss / n
        val b = 2 * sc / n
        return (a * a + b * b) / 2
    }

    fun toneDb(amplitude: Double): String {
        if (n == 0L) return "-"
        val p = tonePower()
        return if (p > 0) "%+.2f".format(10 * log10(p / (amplitude * amplitude / 2))) else "-inf"
    }

    fun restBelowToneDb(): String {
        if (n == 0L) return "-"
        val tone = tonePower()
        val rest = xx / n - tone
        return if (tone > 0 && rest > 0) "%.1f".format(10 * log10(tone / rest)) else "-"
    }
}

private const val TONE_HZ = 1000
private const val TONE_AMPLITUDE = 1000.0
private const val SETTLE_NANOS = 300_000_000L

private fun writeTone(file: File, seconds: Int, amplitude: Double) {
    val rate = 48_000
    val frames = rate * seconds
    val dataBytes = frames * 4
    RandomAccessFile(file, "rw").use { out ->
        out.setLength(0)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate)
            .putInt(rate * 4).putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(dataBytes)
        out.write(header.array())
        val body = java.nio.ByteBuffer.allocate(dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = Math.round(amplitude * sin(2 * PI * TONE_HZ * i / rate)).toShort()
            body.putShort(s).putShort(s)
        }
        out.write(body.array())
    }
}
