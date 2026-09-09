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
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialShaper
import com.soundmesh.core.StereoGain
import com.soundmesh.core.acquiringTotalNanos
import com.soundmesh.core.driftIntervalNanos
import com.soundmesh.core.extrapolatedPlaybackFrames
import com.soundmesh.core.nextPhaseState
import com.soundmesh.core.pendingPlaybackFrames
import com.soundmesh.core.playbackErrorFrames
import com.soundmesh.core.releaseTrimFrames
import com.soundmesh.probe.PlaybackUsage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * What the output path this run actually got looks like, as the framework describes it.
 *
 * Every field here is a thing the framework decides rather than a thing this code asks for, and
 * they are recorded because on 2026-09-08 the run-level alignment bias turned out to sit at one of
 * two levels 0.87 ms apart, drawn afresh each run, with nothing in the report able to tell which.
 * Twelve runs could establish that the two levels exist and not what distinguishes them - a sweep
 * over the fields that were being recorded found nothing that survived the multiple comparisons.
 * These are the ones the sweep had no access to.
 */
internal fun trackProfileJson(
    minBufferBytes: Int,
    requestedBufferBytes: Int,
    bufferSizeFrames: Int,
    bufferCapacityFrames: Int,
    performanceMode: Int,
    sampleRate: Int,
    firstPendingFrames: Long?
): String =
    "{\"minBufferBytes\":$minBufferBytes,\"requestedBufferBytes\":$requestedBufferBytes," +
        "\"bufferSizeFrames\":$bufferSizeFrames,\"bufferCapacityFrames\":$bufferCapacityFrames," +
        "\"performanceMode\":$performanceMode,\"sampleRate\":$sampleRate," +
        "\"firstPendingFrames\":${firstPendingFrames ?: "null"}}"

/**
 * One chunk of PCM as the spatial rule wants it heard, or the chunk itself when no rule applies.
 *
 * Kept out of [SyncRenderer] so it can be tested without an AudioTrack, the same reason
 * [trackProfileJson] sits out here. The gain law is core's; what is decided here is which chunks
 * it is allowed near.
 *
 * Chirp chunks are never shaped. The chirp is the instrument every alignment number is measured
 * with, and a gain on it moves the correlation peak and the between-handset ratios the verdict is
 * read from - while sounding like a room working correctly. This is the same exemption the trim
 * deadband and the splice fade already take, for the same reason.
 *
 * A handset the drawing does not name plays on unshaped rather than going silent. Absence means a
 * stale rule or a bug, and the two answers are not symmetric: playing on is the room behaving as
 * it did before spatial audio existed, while a silent handset is one dropping out of a room the
 * listener is still looking at, with nothing on screen saying why.
 */
internal fun spatialShaped(
    sequence: Int,
    playAtHostNanos: Long,
    payload: ByteArray,
    field: SpatialField?,
    peerId: String?,
    wasUnder: SpatialField? = null
): ByteArray {
    if (field == null || peerId == null) return payload
    if (sequence >= SyncRenderer.CHIRP_SEQUENCE_BASE) return payload
    if (!field.layout.contains(peerId)) return payload
    return SpatialShaper.shape(
        payload,
        field,
        peerId,
        playAtHostNanos,
        SyncRenderer.SAMPLE_RATE,
        from = cameFrom(wasUnder, field, peerId, playAtHostNanos)
    )
}

/**
 * Where the room was a moment ago, when that is not where this rule says it is.
 *
 * Null whenever the previous chunk was already under this same rule, which is every chunk of
 * ordinary playback - the law is continuous, so its value at this chunk's first instant is exactly
 * where the previous chunk left off and the ramp needs no help.
 *
 * The three cases that are not that: the first rule arriving at a handset that has been playing
 * unshaped, a rule being replaced by a different one, and an icon being dragged - which publishes a
 * new rule several times a second. In all three the gain steps rather than moves, and the step is
 * the whole gain: up to unity, sixty times the 1.64% a chunk edge is worth. That is the one the
 * room can hear, and it is what a listener reported as a noise in the first second of a session.
 *
 * Unity for the handset that was playing under no rule at all, because that is what it was heard
 * at. A handset the old rule did not name is the same case.
 */
