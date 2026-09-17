package com.soundmesh.probe.sync

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.soundmesh.core.Crossover
import com.soundmesh.core.Decorrelator
import com.soundmesh.core.DistanceShelf
import com.soundmesh.core.DriftController
import com.soundmesh.core.PhaseState
import com.soundmesh.core.PlaybackDecision
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.REACQUIRE_THRESHOLD_FRAMES
import com.soundmesh.core.RendererPhase
import com.soundmesh.core.SchedulerStats
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialShaper
import com.soundmesh.core.SpectrumMix
import com.soundmesh.core.StereoGain
import com.soundmesh.core.TravellingDelay
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
 * Every field here is a thing the framework decides rather than a thing this code asks for.
 *
 * The reason they were added has since been withdrawn: twelve runs read as a run-level bias
 * sitting at one of two levels 0.87 ms apart, and eighteen runs showed one wide distribution with
 * no step in it at all. The run-level bias turned out to be the clock offset the sink converted
 * through, which is not in here and never was.
 *
 * They stay because a different two-level thing did survive, one emission below the run: an
 * emission of the X10's lands 56.05 +/- 2.26 frames early on about a third of the chirps, drawn
 * afresh each time, and the trim on the chirp's own chunk is a frame or less - so the step is in
 * the depth this renderer reads out of getTimestamp rather than anywhere it can see. Which output
 * the framework granted is the next thing that could distinguish it, and this is where a later
 * reader finds out. What is still missing is the device's native burst, which no AudioTrack
 * reports; it is read off the platform instead.
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
/**
 * Which rule a chunk heard at [playAtHostNanos] is played under.
 *
 * [waiting] is the newest rule this handset has been told about and [inForce] is the one its
 * last chunk was played under. A rule stamped with an instant is held back until a chunk that
 * is heard at or after it, so every handset in the room swaps on the same chunk however far
 * apart they were told - which is the whole of what this buys. See
 * [com.soundmesh.core.SpatialField.effectiveAtHostNanos] for what the disagreement costs and
 * why only the large changes are worth this.
 *
 * A rule whose instant has already passed is taken at once, so a handset told late lands on the
 * behaviour every handset had before this existed rather than on something worse.
 */
internal fun ruleInForce(
    inForce: SpatialField?,
    waiting: SpatialField?,
    playAtHostNanos: Long
): SpatialField? =
    if (waiting != null && playAtHostNanos >= waiting.effectiveAtHostNanos) waiting else inForce

