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
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** AudioRecord adapter over media playback: one package's, or everything except this app's own. */
class AndroidPlaybackReader(
    context: Context,
    private val mediaProjection: MediaProjection,
    /**
     * The one app to capture, or null for whatever is playing.
     *
     * A named package is what every capture case on record used, and it is what a measurement
     * wants: a run that captured a notification chime as well as the track is not the run the
     * report claims. The product cannot name one - a person picks their music app, not ours - so
     * null instead captures every media output except this app's own, which is what keeps the
     * replay from being fed back into itself.
     */
    expectedPackage: String?,
    private val onProjectionStopped: () -> Unit
) : PcmReader {
    private val expectedPackageUid = expectedPackage?.let { context.packageManager.getApplicationInfo(it, 0).uid }
    private val ownUid = Process.myUid()
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

    private fun createRecord(channels: Int): AudioRecord? {
        return try {
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
            // Matching and excluding UIDs cannot be combined, so it is one or the other.
            .apply { if (expectedPackageUid != null) addMatchingUid(expectedPackageUid) else excludeUid(ownUid) }
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
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val STEREO_CHANNEL_COUNT = 2
        private const val MONO_CHANNEL_COUNT = 1
        private const val BUFFER_COUNT = 4
    }
}
