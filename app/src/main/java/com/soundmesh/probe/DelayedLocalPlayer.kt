package com.soundmesh.probe

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays buffered capture back on this device after the configured delay.
 * Thin framework glue: all buffering decisions live in DelayedPcmBuffer.
 */
class DelayedLocalPlayer(
    private val buffer: DelayedPcmBuffer,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val usage: PlaybackUsage
) {
    @Volatile private var startedPlayback = false
    @Volatile private var failureCode: String? = null
    @Volatile private var underrunCount = 0
    @Volatile private var routedDeviceType: Int? = null

    fun run() {
        val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        var track: AudioTrack? = null
        try {
            require(minimum > 0) { "AudioTrack reported no usable buffer size" }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage.androidUsage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minimum * BUFFER_COUNT)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val chunk = ByteArray(minimum)
            while (true) {
                val read = buffer.read(chunk, READ_TIMEOUT_NANOS)
                if (read == DelayedPcmBuffer.END_OF_STREAM) break
                if (read <= 0) continue
                if (!startedPlayback) {
                    track.play()
                    startedPlayback = true
                }
                track.write(chunk, 0, read)
            }
        } catch (error: Throwable) {
            failureCode = error.javaClass.simpleName.ifEmpty { "PLAYBACK_EXCEPTION" }
            Log.e(LOG_TAG, "delayed playback failed", error)
        } finally {
            // Snapshot before release; the track rejects queries once released.
            track?.let { active ->
                runCatching { underrunCount = active.underrunCount }
                runCatching { routedDeviceType = active.routedDevice?.type }
                runCatching { if (startedPlayback) active.stop() }
                runCatching { active.flush() }
                runCatching { active.release() }
            }
        }
    }

    fun report(): DelayedPlaybackReport = DelayedPlaybackReport(
        usage = usage.name,
        startedPlayback = startedPlayback,
        underrunCount = underrunCount,
        routedDeviceType = routedDeviceType,
        failureCode = failureCode,
        buffer = buffer.stats()
    )

    private companion object {
        const val LOG_TAG = "SoundMeshProbe"
        const val BUFFER_COUNT = 2
        const val READ_TIMEOUT_NANOS = 200_000_000L
    }
}
