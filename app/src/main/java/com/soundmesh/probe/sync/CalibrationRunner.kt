package com.soundmesh.probe.sync

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.WavFileWriter
import java.io.File

/** Plays the calibration chirp at a scheduled instant, and records the room so the PC can measure. */
class CalibrationRunner(private val runStore: RunStore, private val caseId: String) {
    fun playChirpAt(hostNanos: Long, hostNanosNow: () -> Long) {
        val chirp = ChirpGenerator.generateMono()
        val minimum = AudioTrack.getMinBufferSize(ChirpGenerator.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(ChirpGenerator.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, chirp.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(chirp, 0, chirp.size)
        while (hostNanosNow() < hostNanos) Thread.sleep(1)
        track.play()
        Thread.sleep(ChirpGenerator.DURATION_MS.toLong() + 200)
        runCatching { track.stop() }; runCatching { track.release() }
    }

    fun record(seconds: Int) {
        val minimum = AudioRecord.getMinBufferSize(ChirpGenerator.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, ChirpGenerator.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 65536))
        val target = File(runStore.prepareRun(caseId), "calibration.wav")
        WavFileWriter(target, ChirpGenerator.SAMPLE_RATE, 1).use { writer ->
            record.startRecording()
            val buffer = ByteArray(8192)
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            while (System.nanoTime() < deadline) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) writer.writePcm(buffer, read)
            }
            record.stop()
        }
        record.release()
        // The reference must be the very bytes that were played, so it is saved rather than
        // reimplemented on the PC. Two generators drifting apart would corrupt every measurement.
        val chirp = ChirpGenerator.generateMono()
        val bytes = ByteArray(chirp.size * 2)
        for (index in chirp.indices) {
            bytes[index * 2] = (chirp[index].toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = (chirp[index].toInt() shr 8).toByte()
        }
        WavFileWriter(File(runStore.prepareRun(caseId), "chirp.wav"), ChirpGenerator.SAMPLE_RATE, 1)
            .use { it.writePcm(bytes, bytes.size) }
    }
}
