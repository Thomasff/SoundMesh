package com.soundmesh.probe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** AudioRecord adapter limited to media playback from the selected package UID. */
class AndroidPlaybackReader(
    context: Context,
    private val mediaProjection: MediaProjection,
    expectedPackage: String,
    private val onProjectionStopped: () -> Unit
) : PcmReader {
    private val expectedPackageUid = context.packageManager.getApplicationInfo(expectedPackage, 0).uid
    private val stopped = AtomicBoolean(false)
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopped.set(true)
            onProjectionStopped()
        }
    }
    private var callbackRegistered = false
    private var record: AudioRecord? = null
    override var sampleRate: Int = SAMPLE_RATE
        private set
    override var channelCount: Int = STEREO_CHANNEL_COUNT
        private set

    override fun start() {
        check(record == null) { "AudioPlaybackReader already started" }
        mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
        callbackRegistered = true
        record = createRecord(STEREO_CHANNEL_COUNT) ?: createRecord(MONO_CHANNEL_COUNT)
            ?: throw IllegalStateException("Unable to initialize playback capture AudioRecord")
        record!!.startRecording()
    }

    override fun read(target: ByteArray): Int {
        if (stopped.get()) return -1
        return record?.read(target, 0, target.size, AudioRecord.READ_BLOCKING) ?: -1
    }

    override fun stop() {
        record?.let { audioRecord ->
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
        }
    }

    override fun close() {
        record?.release()
        record = null
        if (callbackRegistered) {
            mediaProjection.unregisterCallback(callback)
            callbackRegistered = false
        }
    }

    private fun createRecord(channels: Int): AudioRecord? = try {
        val channelMask = if (channels == STEREO_CHANNEL_COUNT) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelMask)
            .build()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUid(expectedPackageUid)
            .build()
        val candidate = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimum * BUFFER_COUNT)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            candidate.release()
            null
        } else {
            channelCount = channels
            candidate
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val STEREO_CHANNEL_COUNT = 2
        private const val MONO_CHANNEL_COUNT = 1
        private const val BUFFER_COUNT = 4
    }
}
