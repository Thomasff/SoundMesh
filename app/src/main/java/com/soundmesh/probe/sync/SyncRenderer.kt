package com.soundmesh.probe.sync

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.soundmesh.core.DriftController
import com.soundmesh.core.PhaseState
import com.soundmesh.core.PlaybackDecision
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.RendererPhase
import com.soundmesh.core.nextPhaseState
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
    @Volatile private var untilHostNanos = Long.MIN_VALUE
    @Volatile private var adjustments = 0
    @Volatile private var driftSamples = 0
    @Volatile private var lastFilteredError = 0
    @Volatile private var failureCode: String? = null
    @Volatile private var phaseState = PhaseState.INITIAL
    @Volatile private var acquisitionStartHostNanos = UNDEFINED
    @Volatile private var acquisitionConvergedAtHostNanos = UNDEFINED
    /** Frames the drift controller asked for, waiting for the next chunk to carry them. */
    private var pendingAdjustFrames = 0

    /**
     * Sets, or later moves, the instant [run] stops at. It has to be movable: the calibration
     * chirp is scheduled off the last audio chunk's own timestamp, so how long the renderer must
     * stay alive is only known once the audio segment is over. Must be called before [run].
     */
    fun endAt(hostNanos: Long) {
        untilHostNanos = hostNanos
    }

    /** ACQUIRING (fast, per-chunk correction) or TRACKING (the spec's 1Hz cadence). Thread-safe. */
    fun phase(): RendererPhase = phaseState.phase

    /** The most recent median-filtered drift error, in frames. Thread-safe. */
    fun filteredErrorFrames(): Int = lastFilteredError

    /**
     * How long ACQUIRING has taken: from the first drift sample to the instant it converged into
     * TRACKING, or up to now if it has not converged yet. Null before the first sample - playback
     * has not pinned the shared timeline yet, so there is nothing to measure. Thread-safe; reads
     * [hostNanosNow] so it can be called mid-run, before the phase has settled.
     */
    fun acquisitionDurationNanos(): Long? {
        val start = acquisitionStartHostNanos
        if (start == UNDEFINED) return null
        val end = if (acquisitionConvergedAtHostNanos != UNDEFINED) acquisitionConvergedAtHostNanos else hostNanosNow()
        return end - start
    }

    fun run() {
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
            // Host instant at which the next frame to be written must be heard. Undefined until
            // the first real chunk pins the write stream to the shared timeline; silence then
            // carries it forward a chunk at a time, exactly as the write stream advances.
            var timelineNextHostNanos = UNDEFINED
            var nextDriftCheckHostNanos = UNDEFINED
            while (hostNanosNow() < untilHostNanos) {
                if (timelineNextHostNanos != UNDEFINED && hostNanosNow() >= nextDriftCheckHostNanos) {
                    sampleDrift(track, timestamp, writtenFrames, timelineNextHostNanos)
                    nextDriftCheckHostNanos = hostNanosNow() + driftIntervalNanos()
                }
                val depthNanos = outputDepthNanos(track, timestamp, writtenFrames)
                when (val decision = scheduler.poll(hostNanosNow() + depthNanos)) {
                    is PlaybackDecision.Play -> {
                        if (timelineNextHostNanos == UNDEFINED) {
                            // First chunk pins the timeline: acquisition starts now, sampling
                            // immediately rather than waiting a full DRIFT_INTERVAL_NANOS.
                            acquisitionStartHostNanos = hostNanosNow()
                            nextDriftCheckHostNanos = hostNanosNow()
                        }
                        val payload = applyPendingAdjust(decision.chunk.pcm)
                        track.write(payload, 0, payload.size)
                        writtenFrames += payload.size / (CHANNELS * 2)
                        // A dropped or duplicated frame deliberately does not move the timeline:
                        // shifting the frame-to-instant mapping by one frame is the correction.
                        timelineNextHostNanos = decision.chunk.playAtHostNanos + CHUNK_NANOS
                    }
                    is PlaybackDecision.Silence -> {
                        track.write(silence, 0, silence.size)
                        writtenFrames += FRAMES_PER_CHUNK.toLong()
                        if (timelineNextHostNanos != UNDEFINED) timelineNextHostNanos += CHUNK_NANOS
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
     * and asks the controller for a single frame of correction.
     *
     * Sampled on its own getTimestamp reading, before poll is consulted. Read straight after poll
     * released a chunk it would be measured against the very comparison that released it - poll
     * only lets a chunk go once now + depth has reached its instant - so the error could never
     * come out above zero, and the controller would drop a frame on nearly every chunk.
     *
     * The cadence this runs at depends on [phaseState] (see [driftIntervalNanos]): about every
     * chunk while ACQUIRING, working off PlaybackScheduler.poll's own up-to-one-chunk release
     * overshoot before it can bias the calibration chirp; once a few samples in a row land inside
     * DriftController's deadband, [nextPhaseState] moves this to TRACKING and it drops
     * to the 1Hz cadence sections 9.1 and 12 of the design call for - far faster than the ~21
     * seconds these devices take to drift a single frame at steady state.
     */
    private fun sampleDrift(track: AudioTrack, timestamp: AudioTimestamp, writtenFrames: Long, timelineNextHostNanos: Long) {
        if (!track.getTimestamp(timestamp)) return
        val pendingFrames = writtenFrames - timestamp.framePosition
        val errorFrames = playbackErrorFrames(hostNanosNow(), pendingFrames, timelineNextHostNanos, SAMPLE_RATE)
        val decision = drift.observe(errorFrames)
        lastFilteredError = decision.filteredErrorFrames
        driftSamples++
        pendingAdjustFrames = decision.adjustFrames
        val wasAcquiring = phaseState.phase == RendererPhase.ACQUIRING
        phaseState = nextPhaseState(phaseState, inDeadband = decision.adjustFrames == 0)
        if (wasAcquiring && phaseState.phase == RendererPhase.TRACKING) {
            acquisitionConvergedAtHostNanos = hostNanosNow()
        }
    }

    /** Chunk-period cadence (~50Hz) while ACQUIRING; the spec's 1Hz cadence once TRACKING. */
    private fun driftIntervalNanos(): Long =
        if (phaseState.phase == RendererPhase.ACQUIRING) CHUNK_NANOS else DRIFT_INTERVAL_NANOS

    /**
     * Carries the correction the last drift sample asked for into the chunk about to be written.
     * At the drift these devices show, one frame every twenty seconds is enough, and twenty
     * microseconds of it cannot be heard.
     */
    private fun applyPendingAdjust(pcm: ByteArray): ByteArray {
        val adjust = pendingAdjustFrames
        if (adjust == 0) return pcm
        pendingAdjustFrames = 0
        adjustments++
        val bytesPerFrame = CHANNELS * 2
        return if (adjust > 0) pcm + pcm.copyOfRange(pcm.size - bytesPerFrame, pcm.size)
        else pcm.copyOfRange(0, pcm.size - bytesPerFrame)
    }

    /**
     * The exception the render loop died of, if any. Exposed on its own (not just inside
     * [report]'s JSON) so the caller can surface it at the top level of sync.json: the renderer is
     * the only source of the calibration chirp now, so a renderer that threw must not read back as
     * a clean run with no chirp in the recording.
     */
    fun currentFailureCode(): String? = failureCode

    /**
     * [streamingSilenceFrames] is the silence PlaybackScheduler counted before this call site's
     * caller took its own pre-chirp snapshot - i.e. during the actual audio segment, not the
     * calibration gap/chirp/drain that follows it. `silenceFrames` here stays the session-wide
     * total (design's health gate was never written against a total that includes ~180 chunks of
     * silence that are silent by design); the streaming-scoped count lets the gate be checked
     * against the number it was actually written for.
     */
    fun report(streamingSilenceFrames: Int): String {
        val stats = scheduler.stats()
        return "{\"played\":${stats.played},\"droppedLate\":${stats.droppedLate}," +
            "\"droppedOverflow\":${stats.droppedOverflow},\"silenceFrames\":${stats.silenceFrames}," +
            "\"streamingSilenceFrames\":$streamingSilenceFrames," +
            "\"adjustments\":$adjustments,\"driftSamples\":$driftSamples," +
            "\"lastFilteredErrorFrames\":$lastFilteredError,\"phase\":\"${phaseState.phase}\"," +
            "\"failureCode\":${failureCode?.let { "\"$it\"" } ?: "null"}}"
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val FRAMES_PER_CHUNK = 960
        const val CHUNK_NANOS = FRAMES_PER_CHUNK * 1_000_000_000L / SAMPLE_RATE
        private const val DEFAULT_DEPTH_NANOS = 200_000_000L

        /** Design sections 9.1 and 12: read the playback position once a second, no faster. */
        private const val DRIFT_INTERVAL_NANOS = 1_000_000_000L
        private const val UNDEFINED = Long.MIN_VALUE
    }
}
