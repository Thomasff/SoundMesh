package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.DriftController
import com.soundmesh.core.OutputLeadAnalysis
import com.soundmesh.core.OutputLeadReading
import com.soundmesh.core.OutputLeadResult
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.PlaybackUsage
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.WavFileReader
import java.io.File

/** One playback pass: a single output path, warmed up and then made to emit one chirp. */
internal data class Pass(
    val usage: PlaybackUsage,
    val startHostNanos: Long,
    val chirpAtHostNanos: Long,
    /**
     * How far this pass's renderer holds its own clock back, which is zero for a measurement and
     * the stored constant for a verification.
     */
    val leadNanos: Long = 0L
)

/** What a calibration run found, and everything a later reader needs to disbelieve it. */
data class OutputLeadRun(
    val result: OutputLeadResult,
    val readings: List<OutputLeadReading>,
    val json: String
)

/**
 * Measures how far one of this handset's output paths runs ahead of its ordinary one, using
 * nothing but this handset.
 *
 * The quantity is a difference between two of one phone's own outputs, so the phone has everything
 * it needs: it plays a chirp on each path while recording itself, and the two arrivals differ by
 * exactly what the two paths differ by. Everything else - the speaker, the microphone, the capture
 * chain's own latency, the room, the instant the recording opened - is identical for both chirps
 * and leaves in the subtraction. No second device, no network, no clock sync, no tape measure.
 *
 * Both chirps go through a real [SyncRenderer] on a real [PlaybackScheduler], warmed up first with
 * ordinary audio. That is not incidental: the renderer compensates for its own output depth out of
 * `getTimestamp`, and the number this measures is what is left after that compensation. A chirp
 * played on a private AudioTrack would measure a different quantity and store it under the same
 * name.
 *
 * One pass per chirp, one path at a time, because that is the condition the PC A/B measured: O64
 * ran a host on media alone and O65 the same host on accessibility alone. Two tracks open at once
 * is a third arrangement neither of them covers.
 *
 * The order of the two passes alternates between repeats. Anything that drifts over the seconds
 * between a pair - the recorder's own sample clock against `nanoTime`, the hardware warming - would
 * otherwise enter every repeat with the same sign and survive the median.
 */