internal fun spatialShaped(
    sequence: Int,
    playAtHostNanos: Long,
    payload: ByteArray,
    field: SpatialField?,
    peerId: String?,
    wasUnder: SpatialField? = null,
    crossover: Crossover,
    shelf: DistanceShelf,
    diffuse: Decorrelator? = null,
    travel: TravellingDelay? = null
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
        from = cameFrom(wasUnder, field, peerId, playAtHostNanos),
        fromFold = foldCameFrom(wasUnder, field, peerId),
        fromSpectrum = spectrumCameFrom(wasUnder, field, peerId),
        fromRetreat = retreatCameFrom(wasUnder, field, peerId),
        crossover = crossover,
        shelf = shelf,
        diffuse = diffuse,
        travel = travel
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
 * How much of the other channel the room was folding in a moment ago, when that is not what this
 * rule asks for.
 *
 * A second function rather than a second return value from [cameFrom] because the two answer to
 * different controls: dragging an icon moves the gain and leaves the fold where it was, dragging
 * the separation knob does the reverse. Sharing one would make each of them ramp whenever the
 * other did, which is a step at a chunk edge dressed as a smoothing.
 *
 * Zero for a handset that was playing under no rule at all, because folding in none of the other
 * channel is exactly what playing the mix unshaped is. A handset the old rule did not name is the
 * same case, for the same reason it is in [cameFrom].
 */
private fun foldCameFrom(wasUnder: SpatialField?, now: SpatialField, peerId: String): Double? {
    if (wasUnder === now) return null
    if (wasUnder == null || !wasUnder.layout.contains(peerId)) return 0.0
    return wasUnder.foldFor(peerId)
}

/**
 * How far off the room had put the source a moment ago, when that is not where this rule puts it.
 *
 * The fourth of these and the only one that does not ask the rule about a handset - the retreat is
 * one number for the whole room. It is here rather than folded into [cameFrom] for the same reason
 * the fold is: the gain it drives is already carried there, and what this is for is the **shelf**,
 * which no gain can ramp. Dragging the source outward moves both, and a step in the depth while
 * the level ramps smoothly would be the treble arriving before the loudness.
 *
 * Zero for a handset that was playing under no rule at all, because a source nobody has moved is
 * exactly where it stands. A handset the old rule did not name is the same case.
 */
private fun retreatCameFrom(wasUnder: SpatialField?, now: SpatialField, peerId: String): Double? {
    if (wasUnder === now) return null
    if (wasUnder == null || !wasUnder.layout.contains(peerId)) return 0.0
    return wasUnder.retreat
}

/**
 * How much of the mix and of its low half the room was playing a moment ago.
 *
 * The third of these, for the third control, and separate for the reason the other two are: the
 * axis can be swapped under a handset, which moves this and the fold at once and leaves the gain
 * where it stands.
 *
 * The whole mix and none of the filter for a handset that was under no rule, because playing the
 * mix unshaped is exactly that. A swap of axis therefore ramps out of one division and into the
 * other across a chunk rather than cutting between them.
 */
private fun spectrumCameFrom(wasUnder: SpatialField?, now: SpatialField, peerId: String): SpectrumMix? {
    if (wasUnder === now) return null
    if (wasUnder == null || !wasUnder.layout.contains(peerId)) return SpectrumMix(1.0, 0.0)
    return wasUnder.spectrumFor(peerId)
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
    /** The newest rule this handset has been told about, which may not be due yet. */
    @Volatile private var spatialField: SpatialField? = null

    /**
     * How long this handset holds everything back so that its sound arrives with the rest.
     *
     * Zero on every run that has no spatial rule, which is every calibration run there has ever
     * been - the chirp the alignment is measured with must land where it was told to land, and a
     * delay under it would be measured as the alignment error it exists to sit beside.
     */
    @Volatile private var arrivalDelayNanos = 0L

    /** The rule the last chunk was played under. Touched only by the render thread. */
    private var spatialInForce: SpatialField? = null
    @Volatile private var untilHostNanos = Long.MIN_VALUE
    @Volatile private var adjustments = 0
    // Which arm ran. A spatial run and a flat one are the same binary, the same log and the same
    // duration; without this the only thing that tells them apart is a pair of ears.
    @Volatile private var spatialChunks = 0

    // Which rule the last chunk was actually heard under, so a new one can be ramped away from it
    // rather than stepped into. Null means the last chunk went out unshaped - at unity - which is
    // true both before any rule arrives and for a chirp, which is never shaped.
    private var shapedUnder: SpatialField? = null

    // One filter for this handset stream, not one per chunk. It answers with what it has already
    // heard, so a fresh one at every chunk edge would restart the ringing fifty times a second.
    // Held here rather than inside the shaper because the shaper is a function of the instant and
    // this is the one thing in the path that is a function of the past.
    private val crossover = Crossover()

    // Beside the crossover and never built lazily like the two below it: this one is fed on every
    // frame whether or not it is taking anything off, so that a source dragged outward finds it
    // already holding the right few hundred microseconds instead of clicking its way up from cold.
    // At a depth of nothing it is two arithmetic operations and hands the sample straight back.
    private val shelf = DistanceShelf()

    // This handset's own, and the one thing in the path that is meant to disagree with every other
    // handset's - see Decorrelator. Built on first use rather than eagerly: a room with the knob at
    // zero should not be carrying thirty milliseconds of delay lines it never reads.
    private val diffuser by lazy { spatialPeerId?.let { Decorrelator(it, SAMPLE_RATE) } }

    // Where this handset's own output waits when the rule asks it to arrive later than it was
    // made - see TravellingDelay. Built on first use like the diffuser and, unlike it, never let
    // go of again: a delay line is the last few milliseconds of the song, so one that is dropped
    // and rebuilt when a knob passes through zero plays a gap and then plays a fragment of
    // whatever was in the old one. Once built it is stepped on every chunk, at a delay of zero
    // when nothing is asking, which hands every frame straight back.
    private val travelling by lazy { TravellingDelay(SAMPLE_RATE) }

    // Whether anything has ever asked, so a session that never turns either of them on carries no
    // delay line at all. Latched rather than read off the current rule for the reason above.
    @Volatile private var everTravelled = false

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
     * [reacquisitionsNegative] is the direction the other three throw away by being magnitudes,
     * and the two answers it separates want opposite repairs. Fallbacks all of one sign mean
     * playback keeps running off the same way from its own timeline - a rate the 1Hz, one-frame
     * TRACKING budget cannot hold, whose repair is more correction authority. Fallbacks of both
     * signs mean the loop is chasing something that is not going anywhere, and more authority
     * would only let it chase harder; the repair would be in what pendingFrames measures. The
     * fallback needs the filtered error at or beyond the threshold, so it is never zero here and
     * the split is over two buckets rather than three.
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
    @Volatile private var reacquisitionsNegative = 0
    @Volatile private var maxFilteredErrorMagnitudeFrames = 0
    /**
     * The same three questions asked of the steady state alone, where [reacquisitions] cannot
     * reach: how often the loop sits outside the deadband while TRACKING, and which way.
     *
     * [adjustments] over [driftSamples] is the ratio the limit-cycle reading rests on - 84-98% on
     * a twenty-minute run against 11-24% on a five-second one - and it is a mixture. Eighty-eight
     * percent of a long run's samples are taken while ACQUIRING, where being outside the deadband
     * is not a symptom but the definition: that is the phase working an excursion off. So the
     * headline ratio is mostly a statement about how long acquisitions last.
     * [trackingAdjustments] over [trackingSamples] asks it of the phase that is supposed to be
     * holding, which is the phase the reading is actually about.
     *
     * [trackingAdjustmentsNegative] is [reacquisitionsNegative]'s question at n in the thousands
     * instead of n in the tens, and of a different quantity: not which way an excursion blew up,
     * but which way the error sits when nothing is blowing up. The two can disagree, and that
     * combination is itself an answer - a steady state parked on one side while the fallbacks
     * fire both ways means two mechanisms at once, a slow one-way rate and a two-way step source,
     * which no single counter here can say.
     *
     * [trackingSamples] also gives the phase split of the samples directly. Reverse-solving it
     * from [driftSamples] needs ACQUIRING to sample at exactly one per chunk, which has never been
     * checked; against acquiringNanos this is the second, independent route to the same split.
     *
     * Counted where the correction is asked for rather than where applyPendingAdjust applies it,
     * because only here is the phase known. The two are near enough to one-to-one in TRACKING -
     * one request a second against fifty chances to spend it - but do not expect
     * [trackingAdjustments] and [adjustments] to reconcile exactly.
     */
    @Volatile private var trackingSamples = 0
    @Volatile private var trackingAdjustments = 0
    @Volatile private var trackingAdjustmentsNegative = 0
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
     * How loud the PCM most recently written to the track was, from 0 (silence) to 1 (full scale).
     *
     * Read by the product layer once a frame, off the audio thread - see [loudnessOf]. A volatile
     * write here is the only cost this path may spend; nothing else about the write it accompanies
     * is allowed to change for it.
     */
    @Volatile var loudness = 0f
        private set

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
        val end = if (acquisitionConvergedAtHostNanos != UNDEFINED) acquisitionConvergedAtHostNanos else playHostNanos()
        return end - start
    }

    /**
     * The host clock this handset plays against: the real one, moved back by [arrivalDelayNanos].
     *
     * One place rather than a subtraction at the release gate, and that is the whole of why it
     * exists. The gate is not the only thing that reads the clock - the trim decides how much of
     * a chunk is already past, and the drift controller compares where playback is against where
     * the timeline says it should be. Delaying at the gate alone would leave the controller
     * measuring the delay as error and quietly correcting it away, a frame at a time, until the
     * room was back where it started with nothing on screen having changed.
     */
    private fun playHostNanos(): Long = hostNanosNow() - arrivalDelayNanos

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
            while (playHostNanos() < untilHostNanos) {
                if (timelineNextHostNanos != UNDEFINED && playHostNanos() >= nextDriftCheckHostNanos) {
                    sampleDrift(track, timestamp, writtenFrames, timelineNextHostNanos)
                    nextDriftCheckHostNanos = playHostNanos() + driftIntervalNanos()
                }
                // Taken before poll on purpose: poll has already counted the chunk it returns, so
                // this is the only reading that excludes the chirp's own first chunk.
                val statsBeforePoll = scheduler.stats()
                val depthNanos = outputDepthNanos(track, timestamp, writtenFrames)
                // The instant the next frame written will be heard. Both poll's release test and
                // the trim below are asked about the same instant on purpose: poll decides whether
                // the chunk is due, the trim decides how much of it already is not.
                val heardAtHostNanos = playHostNanos() + depthNanos
                // Cumulative on the track, so the last read is the run's total. Polled here rather
                // than once at the end because the track is released before report() is called.
                runCatching { trackUnderruns = track.underrunCount }
                when (val decision = scheduler.poll(heardAtHostNanos)) {
                    is PlaybackDecision.Play -> {
                        if (timelineNextHostNanos == UNDEFINED) {
                            // First chunk pins the timeline: acquisition starts now, sampling
                            // immediately rather than waiting a full DRIFT_INTERVAL_NANOS.
                            acquisitionStartHostNanos = playHostNanos()
                            nextDriftCheckHostNanos = playHostNanos()
                        }
                        // Shaped before the trim, not after: a trim shortens what is written
                        // without moving the instant any surviving frame lands on, so frame j of
                        // this payload is heard at playAtHostNanos + j/SAMPLE_RATE either way.
                        val adjusted = applyPendingAdjust(decision.chunk.pcm)
                        // Read once: it is written from another thread, and a rule that changed
                        // between the shaping and the remembering would leave the next chunk
                        // ramping away from a rule that was never applied.
                        val rule = ruleInForce(spatialInForce, spatialField, decision.chunk.playAtHostNanos)
                        spatialInForce = rule
                        val payload = spatialShaped(
                            decision.chunk.sequence,
                            decision.chunk.playAtHostNanos,
                            adjusted,
                            rule,
                            spatialPeerId,
                            wasUnder = shapedUnder,
                            crossover = crossover,
                            shelf = shelf,
                            diffuse = diffuser,
                            travel = if (everTravelled) travelling else null
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
                        if (faded != null) {
                            loudness = loudnessOf(faded, 0, faded.size)
                            track.write(faded, 0, faded.size)
                        } else {
                            loudness = loudnessOf(payload, offset, payload.size - offset)
                            track.write(payload, offset, payload.size - offset)
                        }
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
                        loudness = 0f
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
        val errorFrames = playbackErrorFrames(playHostNanos(), pendingFrames, timelineNextHostNanos, SAMPLE_RATE)
        val decision = drift.observe(errorFrames)
        lastFilteredError = decision.filteredErrorFrames
        val magnitude = abs(decision.filteredErrorFrames)
        if (magnitude > maxFilteredErrorMagnitudeFrames) maxFilteredErrorMagnitudeFrames = magnitude
        driftSamples++
        pendingAdjustFrames = decision.adjustFrames
        val wasAcquiring = phaseState.phase == RendererPhase.ACQUIRING
        if (!wasAcquiring) {
            trackingSamples++
            if (decision.adjustFrames != 0) trackingAdjustments++
            if (decision.adjustFrames < 0) trackingAdjustmentsNegative++
        }
        phaseState = nextPhaseState(
            phaseState,
            inDeadband = decision.adjustFrames == 0,
            filteredErrorFrames = decision.filteredErrorFrames,
            reacquireThresholdFrames = reacquireThresholdFrames
        )
        val isAcquiring = phaseState.phase == RendererPhase.ACQUIRING
        if (wasAcquiring && !isAcquiring) {
            acquisitionConvergedAtHostNanos = playHostNanos()
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
            if (decision.filteredErrorFrames < 0) reacquisitionsNegative++
            acquisitionStartHostNanos = playHostNanos()
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
     * Hands the renderer a new spatial rule, to take effect on the chunk the rule names.
     *
     * When that is depends on the rule rather than on this call: see [ruleInForce]. What this
     * used to say was that nothing here decides when it takes effect because the rule is a
     * function of the host instant - which was true of the gain law and stopped being true when
     * the fold and the spectrum were added underneath it, without the sentence changing.
     *
     * Called from whichever thread the rule arrived on. Null puts the room back to flat, and
     * does not wait: flat is the room being switched off rather than moved, and holding the old
     * rule for another fraction of a second would be playing a room the listener has dismissed.
     */
    fun applySpatialField(field: SpatialField?) {
        spatialField = field
        if (field == null) spatialInForce = null
        // Read off the rule as it arrives rather than through ruleInForce, because it is not the
        // same kind of quantity as the gains that wait. The instant matters for those: two
        // handsets swapping halves of the mix a few milliseconds apart play two halves that no
        // longer add back up to it. Nothing adds up across handsets here - this one is how far
        // away this phone is, which was true before the message arrived and stays true after.
        arrivalDelayNanos =
            if (field == null || spatialPeerId == null || !field.layout.contains(spatialPeerId)) 0L
            else field.arrivalDelayNanosFor(spatialPeerId)
        // Latched here rather than when a chunk needs it, because a chunk that needs it and does
        // not have it is refused by the shaper. Arriving early costs an array write per frame and
        // no sound at all; arriving late is a rule this handset cannot play.
        if (field != null && field.movesInTime) everTravelled = true
    }

    /** How long this handset is waiting for the furthest one, in nanoseconds. */
    fun arrivalDelayNanos(): Long = arrivalDelayNanos

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
                ::playHostNanos
            )}," +
            "\"reacquisitionErrorSumFrames\":$reacquisitionErrorSumFrames," +
            "\"reacquisitionsNegative\":$reacquisitionsNegative," +
            "\"maxFilteredErrorMagnitudeFrames\":$maxFilteredErrorMagnitudeFrames," +
            "\"trackingSamples\":$trackingSamples,\"trackingAdjustments\":$trackingAdjustments," +
            "\"trackingAdjustmentsNegative\":$trackingAdjustmentsNegative," +
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
            "\"arrivalDelayNanos\":$arrivalDelayNanos," +
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