private fun cameFrom(
    wasUnder: SpatialField?,
    now: SpatialField,
    peerId: String,
    playAtHostNanos: Long
): StereoGain? {
    if (wasUnder === now) return null
    if (wasUnder == null || !wasUnder.layout.contains(peerId)) return StereoGain(1.0, 1.0)
    return wasUnder.gainAt(peerId, playAtHostNanos)
}

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
     *
     * Those eight runs each played five seconds. On twenty-minute runs the fallback fires 20 to 36
     * times, once every 32 to 55 seconds of TRACKING, so at the production threshold it is the
     * steady state rather than an exception - see [completedAcquiringNanos] for what that costs.
     */
    private val reacquireThresholdFrames: Int = REACQUIRE_THRESHOLD_FRAMES,
    /**
     * The host offset in force, for recording alongside a chirp release. Zero on the host itself.
     *
     * Separate from [hostNanosNow] on purpose. Deriving the offset as hostNanosNow() minus a
     * nanoTime() taken beside it also picks up whatever elapsed between the two reads - measured
     * at 5 to 75 microseconds on one run, always positive, worst on the first release. Small next
     * to the millisecond the recording exists to resolve, but it turned an exact check into an
     * approximate one, and an approximate check is one that gets explained away.
     */
    private val offsetNanosNow: () -> Long = { 0L },
    /**
     * How late a streamed chunk may be released before it is shortened instead of written whole.
     *
     * Defaults to [TRIM_DEADBAND_FRAMES], the value every measurement so far was taken on.
     * Overridable for the same reason [lowLatency] is: the constant was sized against O35's
     * 8.6-frame mean release error, and a product session measured a mean of 77 frames among the
     * releases that actually cross it, about 3.3 times a second on the host. Whether those edits
     * are what a listener hears is a question for a band wide enough to stop them and a pair of
     * ears, and a band that wide is not something to bake in before that answer exists.
     */
    private val trimDeadbandFrames: Int = TRIM_DEADBAND_FRAMES,
    /**
     * Which output the audio is attributed to, and therefore which volume slider controls it.
     *
     * Defaults to [PlaybackUsage.MEDIA] - the attribution every archived measurement was taken on,
     * so the ruler's own path is untouched by this parameter existing. A host that is capturing
     * what a music app plays cannot use it: the capture only reads a stream the media volume has
     * to be at zero for, and R1 on the X10 proved that a media-usage playback is muted along with
     * it - the report said the player ran, routed to the speaker, dropped nothing, and nobody heard
     * a thing. [PlaybackUsage.ACCESSIBILITY] has its own volume and was audible under exactly those
     * conditions on both handsets.
     *
     * Echoed into [report] for the reason [lowLatency] is: which output path a run used is not
     * something a later reader should have to infer from the build.
     */
    private val playbackUsage: PlaybackUsage = PlaybackUsage.MEDIA,
    /**
     * Which handset in a spatial drawing this renderer is. Null - the default - means no spatial
     * rule can apply here at all, which is what every run before spatial audio existed did and
     * what the measurement runs keep doing.
     */
    private val spatialPeerId: String? = null,
    private val hostNanosNow: () -> Long
) {
    private val silence = ByteArray(FRAMES_PER_CHUNK * CHANNELS * 2)
    // Written from whichever thread the rule arrived on and read by the render loop. A rule is a
    // whole object replaced at once, never edited in place, so a reader sees either the old room
    // or the new one and never a room half way between two drawings.
    @Volatile private var spatialField: SpatialField? = null
    @Volatile private var untilHostNanos = Long.MIN_VALUE
    @Volatile private var adjustments = 0
    // Which arm ran. A spatial run and a flat one are the same binary, the same log and the same
    // duration; without this the only thing that tells them apart is a pair of ears.
    @Volatile private var spatialChunks = 0

    // Which rule the last chunk was actually heard under, so a new one can be ramped away from it
    // rather than stepped into. Null means the last chunk went out unshaped - at unity - which is
    // true both before any rule arrives and for a chirp, which is never shaped.
    private var shapedUnder: SpatialField? = null
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
     * What each fallback cost, beside how many there were.
     *
     * [completedAcquiringNanos] carries the windows that have already converged; [report] adds the
     * one still running through [acquiringTotalNanos]. Twenty fallbacks in a twenty-minute run
     * price out very differently at four seconds each and at forty, and the fast cadence they
     * switch on edits a frame in the waveform up to fifty times a second.
     *
     * [reacquisitionErrorSumFrames] is the filtered error each fallback fired at, summed;
     * against [reacquisitions] it gives the mean excursion. That mean is what separates a slow
     * ramp out of the deadband from a step: landing near [REACQUIRE_THRESHOLD_FRAMES] says the
     * error crept over it, while twice the threshold says something moved it in one go.
     * [maxFilteredErrorMagnitudeFrames] holds the run's worst, which lastFilteredErrorFrames
     * cannot report - it is one sample, and on a run ending mid-acquisition it is one taken while
     * the error was already being worked off.
     *
     * One thing to subtract before reading a sink's reported acquiringNanos. A sink outlives its
     * host by SinkSession's host-gone budget, and across that whole tail nothing is queued, so the
     * loop writes blind silence - which advances the timeline by exactly the frames it writes,
     * leaving the error where it was, while applyPendingAdjust never runs because it runs only on
     * a Play. An acquisition in progress when the host stopped therefore cannot converge and holds
     * for the entire tail. On the measured runs that tail was 61.9s against 169s of real
     * acquisition, so it is not a rounding error. silenceFrames says how long it was.
     */
    @Volatile private var completedAcquiringNanos = 0L
    @Volatile private var reacquisitionErrorSumFrames = 0L
    @Volatile private var maxFilteredErrorMagnitudeFrames = 0
    /**
     * How often a released chunk had to be trimmed, and by how much at worst. This is the release
     * phase made directly observable: before the trim existed the same quantity was silently
     * carried into the audio and left for the drift controller, and the only place it ever showed
     * up was the microphone. [maxTrimFrames] approaching one chunk means the loop is polling a
     * whole chunk period late. Emitted by [report].
     *
     * This used to add "near zero means playback is contiguous", which read as a claim that a
     * healthy run trims rarely. It does not: O26-O34 trimmed 25-56% of chunks with nothing else
     * wrong, so the rate says which scheduling the run used, not whether it was well. See
     * [trimmedFrames] for the quantity that does carry information.
     */
    @Volatile private var releaseTrims = 0
    @Volatile private var maxTrimFrames = 0

    /**
     * Every frame the trim has thrown away, so the typical trim is readable as
     * [trimmedFrames] / [releaseTrims] rather than guessed at from the worst one.
     *
     * The count alone was not enough to act on. Runs O26-O34 all reported a trim on 25-56% of
     * chunks, against a comment two lines up claiming playback that is contiguous trims near zero -
     * and with only the count and the maximum, there was no telling a harmless one-frame
     * quantisation from an audible three-millisecond edit. A listener reported a crackle on both
     * handsets on every source; this is the number that says whether the trim is what they heard.
     */
    @Volatile private var trimmedFrames = 0L

    /**
     * What the AudioTrack itself says it ran dry, which the scheduler's own counts cannot see: a
     * chunk trimmed or dropped is still a chunk this renderer knows about, while an underrun is
     * the HAL reaching the end of what was written. DelayedLocalPlayer has reported this since D1;
     * the sync path never did, so a gap in the audio had nowhere to show up.
     */
    @Volatile private var trackUnderruns = 0

    /** Silence writes as events and the longest of them, beside the frame total. */
    @Volatile private var silenceWrites = 0
    @Volatile private var maxSilenceFrames = 0

    /**
     * [silenceWrites] frozen where the streaming segment ends, or -1 if no chirp ever played.
     *
     * The whole-run count is dominated by the chirp schedule, which fills a whole chunk of
     * silence between chirps and so writes about fifty a second for its whole length. Only the
     * streaming-scoped count can be read beside streamingSilenceFrames.
     */
    @Volatile private var streamingSilenceWrites = -1
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
    /**
     * The rest of what the framework granted, reported by [trackProfileJson].
     *
     * [firstPendingFrames] is the one that is not a build-time constant: the first depth the
     * timestamp path ever returned, which is how far ahead of the speaker this run actually ran
     * before any drift correction had happened. Whole-run extremes cannot answer that - they are
     * dominated by the streaming segment - and the run-level bias this is here to identify is
     * fixed before the first chirp.
     */
    @Volatile private var trackMinBufferBytes = 0
    @Volatile private var trackRequestedBytes = 0
    @Volatile private var trackCapacityFrames = 0
    @Volatile private var trackPerformanceMode = 0
    @Volatile private var trackSampleRate = 0
    @Volatile private var firstPendingFrames: Long? = null
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
                .setAudioAttributes(AudioAttributes.Builder().setUsage(playbackUsage.androidUsage).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
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
            trackMinBufferBytes = minimum
            trackRequestedBytes = maxOf(minimum, silence.size * 2)
            trackCapacityFrames = track.bufferCapacityInFrames
            trackPerformanceMode = track.performanceMode
            trackSampleRate = track.sampleRate
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
                // Cumulative on the track, so the last read is the run's total. Polled here rather
                // than once at the end because the track is released before report() is called.
                runCatching { trackUnderruns = track.underrunCount }
                when (val decision = scheduler.poll(heardAtHostNanos)) {
                    is PlaybackDecision.Play -> {
                        if (timelineNextHostNanos == UNDEFINED) {
                            // First chunk pins the timeline: acquisition starts now, sampling
                            // immediately rather than waiting a full DRIFT_INTERVAL_NANOS.
                            acquisitionStartHostNanos = hostNanosNow()
                            nextDriftCheckHostNanos = hostNanosNow()
                        }
                        // Shaped before the trim, not after: a trim shortens what is written
                        // without moving the instant any surviving frame lands on, so frame j of
                        // this payload is heard at playAtHostNanos + j/SAMPLE_RATE either way.
                        val adjusted = applyPendingAdjust(decision.chunk.pcm)
                        // Read once: it is written from another thread, and a rule that changed
                        // between the shaping and the remembering would leave the next chunk
                        // ramping away from a rule that was never applied.
                        val rule = spatialField
                        val payload = spatialShaped(
                            decision.chunk.sequence,
                            decision.chunk.playAtHostNanos,
                            adjusted,
                            rule,
                            spatialPeerId,
                            wasUnder = shapedUnder
                        )
                        shapedUnder = if (payload !== adjusted) rule else null
                        // Against the adjusted array, not against the chunk: applyPendingAdjust returns a
                        // fresh array too, and a dropped frame counting as a shaped chunk would
                        // make the tally say the spatial arm ran on a run where it never did.
                        if (payload !== adjusted) spatialChunks++
                        // Whatever of this chunk is already in the past is dropped rather than
                        // written late (see releaseTrimFrames). Clamped against the payload
                        // because applyPendingAdjust may have shortened it by a frame.
                        //
                        // Below TRIM_DEADBAND_FRAMES the chunk is written whole and heard that
                        // fraction of a millisecond late instead. O35 measured why: the release
                        // error is two-sided jitter with a mean trim of 8.6 frames, and trimming
                        // was a one-sided answer to it - late chunks lost audio, early ones gained
                        // silence, and the silence written over the run (90444 frames) came out
                        // within 1% of the audio cut (91174). That is 0.47% of the music replaced
                        // by ~26 splices a second, which is what a listener hears as a crackle.
                        //
                        // Chirp chunks keep trimming with no deadband. The chirp is the instrument
                        // the alignment is measured with, so its release must stay exact; the
                        // deadband would put its own width straight into every measurement.
                        val deadband = if (decision.chunk.sequence >= CHIRP_SEQUENCE_BASE) 0 else trimDeadbandFrames
                        val trimFrames = releaseTrimFrames(
                            decision.chunk.playAtHostNanos, heardAtHostNanos, SAMPLE_RATE, FRAMES_PER_CHUNK
                        ).let { if (it < deadband) 0 else it }
                        val offset = minOf(trimFrames * CHANNELS * 2, payload.size)
                        if (trimFrames > 0) {
                            releaseTrims++
                            trimmedFrames += trimFrames
                            if (trimFrames > maxTrimFrames) maxTrimFrames = trimFrames
                        }
                        // A trim leaves a step in the waveform: the previous chunk ran on into
                        // payload[0], and the next sample heard is payload[offset]. The step is
                        // what clicks - not the missing frames - so streamed audio is faded across
                        // it instead of butted together. Same length either way, so the release
                        // instant this trim exists to hit is untouched.
                        //
                        // Chirp chunks keep the butt splice they have always had. The fade would
                        // rewrite the sweep's first frames, and those are what the correlator
                        // measures alignment with - every run back to O1 would stop being
                        // comparable to answer a question about the music.
                        val faded = if (offset > 0 && decision.chunk.sequence < CHIRP_SEQUENCE_BASE) {
                            spliceAcross(payload, offset)
                        } else {
                            null
                        }
                        if (faded != null) track.write(faded, 0, faded.size)
                        else track.write(payload, offset, payload.size - offset)
                        writtenFrames += (payload.size - offset) / (CHANNELS * 2)
                        // A dropped or duplicated frame deliberately does not move the timeline:
                        // shifting the frame-to-instant mapping by one frame is the correction.
                        timelineNextHostNanos = decision.chunk.playAtHostNanos + CHUNK_NANOS
                        recordPlayedBoundary(decision.chunk.sequence, statsBeforePoll, trimFrames, depthNanos)
                    }
                    is PlaybackDecision.Silence -> {
                        // Counted as events, not only as frames. The frame total said 124 a second
                        // without saying whether that was one long gap or fifty short ones - and
                        // only the second of those punches fifty holes into the music. The
                        // listener's description was bursts of clicks, which is what the frame
                        // total alone could neither confirm nor rule out.
                        silenceWrites++
                        if (decision.frames > maxSilenceFrames) maxSilenceFrames = decision.frames
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
            // Freeze the silence-write count at the streaming segment's end, the same boundary
            // streamingSilenceFrames is scoped to. O38 reported the whole-run count beside a
            // streaming-scoped frame total, and dividing one by the other gave a mean of 2.6
            // frames a write - a number with no meaning, because roughly 15000 of those writes
            // were the 960-frame fills of the chirp schedule that follows.
            if (streamingSilenceWrites < 0) streamingSilenceWrites = silenceWrites
            // Every repeat is measured on its own first chunk; only the first moves the window.
            val repeat = (sequence - CHIRP_SEQUENCE_BASE) / CHIRP_REPEAT_STRIDE
            // Read the clock only on the repeat's first chunk, not on every chunk of it: the lambda
            // runs only when this repeat is new. localNanos locates the release in the recorded
            // exchange stream, whose t1 are on this same clock; offsetNanos is what the run actually
            // converted through, which is not always the newest estimate - a cycle whose fit is
            // rejected leaves the previous one standing.
            chirpPlays.computeIfAbsent(repeat) {
                val localNanos = System.nanoTime()
                ChirpPlay(trimFrames, depthNanos, lastFilteredError, localNanos, offsetNanosNow())
            }
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
        if (firstPendingFrames == null) firstPendingFrames = pendingFrames
        if (pendingFrames < minPendingFrames) minPendingFrames = pendingFrames
        if (pendingFrames > maxPendingFrames) maxPendingFrames = pendingFrames
        val errorFrames = playbackErrorFrames(hostNanosNow(), pendingFrames, timelineNextHostNanos, SAMPLE_RATE)
        val decision = drift.observe(errorFrames)
        lastFilteredError = decision.filteredErrorFrames
        val magnitude = abs(decision.filteredErrorFrames)
        if (magnitude > maxFilteredErrorMagnitudeFrames) maxFilteredErrorMagnitudeFrames = magnitude
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
            // Banked at the instant it converged, so the running total never has to reconstruct a
            // window whose start has since been overwritten by the next fallback.
            completedAcquiringNanos += acquisitionConvergedAtHostNanos - acquisitionStartHostNanos
        } else if (!wasAcquiring && isAcquiring) {
            // Fallen back. The acquisition window has to be restarted, not extended: leaving the
            // old converged instant in place would have acquisitionDurationNanos() keep reporting
            // the first acquisition's duration while a second one is actually running, and moving
            // only the start would make it negative against that stale end.
            reacquisitions++
            reacquisitionErrorSumFrames += magnitude
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
     * The kept part of a trimmed chunk, with the cut faded over instead of butted against what
     * came before.
     *
     * Where the discontinuity is: the previous chunk ran on into `payload[0]`, and a trim of
     * `offset` bytes makes the next sample heard `payload[offset]`. Those two are unrelated
     * samples, and the step between them is what is heard - the missing frames themselves are
     * inaudible at the sizes measured (a mean of 70 frames after the deadband, 1.5 ms).
     *
     * So the first [SPLICE_RAMP_FRAMES] frames of the result cross from the audio that would have
     * played to the audio that does:
     *
     *     out[i] = payload[i] * (1 - w) + payload[offset + i] * w,  w rising 0 -> 1
     *
     * At `i = 0` the result is exactly `payload[0]`, which continues the previous chunk; at the end
     * of the ramp it is exactly `payload[offset + ramp - 1]`, which continues into the rest. Both
     * joins are now continuous, and the ramp is raised-cosine so the slope is continuous too - a
     * linear fade would leave a corner at each end, which is a quieter click rather than none.
     *
     * The result is the same length as the plain trim, so this changes what is heard and not when.
     */
    private fun spliceAcross(payload: ByteArray, offset: Int): ByteArray {
        val out = payload.copyOfRange(offset, payload.size)
        val bytesPerFrame = CHANNELS * 2
        // Bounded by both sides: the fade reads `offset` frames of outgoing audio and the same
        // count of incoming, so a trim shorter than the ramp fades over only what it has.
        val ramp = minOf(SPLICE_RAMP_FRAMES, offset / bytesPerFrame, out.size / bytesPerFrame)
        // A ramp of two frames is a butt splice in disguise and measured worse than one: the
        // deadband keeps offset at 48 frames or more so it cannot be reached, but a shorter one
        // must not quietly make things worse.
        if (ramp < 4) return out
        for (frame in 0 until ramp) {
            val w = 0.5 - 0.5 * cos(PI * frame / (ramp - 1))
            for (channel in 0 until CHANNELS) {
                val at = frame * bytesPerFrame + channel * 2
                val outgoing = sampleAt(payload, at)
                val incoming = sampleAt(out, at)
                val blended = (outgoing * (1.0 - w) + incoming * w).toInt()
                out[at] = (blended and 0xFF).toByte()
                out[at + 1] = (blended shr 8).toByte()
            }
        }
        return out
    }

    /** One little-endian 16-bit sample, sign extended. */
    private fun sampleAt(pcm: ByteArray, at: Int): Int =
        ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()

    /**
     * Puts a new spatial rule in force from the next chunk written.
     *
     * Nothing here decides when it takes effect on the timeline, because the rule is a function of
     * the host instant and the instants are already in the chunks. Called from whichever thread
     * the rule arrived on. Null puts the room back to flat.
     */
    fun applySpatialField(field: SpatialField?) {
        spatialField = field
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
            "\"depthNanos\":${play.depthNanos},\"filteredErrorFrames\":${play.filteredErrorFrames}," +
            "\"localNanos\":${play.localNanos},\"offsetNanos\":${play.offsetNanos}}"
    }

    /** One chirp repeat's release conditions. See [chirpPlays]. */
    private class ChirpPlay(
        val trimFrames: Int,
        val depthNanos: Long,
        val filteredErrorFrames: Int,
        /** This device's own clock at the release, which is the axis the recorded exchanges use. */
        val localNanos: Long,
        /** The host offset this release was converted through. Zero on the host, its own reference. */
        val offsetNanos: Long
    )

    fun report(streamingSilenceFrames: Int?): String {
        val stats = scheduler.stats()
        return "{\"played\":${stats.played},\"droppedLate\":${stats.droppedLate}," +
            "\"droppedOverflow\":${stats.droppedOverflow},\"silenceFrames\":${stats.silenceFrames}," +
            "\"streamingSilenceFrames\":${streamingSilenceFrames ?: "null"}," +
            "\"adjustments\":$adjustments,\"driftSamples\":$driftSamples," +
            "\"lastFilteredErrorFrames\":$lastFilteredError,\"phase\":\"${phaseState.phase}\"," +
            "\"reacquisitions\":$reacquisitions," +
            "\"acquiringNanos\":${acquiringTotalNanos(
                completedAcquiringNanos,
                acquisitionStartHostNanos.takeIf { it != UNDEFINED },
                acquisitionConvergedAtHostNanos.takeIf { it != UNDEFINED },
                hostNanosNow()
            )}," +
            "\"reacquisitionErrorSumFrames\":$reacquisitionErrorSumFrames," +
            "\"maxFilteredErrorMagnitudeFrames\":$maxFilteredErrorMagnitudeFrames," +
            "\"releaseTrims\":$releaseTrims,\"maxTrimFrames\":$maxTrimFrames," +
            "\"trimmedFrames\":$trimmedFrames,\"trackUnderruns\":$trackUnderruns," +
            "\"silenceWrites\":$silenceWrites,\"maxSilenceFrames\":$maxSilenceFrames," +
            "\"streamingSilenceWrites\":$streamingSilenceWrites," +
            "\"chirpTrimFrames\":${chirpTrimFrames ?: "null"},\"chirpDepthNanos\":${chirpDepthNanos ?: "null"}," +
            "\"chirpPlays\":${chirpPlaysJson()}," +
            "\"reacquireThresholdFrames\":$reacquireThresholdFrames," +
            // Echoed for the same reason the threshold above is: an artifact has to say which band
            // produced it, or a run taken at a widened band is silently compared against the
            // archive as though it were taken at the default.
            "\"trimDeadbandFrames\":$trimDeadbandFrames," +
            "\"trackBufferFrames\":$trackBufferFrames," +
            "\"minPendingFrames\":${if (minPendingFrames == Long.MAX_VALUE) "null" else minPendingFrames}," +
            "\"maxPendingFrames\":${if (maxPendingFrames == Long.MIN_VALUE) "null" else maxPendingFrames}," +
            "\"timestampQueries\":$timestampQueries,\"timestampFailures\":$timestampFailures," +
            "\"pendingRejected\":$pendingRejected,\"depthFallbacks\":$depthFallbacks," +
            "\"lowLatency\":$lowLatency,\"playbackUsage\":\"${playbackUsage.name}\"," +
            "\"spatialPeerId\":${spatialPeerId?.let { "\"$it\"" } ?: "null"}," +
            "\"spatialChunks\":$spatialChunks," +
            "\"trackProfile\":" + trackProfileJson(
                trackMinBufferBytes, trackRequestedBytes, trackBufferFrames,
                trackCapacityFrames, trackPerformanceMode, trackSampleRate, firstPendingFrames
            ) + "," +
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
         * One millisecond. Streamed chunks released later than this by the poll's own quantisation
         * are written whole and heard late, rather than shortened to stay exactly on the grid.
         *
         * Sized against what the release error actually is, not against the gate: O35 measured a
         * mean trim of 8.6 frames with the worst at 403, so a band of 48 passes the jitter through
         * untouched while still cutting a real gap back. What it costs is up to 1 ms of timing,
         * against a 5 ms gate and a drift controller that goes on correcting underneath it.
         */
        const val TRIM_DEADBAND_FRAMES = 48

        /**
         * 1.3 ms of cross-fade over the cut a trim leaves. Long enough that the blend is gradual
         * at the lowest frequencies a phone speaker reproduces, short enough to sit inside the
         * trims actually seen - O36 measured a mean of 70 frames with the deadband in place, so
         * the ramp fits whole in the typical case and clamps to the trim in the rest.
         */
        const val SPLICE_RAMP_FRAMES = 64

        /**
         * The trim deadband expressed as time, for the scheduler's early release.
         *
         * Derived rather than written out so the two stay the mirror of each other: the renderer
         * lets a chunk run this far late without cutting it, and the scheduler lets one go this
         * far early without wedging silence in front of it. Together the write position moves
         * inside a band of +/-1 ms instead of being snapped to the grid every chunk, and neither
         * side edits the waveform for jitter this small.
         */
        const val EARLY_RELEASE_NANOS = TRIM_DEADBAND_FRAMES * 1_000_000_000L / SAMPLE_RATE

        /**
         * The same mirror for a band that is not the default one.
         *
         * A caller that widens the renderer's trim band and leaves the scheduler on the default
         * early release has not widened the band - it has made it one-sided, which is the exact
         * arrangement O38 removed. Derived here so the pair cannot be set apart by accident.
         */
        fun earlyReleaseNanos(trimDeadbandFrames: Int): Long =
            trimDeadbandFrames * 1_000_000_000L / SAMPLE_RATE

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
