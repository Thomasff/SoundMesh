package com.soundmesh.probe

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a generated tone and records how this device reports its own playback position.
 * Thin framework glue: readings stay raw in ClockProbeLog so the PC decides what they mean.
 */
class ClockProbeRunner(
    private val runStore: RunStore,
    private val caseId: String,
    private val durationSeconds: Int
) {
    fun run() {
        val log = ClockProbeLog(SAMPLE_RATE, CHANNEL_COUNT)
        var failureCode: String? = null
        var track: AudioTrack? = null
        try {
            val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "AudioTrack reported no usable buffer size" }
            val chunk = buildToneChunk()
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimum, chunk.size * BYTES_PER_SAMPLE * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val timestamp = AudioTimestamp()
            var writtenFrames = 0L
            var nextPollNanos = 0L
            val deadline = SystemClock.elapsedRealtimeNanos() + durationSeconds * NANOS_PER_SECOND
            while (SystemClock.elapsedRealtimeNanos() < deadline) {
                // Blocking writes pace the loop; the track only accepts data as fast as it plays.
                val written = track.write(chunk, 0, chunk.size)
                if (written < 0) throw IllegalStateException("AudioTrack write returned $written")
                writtenFrames += written / CHANNEL_COUNT
                val now = SystemClock.elapsedRealtimeNanos()
                if (now >= nextPollNanos) {
                    nextPollNanos = now + POLL_INTERVAL_NANOS
                    if (track.getTimestamp(timestamp)) {
                        log.record(timestamp.nanoTime, timestamp.framePosition, writtenFrames)
                    } else {
                        log.recordUnavailable()
                    }
                }
            }
        } catch (error: Throwable) {
            failureCode = error.javaClass.simpleName.ifEmpty { "CLOCK_PROBE_EXCEPTION" }
            Log.e(LOG_TAG, "clock probe failed", error)
        } finally {
            track?.let { active ->
                runCatching { active.stop() }
                runCatching { active.flush() }
                runCatching { active.release() }
            }
            runStore.writeClockJson(caseId, log.toJson(failureCode))
        }
    }

    /** A whole number of tone periods per chunk, so looping the chunk cannot introduce a click. */
    private fun buildToneChunk(): ShortArray {
        val chunk = ShortArray(CHUNK_FRAMES * CHANNEL_COUNT)
        for (frame in 0 until CHUNK_FRAMES) {
            val value = (sin(2.0 * PI * TONE_HZ * frame / SAMPLE_RATE) * TONE_AMPLITUDE).toInt().toShort()
            chunk[frame * CHANNEL_COUNT] = value
            chunk[frame * CHANNEL_COUNT + 1] = value
        }
        return chunk
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 2
        const val MIN_DURATION_SECONDS = 30
        const val MAX_DURATION_SECONDS = 900

        private const val LOG_TAG = "SoundMeshProbe"
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_FRAMES = 4800
        private const val TONE_HZ = 480
        private const val TONE_AMPLITUDE = 1638.0
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val POLL_INTERVAL_NANOS = 200_000_000L
    }
}
