package com.soundmesh.probe.sync

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackDecision
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.playbackErrorFrames

/**
 * Feeds the scheduler's decisions to an AudioTrack and keeps playback on the shared timeline.
 * Output latency lives here: the scheduler is asked what should be leaving the speakers by the
 * time the bytes written now actually get there.
 */
class SyncRenderer(
    private val scheduler: PlaybackScheduler,
    private val drift: DriftController,
    private val hostNanosNow: () -> Long
) {
    private val silence = ByteArray(FRAMES_PER_CHUNK * CHANNELS * 2)
    @Volatile private var adjustments = 0
    @Volatile private var lastFilteredError = 0
    @Volatile private var failureCode: String? = null

    fun run(untilHostNanos: Long) {
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        var track: AudioTrack? = null
        try {
            require(minimum > 0) { "AudioTrack reported no usable buffer size" }
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minimum, silence.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val timestamp = AudioTimestamp()
            var writtenFrames = 0L
            while (hostNanosNow() < untilHostNanos) {
                val depthNanos = outputDepthNanos(track, timestamp, writtenFrames)
                when (val decision = scheduler.poll(hostNanosNow() + depthNanos)) {
                    is PlaybackDecision.Play -> {
                        val payload = applyDrift(decision.chunk.pcm, track, timestamp, writtenFrames, decision.chunk.playAtHostNanos)
                        track.write(payload, 0, payload.size)
                        writtenFrames += payload.size / (CHANNELS * 2)
                    }
                    is PlaybackDecision.Silence -> {
                        track.write(silence, 0, silence.size)
                        writtenFrames += FRAMES_PER_CHUNK.toLong()
                    }
                    PlaybackDecision.Wait, PlaybackDecision.Idle -> Thread.sleep(5)
                }
            }
        } catch (error: Throwable) {
            failureCode = error.javaClass.simpleName.ifEmpty { "RENDER_EXCEPTION" }
        } finally {
            track?.let { active ->
                runCatching { active.stop() }; runCatching { active.flush() }; runCatching { active.release() }
            }
        }
    }

    /** Frames handed over but not yet heard, as nanoseconds of lead the scheduler must account for. */
    private fun outputDepthNanos(track: AudioTrack, timestamp: AudioTimestamp, writtenFrames: Long): Long {
        if (!track.getTimestamp(timestamp)) return DEFAULT_DEPTH_NANOS
        val pending = writtenFrames - timestamp.framePosition
        return pending.coerceAtLeast(0) * 1_000_000_000L / SAMPLE_RATE
    }

    /**
     * Compares where playback actually is against where the shared timeline says it should be,
     * then drops or duplicates a single frame. At the drift these devices show, one frame every
     * twenty seconds is enough, and twenty microseconds of it cannot be heard.
     */
    private fun applyDrift(pcm: ByteArray, track: AudioTrack, timestamp: AudioTimestamp, writtenFrames: Long, playAtHostNanos: Long): ByteArray {
        if (!track.getTimestamp(timestamp)) return pcm
        val pendingFrames = writtenFrames - timestamp.framePosition
        val errorFrames = playbackErrorFrames(hostNanosNow(), pendingFrames, playAtHostNanos, SAMPLE_RATE)
        val decision = drift.observe(errorFrames)
        lastFilteredError = decision.filteredErrorFrames
        if (decision.adjustFrames == 0) return pcm
        adjustments++
        val bytesPerFrame = CHANNELS * 2
        return if (decision.adjustFrames > 0) pcm + pcm.copyOfRange(pcm.size - bytesPerFrame, pcm.size)
        else pcm.copyOfRange(0, pcm.size - bytesPerFrame)
    }

    fun report(): String {
        val stats = scheduler.stats()
        return "{\"played\":${stats.played},\"droppedLate\":${stats.droppedLate}," +
            "\"droppedOverflow\":${stats.droppedOverflow},\"silenceFrames\":${stats.silenceFrames}," +
            "\"adjustments\":$adjustments,\"lastFilteredErrorFrames\":$lastFilteredError," +
            "\"failureCode\":${failureCode?.let { "\"$it\"" } ?: "null"}}"
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val FRAMES_PER_CHUNK = 960
        private const val DEFAULT_DEPTH_NANOS = 200_000_000L
    }
}
