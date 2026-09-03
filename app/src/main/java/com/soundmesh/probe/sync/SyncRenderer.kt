package com.soundmesh.probe.sync

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.soundmesh.core.DriftController
import com.soundmesh.core.PhaseState
import com.soundmesh.core.PlaybackDecision
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.REACQUIRE_THRESHOLD_FRAMES
import com.soundmesh.core.RendererPhase
import com.soundmesh.core.SchedulerStats
import com.soundmesh.core.driftIntervalNanos
import com.soundmesh.core.extrapolatedPlaybackFrames
import com.soundmesh.core.nextPhaseState
import com.soundmesh.core.pendingPlaybackFrames
import com.soundmesh.core.playbackErrorFrames
import com.soundmesh.core.releaseTrimFrames

/**
 * Feeds the scheduler's decisions to an AudioTrack and keeps playback on the shared timeline.
 * Output latency lives here: the scheduler is asked what should be leaving the speakers by the
 * time the bytes written now actually get there.
 */
class SyncRenderer(
    private val scheduler: PlaybackScheduler,
    private val drift: DriftController,
    /**
     * Whether to ask the AudioTrack for the fast mixer path. Defaults to false - the deep normal
     * mixer the M1/M2 numbers were measured on - because the two paths produce very different
     * residuals (-34.7ms against +90ms across one measured pair), so which one a run used has to
     * be a deliberate choice rather than a build-time constant. Echoed into [report].
     */
    private val lowLatency: Boolean = false,
    /**
     * How far the filtered error has to go before TRACKING falls back to ACQUIRING. Defaults to
     * [REACQUIRE_THRESHOLD_FRAMES], which is set well above the drift-sample noise floor so the
     * fallback only fires on a real slip - and which therefore did not fire once across eight
     * two-handset runs, leaving the mechanism unexecuted rather than demonstrated. Overridable per
     * run so it can be dropped below the noise floor deliberately, making the fallback fire on
     * ordinary jitter; that says nothing about whether the production threshold is right, but it is
     * the only way to watch the transition run on a device. Echoed into [report] so an artifact
     * always says which threshold produced it.
     */
    private val reacquireThresholdFrames: Int = REACQUIRE_THRESHOLD_FRAMES,
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
    /**
     * How many times TRACKING fell back to ACQUIRING on a wide excursion. Without it a run cannot
     * tell "the fallback never fired" from "the fallback fired and saved the run" - the phase field
     * alone reads TRACKING in both cases. Emitted by [report].
     */
    @Volatile private var reacquisitions = 0
    /**
     * How often a released chunk had to be trimmed, and by how much at worst. This is the release
     * phase made directly observable: before the trim existed the same quantity was silently
     * carried into the audio and left for the drift controller, and the only place it ever showed
     * up was the microphone. [maxTrimFrames] approaching one chunk means the loop is polling a
     * whole chunk period late; near zero means playback is contiguous. Emitted by [report].
     */
    @Volatile private var releaseTrims = 0
    @Volatile private var maxTrimFrames = 0
    /**
     * The trim applied to the chirp's own first chunk, or null if no chirp chunk was ever played.
     *
     * The whole-run counters above cannot answer the one question that decides where the residual
     * error lives, because they are dominated by the streaming segment. The calibration chirp is
     * the only thing the alignment number is actually measured from, and the claim that its release
     * carries no quantisation - the scheduler fills silence exactly up to the queued chirp's
     * instant, so the trim should be zero by the time it is released - has so far only been
     * reasoned, never measured. Zero here puts the remaining 1.5ms role-dependent term and the
     * doubled run-to-run scatter outside the playback layer; anything else puts them back in it.
     */
    @Volatile private var chirpTrimFrames: Int? = null

    /**
     * What each chirp repeat was released against, keyed by repeat index: its trim, the output
     * depth in force, and the drift loop's filtered error - all read at the instant that repeat's
     * first chunk was actually played, not when it was queued.
     *
     * Repeats exist to separate a per-session residual from a per-emission one, and the L batch
     * settled that: two emissions sharing one clock session still scatter 0.628ms, which is 73% of
     * the whole run-to-run variance, while the session-level term is not significant. The scatter
     * is therefore made fresh for each emission, somewhere in this playback path - and these are
     * the three quantities in it that can differ between two repeats of one run.
     *
     * Recording them per repeat turns the search into a paired comparison inside one session, where
     * the device, the link, the clock offset and the placement are all held constant. That is a far
     * stronger test than the run-to-run correlations that failed to find anything: those were
     * diluted by the session-level term and by every common-mode difference between runs.
     */
    private val chirpPlays = java.util.concurrent.ConcurrentHashMap<Int, ChirpPlay>()
    /**
     * The output depth this device was working from when it released the chirp's first chunk, or
     * null if no chirp chunk was ever played.
     *
     * The last un-instrumented quantity in the chain. Playback, link quality, the clock offset
     * estimate and the correlator have each been measured and ruled out as the source of the
     * ~1.15ms run-to-run scatter; the depth has not, and it lands straight in the answer, because
     * it is what decides how far ahead of being heard the chirp is written. It comes from
     * getTimestamp, whose position the HAL refreshes on its own schedule, so a run-to-run spread of
     * about a millisecond is exactly what would be expected here if this is where the scatter
     * lives. [minPendingFrames] and [maxPendingFrames] cannot answer it: they are whole-run
     * extremes dominated by the streaming segment.
     */
    @Volatile private var chirpDepthNanos: Long? = null
    @Volatile private var chirpStartStats: SchedulerStats? = null
    @Volatile private var chirpEndStats: SchedulerStats? = null
    @Volatile private var streamingEndStats: SchedulerStats? = null
    /** What the AudioTrack actually granted, and how deep the output really ran. Diagnostics only. */
    @Volatile private var trackBufferFrames = 0
    @Volatile private var minPendingFrames = Long.MAX_VALUE
    @Volatile private var maxPendingFrames = Long.MIN_VALUE
    /**
     * How the timestamp path itself is holding up. [pendingRejected] counts how often
     * pendingPlaybackFrames refused to return a reading, i.e. how often the extrapolated position
     * ran past everything written (underrun, or a pair the HAL never refreshed); together with
     * [timestampFailures] that is every occasion [outputDepthNanos] had no fresh depth to work
     * from, counted by [depthFallbacks]. A measured sink rejected ~5% of its readings, so how often
     * this path is taken decides how much of the run's output lead is a remembered value rather
     * than a measured one.
     */
    @Volatile private var timestampQueries = 0
    @Volatile private var timestampFailures = 0
    @Volatile private var pendingRejected = 0
    @Volatile private var depthFallbacks = 0
    /**
     * The last depth an actual reading produced, reused whenever the current reading is
     * unavailable. [DEFAULT_DEPTH_NANOS]' 200ms is a guess, and on a measured sink the real depth
     * was about 104ms - falling back to nearly double the truth on every unavailable reading is
     * itself a noise source, so the guess is only used before any reading has ever succeeded.
     */
    @Volatile private var lastDepthNanos = DEFAULT_DEPTH_NANOS
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
     * The scheduler counters as they stood immediately before the first chirp chunk was played,
     * and again immediately after the last one. Null until the chirp has actually been heard.
     *
     * Only the renderer can see the streaming -> chirp transition. The caller's own snapshot is
     * taken when the chirp is *submitted*, which is seconds earlier - every chunk is scheduled
     * well into its own future - so a window built from it spans the whole audio tail, the
     * calibration gap and the post-chirp drain as well, and the by-design silence in those swamps
     * the single missing chirp chunk the window exists to catch. Thread-safe: written on the
     * render thread, read by the caller after joining it.
     */
    fun chirpWindowStart(): SchedulerStats? = chirpStartStats

    fun chirpWindowEnd(): SchedulerStats? = chirpEndStats

    /**
     * The scheduler counters immediately after the last non-chirp chunk was played - the true end
     * of the streaming segment, which lags submission by the whole output lead. Null if no audio
     * chunk was ever played. Thread-safe on the same terms as [chirpWindowStart].
     */
    fun lastStreamingStats(): SchedulerStats? = streamingEndStats

    /**
     * How long the *current* acquisition has taken: from its first drift sample to the instant it
     * converged into TRACKING, or up to now if it has not converged yet. Null before the first
     * sample - playback has not pinned the shared timeline yet, so there is nothing to measure.
     * Thread-safe; reads [hostNanosNow] so it can be called mid-run, before the phase has settled.
     *
     * "Current" matters now that the phase can go backwards: a fallback to ACQUIRING restarts both
     * ends of this window (see [sampleDrift]), so after a fallback this reports the reacquisition
     * in progress rather than the first acquisition's long-finished duration. [reacquisitions] in
     * [report] says how many earlier acquisitions this number is hiding.
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
            val builder = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minimum, silence.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
            // Asks for the framework's fast mixer path instead of the deep normal-mixer one.
            // M2 measured a reproducible 34.7ms residual between two handsets that the shared
            // timeline cannot see, which means at least one device's getTimestamp does not
            // account for everything between the write and the speaker. A shallower output
            // path has less room to hide such a delay, so this is the cheap half of that
            // experiment: if the residual moves, the delay lives in the mixer layers and AAudio
            // can go further; if it does not, the delay is downstream of anything either API
            // reports. [trackBufferFrames] and the pending-frame range below record what the
            // request actually bought, since the framework may decline the fast path.
            //
            // Under an intent extra rather than always on: the measured residual moved from
            // -34.7ms to +90ms with it, so the two paths have to be A/B'd on one build instead of
            // one of them being silently baked in. Off means the call is not made at all, which is
            // the behaviour every earlier measurement was taken on.
            if (lowLatency) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            track = builder.build()
            trackBufferFrames = track.bufferSizeInFrames
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
                // Taken before poll on purpose: poll has already counted the chunk it returns, so
                // this is the only reading that excludes the chirp's own first chunk.
                val statsBeforePoll = scheduler.stats()
                val depthNanos = outputDepthNanos(track, timestamp, writtenFrames)
                // The instant the next frame written will be heard. Both poll's release test and
                // the trim below are asked about the same instant on purpose: poll decides whether
                // the chunk is due, the trim decides how much of it already is not.
                val heardAtHostNanos = hostNanosNow() + depthNanos
                when (val decision = scheduler.poll(heardAtHostNanos)) {
                    is PlaybackDecision.Play -> {
                        if (timelineNextHostNanos == UNDEFINED) {
                            // First chunk pins the timeline: acquisition starts now, sampling
                            // immediately rather than waiting a full DRIFT_INTERVAL_NANOS.
                            acquisitionStartHostNanos = hostNanosNow()
                            nextDriftCheckHostNanos = hostNanosNow()
                        }
                        val payload = applyPendingAdjust(decision.chunk.pcm)
                        // Whatever of this chunk is already in the past is dropped rather than
                        // written late (see releaseTrimFrames). Zero in contiguous playback, so
                        // this only bites at a start or after a gap - which is exactly where the
                        // release phase was being re-drawn. Clamped against the payload because
                        // applyPendingAdjust may have shortened it by a frame.
                        val trimFrames = releaseTrimFrames(
                            decision.chunk.playAtHostNanos, heardAtHostNanos, SAMPLE_RATE, FRAMES_PER_CHUNK
                        )
                        val offset = minOf(trimFrames * CHANNELS * 2, payload.size)
                        if (trimFrames > 0) {
                            releaseTrims++
                            if (trimFrames > maxTrimFrames) maxTrimFrames = trimFrames
                        }
                        track.write(payload, offset, payload.size - offset)
                        writtenFrames += (payload.size - offset) / (CHANNELS * 2)
                        // A dropped or duplicated frame deliberately does not move the timeline:
                        // shifting the frame-to-instant mapping by one frame is the correction.
                        timelineNextHostNanos = decision.chunk.playAtHostNanos + CHUNK_NANOS
                        recordPlayedBoundary(decision.chunk.sequence, statsBeforePoll, trimFrames, depthNanos)
                    }
                    is PlaybackDecision.Silence -> {
                        // Honours the frame count rather than always writing a whole chunk: the
                        // scheduler fills only as far as the next chunk's instant, so the write
                        // stream lands on it instead of stepping past it and making it late.
                        val bytes = decision.frames * CHANNELS * 2
                        track.write(silence, 0, bytes)
                        writtenFrames += decision.frames.toLong()
                        if (timelineNextHostNanos != UNDEFINED) {
                            timelineNextHostNanos += decision.frames * 1_000_000_000L / SAMPLE_RATE
                        }
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

    /**
     * Moves the chirp-window and streaming-segment boundaries along as chunks are played.
     *
     * Chirp chunks carry their own [CHIRP_SEQUENCE_BASE] sequence range, which is what separates
     * them from streamed audio here. [statsBeforePoll] is the reading taken before poll counted
     * this chunk, so the window opens just short of the chirp's first chunk and closes after its
     * last; [streamingEndStats] keeps the streaming segment's real end, which is up to the whole
     * output lead later than the instant the caller stopped submitting.
     *
     * A run that repeats the chirp gives each repeat its own [CHIRP_REPEAT_STRIDE] band of
     * sequences, and only the first band moves these boundaries. Letting a later repeat close the
     * window would stretch it across the whole interval between them, whose seconds of by-design
     * silence bury the single missing chunk the window exists to catch - and would leave the
     * reported numbers incomparable with every run taken before repeats existed.
     */
    private fun recordPlayedBoundary(sequence: Int, statsBeforePoll: SchedulerStats, trimFrames: Int, depthNanos: Long) {
        if (sequence >= CHIRP_SEQUENCE_BASE) {
            // Every repeat is measured on its own first chunk; only the first moves the window.
            val repeat = (sequence - CHIRP_SEQUENCE_BASE) / CHIRP_REPEAT_STRIDE
            chirpPlays.putIfAbsent(repeat, ChirpPlay(trimFrames, depthNanos, lastFilteredError))
            if (repeat > 0) return
            if (chirpStartStats == null) {
                chirpStartStats = statsBeforePoll
                chirpTrimFrames = trimFrames
                chirpDepthNanos = depthNanos
            }
            chirpEndStats = scheduler.stats()
        } else {
            streamingEndStats = scheduler.stats()
        }
    }

    /**
     * Frames handed over but not yet heard, or null when no usable reading is available - either
     * the device had no timestamp to give, or the extrapolated position ran past everything
     * written and pendingPlaybackFrames rejected it.
     *
     * Every call is counted, and so is every refusal of either kind, because a refusal is not free:
     * the caller has to fall back to a depth it did not measure. The rejection is detected against
     * the unbounded position rather than guessed from the sign of a later value - a pending count
     * of zero is a perfectly ordinary reading on a drained output, so only the extrapolated
     * position overrunning [writtenFrames] says the reading was impossible.
     */
    private fun pendingFrames(track: AudioTrack, timestamp: AudioTimestamp, writtenFrames: Long): Long? {
        timestampQueries++
        if (!track.getTimestamp(timestamp)) {
            timestampFailures++
            return null
        }
        // Deliberately not hostNanosNow(): timestamp.nanoTime is on TIMEBASE_MONOTONIC, the same
        // origin as System.nanoTime(), and only their difference is used. Read once so the
        // rejection check and the returned value share an instant.
        val nowNanos = System.nanoTime()
        if (extrapolatedPlaybackFrames(timestamp.framePosition, timestamp.nanoTime, nowNanos, SAMPLE_RATE) > writtenFrames) {
            pendingRejected++
        }
        return pendingPlaybackFrames(
            writtenFrames = writtenFrames,
            framePosition = timestamp.framePosition,
            timestampNanos = timestamp.nanoTime,
            nowNanos = nowNanos,
            sampleRate = SAMPLE_RATE
        )
    }

    /**
     * Frames handed over but not yet heard, as nanoseconds of lead the scheduler must account for.
     *
     * When no usable reading is available the last measured depth is reused. The output buffer's
     * depth moves slowly - it is a property of the device and the mixer path, not of this
     * iteration - so a reading taken a chunk or two ago is far closer to the truth than
     * [DEFAULT_DEPTH_NANOS], which is only used until a first reading has ever succeeded.
     */
    private fun outputDepthNanos(track: AudioTrack, timestamp: AudioTimestamp, writtenFrames: Long): Long {
        val pending = pendingFrames(track, timestamp, writtenFrames)
        if (pending == null) {
            depthFallbacks++
            return lastDepthNanos
        }
        val depthNanos = pending * 1_000_000_000L / SAMPLE_RATE
        lastDepthNanos = depthNanos
        return depthNanos
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
     *
     * The move is not one-way. Every Play re-pins `timelineNextHostNanos` to that chunk's own
     * instant, so a starvation gap re-draws the whole release-phase error mid-run; [nextPhaseState]
     * sends the phase back to ACQUIRING when the filtered error stays wide, and the acquisition
     * window is restarted here so [acquisitionDurationNanos] describes the acquisition actually
     * running.
     */
    private fun sampleDrift(track: AudioTrack, timestamp: AudioTimestamp, writtenFrames: Long, timelineNextHostNanos: Long) {
        val pendingFrames = pendingFrames(track, timestamp, writtenFrames) ?: return
        if (pendingFrames < minPendingFrames) minPendingFrames = pendingFrames
        if (pendingFrames > maxPendingFrames) maxPendingFrames = pendingFrames
        val errorFrames = playbackErrorFrames(hostNanosNow(), pendingFrames, timelineNextHostNanos, SAMPLE_RATE)
        val decision = drift.observe(errorFrames)
        lastFilteredError = decision.filteredErrorFrames
        driftSamples++
        pendingAdjustFrames = decision.adjustFrames
        val wasAcquiring = phaseState.phase == RendererPhase.ACQUIRING
        phaseState = nextPhaseState(
            phaseState,
            inDeadband = decision.adjustFrames == 0,
            filteredErrorFrames = decision.filteredErrorFrames,
            reacquireThresholdFrames = reacquireThresholdFrames
        )
        val isAcquiring = phaseState.phase == RendererPhase.ACQUIRING
        if (wasAcquiring && !isAcquiring) {
            acquisitionConvergedAtHostNanos = hostNanosNow()
        } else if (!wasAcquiring && isAcquiring) {
            // Fallen back. The acquisition window has to be restarted, not extended: leaving the
            // old converged instant in place would have acquisitionDurationNanos() keep reporting
            // the first acquisition's duration while a second one is actually running, and moving
            // only the start would make it negative against that stale end.
            reacquisitions++
            acquisitionStartHostNanos = hostNanosNow()
            acquisitionConvergedAtHostNanos = UNDEFINED
        }
    }

    /** Chunk-period cadence (~50Hz) while ACQUIRING; the spec's 1Hz cadence once TRACKING. */
    private fun driftIntervalNanos(): Long =
        driftIntervalNanos(phaseState.phase, CHUNK_NANOS, DRIFT_INTERVAL_NANOS)

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
     * [streamingSilenceFrames] is the silence PlaybackScheduler counted up to the moment the last
     * streamed chunk was actually played (see [lastStreamingStats]) - i.e. during the actual audio
     * segment, not the calibration gap/chirp/drain that follows it. `silenceFrames` here stays the
     * session-wide total (design's health gate was never written against a total that includes
     * ~180 chunks of silence that are silent by design); the streaming-scoped count lets the gate
     * be checked against the number it was actually written for. Null - written out as JSON null
     * rather than a zero that would read as a clean segment - when no streamed chunk ever played.
     */
    /** What each repeat was released against, in repeat order. Empty when no chirp ever played. */
    private fun chirpPlaysJson(): String = chirpPlays.keys.sorted().joinToString(",", "[", "]") { repeat ->
        val play = chirpPlays.getValue(repeat)
        "{\"repeat\":$repeat,\"trimFrames\":${play.trimFrames}," +
            "\"depthNanos\":${play.depthNanos},\"filteredErrorFrames\":${play.filteredErrorFrames}}"
    }

    /** One chirp repeat's release conditions. See [chirpPlays]. */
    private class ChirpPlay(val trimFrames: Int, val depthNanos: Long, val filteredErrorFrames: Int)

    fun report(streamingSilenceFrames: Int?): String {
        val stats = scheduler.stats()
        return "{\"played\":${stats.played},\"droppedLate\":${stats.droppedLate}," +
            "\"droppedOverflow\":${stats.droppedOverflow},\"silenceFrames\":${stats.silenceFrames}," +
            "\"streamingSilenceFrames\":${streamingSilenceFrames ?: "null"}," +
            "\"adjustments\":$adjustments,\"driftSamples\":$driftSamples," +
            "\"lastFilteredErrorFrames\":$lastFilteredError,\"phase\":\"${phaseState.phase}\"," +
            "\"reacquisitions\":$reacquisitions," +
            "\"releaseTrims\":$releaseTrims,\"maxTrimFrames\":$maxTrimFrames," +
            "\"chirpTrimFrames\":${chirpTrimFrames ?: "null"},\"chirpDepthNanos\":${chirpDepthNanos ?: "null"}," +
            "\"chirpPlays\":${chirpPlaysJson()}," +
            "\"reacquireThresholdFrames\":$reacquireThresholdFrames," +
            "\"trackBufferFrames\":$trackBufferFrames," +
            "\"minPendingFrames\":${if (minPendingFrames == Long.MAX_VALUE) "null" else minPendingFrames}," +
            "\"maxPendingFrames\":${if (maxPendingFrames == Long.MIN_VALUE) "null" else maxPendingFrames}," +
            "\"timestampQueries\":$timestampQueries,\"timestampFailures\":$timestampFailures," +
            "\"pendingRejected\":$pendingRejected,\"depthFallbacks\":$depthFallbacks," +
            "\"lowLatency\":$lowLatency," +
            "\"failureCode\":${failureCode?.let { "\"$it\"" } ?: "null"}}"
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val FRAMES_PER_CHUNK = 960
        const val CHUNK_NANOS = FRAMES_PER_CHUNK * 1_000_000_000L / SAMPLE_RATE

        /**
         * Chirp chunks are generated locally rather than streamed, so they carry their own
         * sequence range. The renderer needs it too: it is how a played chunk is told apart from
         * streamed audio when the chirp window's boundaries are recorded.
         */
        const val CHIRP_SEQUENCE_BASE = 1_000_000

        /**
         * Sequences per chirp repeat. A run that plays the chirp several times inside one clock
         * session gives repeat n the band starting at [CHIRP_SEQUENCE_BASE] + n * this, which is
         * how [recordPlayedBoundary] keeps the reported chirp window on the first repeat alone.
         * Far wider than the six chunks a sweep occupies, so the bands cannot run into each other.
         */
        const val CHIRP_REPEAT_STRIDE = 1_000

        private const val DEFAULT_DEPTH_NANOS = 200_000_000L

        /** Design sections 9.1 and 12: read the playback position once a second, no faster. */
        private const val DRIFT_INTERVAL_NANOS = 1_000_000_000L
        private const val UNDEFINED = Long.MIN_VALUE
    }
}