class OutputLeadRunner(
    private val runStore: RunStore,
    private val caseId: String,
    private val subject: PlaybackUsage,
    private val repeats: Int = DEFAULT_REPEATS,
    private val warmupNanos: Long = WARMUP_NANOS,
    private val audioSource: CalibrationAudioSource = CalibrationAudioSource.MIC,
    private val minimumReadings: Int = DEFAULT_MINIMUM_READINGS,
    private val maximumSpreadMicros: Long = DEFAULT_MAXIMUM_SPREAD_MICROS,
    /**
     * A constant already measured, applied to the subject's renderer the way a session applies it.
     *
     * Zero for a measurement. Non-zero turns this into a verification: a right constant leaves
     * nothing behind, so the run should read near zero, and a constant with the wrong sign reads
     * near twice its own size. That is O65 -> O66 done on one handset, and it is the only check on
     * this number available without pairing the two phones the other way round.
     */
    private val appliedLeadNanos: Long = 0L
) {
    private val tone = TonePcmSource()

    /** One renderer report per pass, in the order the passes ran. */
    private val reports = mutableListOf<String>()

    /**
     * What the recording threw, if it threw.
     *
     * An uncaught throw on any thread takes the whole process with it, which on hardware looks
     * like the app vanishing rather than like a calibration failing - and the first run of this
     * did exactly that, on a case id the run store would not accept. Caught so that a recording
     * that never opened reads as a refused measurement, which is what it is.
     */
    @Volatile private var recordingFailure: String? = null

    fun run(): OutputLeadRun {
        require(repeats >= 1) { "a calibration needs at least one repeat" }
        require(subject != PlaybackUsage.MEDIA) { "media is the path everything else is measured against" }
        val passes = plan(System.nanoTime() + START_LEAD_NANOS)
        val calibration = CalibrationRunner(runStore, caseId, audioSource) { System.nanoTime() }
        val lastEnd = passes.last().chirpAtHostNanos + chirpNanos() + CHIRP_DRAIN_NANOS
        awaitHostInstant(passes.first().startHostNanos - RECORD_LEAD_NANOS)
        val recordSeconds = secondsUntil(lastEnd + RECORD_TAIL_NANOS)
        val recording = Thread({
            runCatching { calibration.record(recordSeconds) }
                .onFailure { recordingFailure = "${it.javaClass.simpleName}: ${it.message}" }
        }, "SoundMeshLeadRecord")
        recording.start()
        // Stopped rather than played out: without a recording every chirp after this is a minute
        // of noise in someone's quiet room that nothing will ever read.
        for (pass in passes) {
            if (recordingFailure != null) break
            play(pass)
        }
        recording.join()
        return analyse(passes, calibration)
    }

    /**
     * Every pass this run will play, laid out before any of it starts.
     *
     * Computed up front because the recording has to be told how long to stay open, and because
     * the analysis needs the instants a chirp was *asked* for rather than any instant observed
     * while it played - the whole measurement is arrival against intent.
     */
    internal fun plan(startHostNanos: Long): List<Pass> = (0 until repeats).flatMap { repeat ->
        val at = startHostNanos + repeat * repeatStrideNanos()
        // Media first on even repeats, the subject first on odd ones.
        val order = if (repeat % 2 == 0) listOf(PlaybackUsage.MEDIA, subject) else listOf(subject, PlaybackUsage.MEDIA)
        order.mapIndexed { index, usage ->
            val passStart = at + index * passStrideNanos()
            Pass(
                usage = usage,
                startHostNanos = passStart,
                chirpAtHostNanos = passStart + warmupNanos + CALIBRATION_GAP_NANOS,
                // The subject's alone. Moving both would shift the pair together and measure the
                // very same difference again, which would pass whatever the constant happened
                // to be - the correction has to be tested where the product puts it.
                leadNanos = if (usage == subject) appliedLeadNanos else 0L
            )
        }
    }

    /**
     * Warms a renderer on this pass's output, lets it settle, then makes it emit one chirp.
     *
     * The warm-up is ordinary audio through the ordinary path, and it is there because the archive
     * measured a converged renderer: O64 and O65 both put their chirp at the end of sixty seconds
     * of music. A renderer given a chirp and nothing else would still be acquiring, and would be
     * answering a different question. [OutputLeadRun.json] carries the acquisition duration so a
     * warm-up that turned out to be too short is visible rather than assumed away.
     */
    private fun play(pass: Pass) {
        val scheduler = PlaybackScheduler(
            SyncRenderer.FRAMES_PER_CHUNK,
            SCHEDULER_CAPACITY_CHUNKS,
            earlyReleaseNanos = SyncRenderer.earlyReleaseNanos(SyncRenderer.TRIM_DEADBAND_FRAMES),
            exactReleaseFromSequence = SyncRenderer.CHIRP_SEQUENCE_BASE
        )
        val renderer = SyncRenderer(
            scheduler,
            DriftController(),
            trimDeadbandFrames = SyncRenderer.TRIM_DEADBAND_FRAMES,
            playbackUsage = pass.usage
            // Held back exactly as HostSession holds it back, so a verification tests the
            // correction where the product applies it rather than somewhere equivalent.
        ) { System.nanoTime() - pass.leadNanos }
        renderer.endAt(pass.chirpAtHostNanos + chirpNanos() + CHIRP_DRAIN_NANOS)
        val thread = Thread({ renderer.run() }, "SoundMeshLeadRender").also { it.start() }
        warmUp(scheduler, pass)
        submitChirp(scheduler, pass.chirpAtHostNanos)
        thread.join()
        reports += renderer.report(null)
    }

    /**
     * Feeds tone chunks from the pass's start until the gap before its chirp.
     *
     * Paced rather than queued in one go: the scheduler holds only [SCHEDULER_CAPACITY_CHUNKS] and
     * drops the oldest beyond that, so a whole warm-up submitted at once would throw away its own
     * beginning. Paced against the pass's start rather than the previous chunk, so a slow pass is
     * absorbed instead of pushing every later chunk out by the same amount.
     */
    private fun warmUp(scheduler: PlaybackScheduler, pass: Pass) {
        val chunks = (warmupNanos / SyncRenderer.CHUNK_NANOS).toInt()
        for (sequence in 0 until chunks) {
            val playAt = pass.startHostNanos + sequence * SyncRenderer.CHUNK_NANOS
            val frameIndex = sequence.toLong() * SyncRenderer.FRAMES_PER_CHUNK
            scheduler.submit(AudioChunk(sequence, playAt, tone.fill(frameIndex, SyncRenderer.FRAMES_PER_CHUNK)))
            awaitHostInstant(playAt - SUBMIT_LEAD_NANOS)
        }
    }

    /** The same submission the harness makes, one repeat of it. */
    private fun submitChirp(scheduler: PlaybackScheduler, startHostNanos: Long) {
        ChirpGenerator.generateStereoChunks(SyncRenderer.FRAMES_PER_CHUNK).forEachIndexed { index, pcm ->
            scheduler.submit(AudioChunk(SyncRenderer.CHIRP_SEQUENCE_BASE + index, startHostNanos + index * SyncRenderer.CHUNK_NANOS, pcm))
        }
    }

    /**
     * Pairs each repeat's two chirps back up by role and reads the recording once per pair.
     *
     * A failure here is reported rather than thrown: the recording is on the device either way,
     * and a run that played correctly and could not be analysed is worth telling apart from one
     * that never played.
     */
    private fun analyse(passes: List<Pass>, calibration: CalibrationRunner): OutputLeadRun {
        recordingFailure?.let { return failed("the recording failed: $it") }
        val startedAt = calibration.startedAtHostNanos
            ?: return failed("the recording never reported when it opened")
        val recorded = runCatching { WavFileReader.readMono(File(runStore.prepareRun(caseId), "calibration.wav")) }
            .getOrElse { return failed("the recording could not be read: ${it.javaClass.simpleName}") }
        val reference = ChirpGenerator.generateMono()
        val readings = passes.chunked(2).map { pair ->
            OutputLeadAnalysis.read(
                recorded = recorded,
                reference = reference,
                recordingStartedAtHostNanos = startedAt,
                referenceChirpAtHostNanos = pair.first { it.usage == PlaybackUsage.MEDIA }.chirpAtHostNanos,
                subjectChirpAtHostNanos = pair.first { it.usage == subject }.chirpAtHostNanos
            )
        }
        val result = OutputLeadAnalysis.combine(readings, minimumReadings, maximumSpreadMicros)
        return OutputLeadRun(result, readings, json(result, readings, recorded.size, startedAt))
    }

    private fun failed(reason: String): OutputLeadRun {
        val result = OutputLeadResult(null, 0, null, reason)
        return OutputLeadRun(result, emptyList(), json(result, emptyList(), 0, null))
    }

    private fun json(result: OutputLeadResult, readings: List<OutputLeadReading>, frames: Int, startedAt: Long?): String =
        "{\"subject\":\"${subject.name}\",\"repeats\":$repeats," +
            "\"warmupNanos\":$warmupNanos,\"audioSource\":\"$audioSource\"," +
            // Which arrangement produced this: a raw measurement, or a check of a stored answer.
            "\"appliedLeadMicros\":${appliedLeadNanos / 1_000L}," +
            "\"recordingStartedAtHostNanos\":${startedAt ?: "null"},\"frames\":$frames," +
            "\"leadMicros\":${result.leadMicros ?: "null"}," +
            "\"usedReadings\":${result.usedReadings},\"spreadMicros\":${result.spreadMicros ?: "null"}," +
            "\"refusal\":${result.refusal?.let { "\"$it\"" } ?: "null"}," +
            "\"pairs\":[" + readings.joinToString(",") { reading ->
                "{\"referenceIndex\":${reading.referenceIndex ?: "null"},\"subjectIndex\":${reading.subjectIndex ?: "null"}," +
                    "\"leadMicros\":${reading.leadMicros ?: "null"},\"confidence\":\"${reading.confidence}\"," +
                    "\"ratios\":[${reading.ratios.joinToString(",") { it?.toString() ?: "null" }}]," +
                    "\"atSearchEdge\":[${reading.atSearchEdge.joinToString(",") { it?.toString() ?: "null" }}]}"
            } + "],\"passes\":[" + reports.joinToString(",") + "]}"

    private fun passStrideNanos(): Long =
        warmupNanos + CALIBRATION_GAP_NANOS + chirpNanos() + CHIRP_DRAIN_NANOS + PASS_MARGIN_NANOS

    private fun repeatStrideNanos(): Long = passStrideNanos() * 2

    private fun chirpNanos(): Long =
        ChirpGenerator.generateStereoChunks(SyncRenderer.FRAMES_PER_CHUNK).size * SyncRenderer.CHUNK_NANOS

    private fun secondsUntil(hostNanos: Long): Int =
        maxOf(1, ((hostNanos - System.nanoTime()) / 1_000_000_000L).toInt() + 1)

    private fun awaitHostInstant(hostNanos: Long) {
        while (true) {
            val remaining = hostNanos - System.nanoTime()
            if (remaining <= 0) return
            Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
    }

    companion object {
        /**
         * The case the recording is filed under, and it has to be one [RunStore] accepts:
         * `[A-Z][0-9]+`, the harness's own shape. L for lead, and a series of its own so a
         * calibration never lands on top of an archived alignment run.
         */
        const val DEFAULT_CASE_ID = "L1"

        /**
         * Five, and the median of them. Three was tried on the two-handset ruler and was not
         * enough: O60, O61 and O62 were the same binary run back to back without the phones being
         * touched, and their cluster means came out +1.323, -0.927 and +0.097 ms.
         */
        const val DEFAULT_REPEATS = 5

        /** A pair that was not heard cleanly is dropped, so most of them still have to land. */
        const val DEFAULT_MINIMUM_READINGS = 3

        /**
         * Three milliseconds of disagreement across the repeats, over which nothing is stored.
         *
         * One handset's emission moves in steps of about 56 frames, so repeats differing by a
         * millisecond or so are the mechanism working. Much wider than that and something else
         * happened in the room, and a constant averaged out of that is worse than none: the
         * product applies it silently and nobody looks at it again.
         */
        const val DEFAULT_MAXIMUM_SPREAD_MICROS = 3_000L

        /**
         * How long each pass plays ordinary audio before its chirp, so the renderer's output depth
         * compensation is settled rather than still acquiring.
         */
        const val WARMUP_NANOS = 4_000_000_000L

        /** Silence between the warm-up and the chirp, so the tone is not in the correlation. */
        const val CALIBRATION_GAP_NANOS = 1_500_000_000L

        /** Long enough for the chirp to have left the output buffer before the track is closed. */
        const val CHIRP_DRAIN_NANOS = 1_000_000_000L

        /** Slack between passes for one AudioTrack to be released and the next one built. */
        const val PASS_MARGIN_NANOS = 500_000_000L

        /** ~3s of audio at 20ms/chunk, matching the sessions. */
        const val SCHEDULER_CAPACITY_CHUNKS = 150

        /** How far ahead of a chunk's instant it is handed to the scheduler. */
        private const val SUBMIT_LEAD_NANOS = 1_000_000_000L

        private const val START_LEAD_NANOS = 2_000_000_000L
        private const val RECORD_LEAD_NANOS = 1_000_000_000L
        private const val RECORD_TAIL_NANOS = 2_000_000_000L
    }
}
