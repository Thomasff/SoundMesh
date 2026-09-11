package com.soundmesh.probe.sync

import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.AudioChunk
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.CalibrationSchedule
import com.soundmesh.core.CalibrationTiming
import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.WavFileReader
import java.io.File

/** One handset's half of a peer calibration, and everything a later reader needs to disbelieve it. */
data class PeerCalibrationRun(
    val readings: List<AlignmentReading>,
    val refusal: String?,
    val json: String,
    /**
     * Where every slot of the room landed, once per repeat, and empty on a pair.
     *
     * A pair fills [readings] because it can: it holds both halves of the one pair it is in.
     * A room fills this instead, and deliberately computes nothing - the pairs it would have
     * to combine are between handsets whose order it has no business deciding. See
     * [com.soundmesh.core.RoomResultMessage].
     */
    val arrivalsByRepeat: List<List<ChirpArrival?>> = emptyList()
)

/**
 * Plays one handset's part of a calibration and reads what it heard.
 *
 * This is the audio half only. The network half - fetching the plan, delivering the readings,
 * folding and storing the correction - belongs to the screen that owns the run, because that part
 * differs by role while everything here is the same on both sides but for which chirps are this
 * handset's.
 *
 * No audio stream, and none is needed. The chirp does not cross the network in the harness either:
 * each handset submits it to its own [PlaybackScheduler] stamped with a host instant, and the
 * renderer converts. What a stream is for is giving the drift loop something to lock onto, and a
 * locally generated tone does that just as well - which is the arrangement [OutputLeadRunner]
 * already uses. It takes the projection consent, the source picking, the audio focus and the chunk
 * server out of a measurement that never needed any of them.
 *
 * What that buys and what it costs are the same fact: no archived run was taken this way. All 186
 * of them had a real stream behind the chirp. The first job of this class is therefore to agree
 * with the constant the harness already measured for the pair in this room; a disagreement means
 * this arrangement is wrong rather than that something new has been found.
 */
class PeerCalibrationRunner(
    private val runStore: RunStore,
    private val caseId: String,
    private val role: CalibrationRole,
    private val plan: CalibrationPlan,
    /**
     * Which chirp of the window is this handset's, or null to take the slot the role implies.
     *
     * Told rather than derived, on both ends of the run: it is the slot the plan named this
     * handset in, and it is also the anchor the recording is read against. A pair leaves it
     * null and gets the slots the role has always meant - host last, sink first.
     */
    private val ownSlot: Int? = null,
    /** This handset's view of host time, correction included, exactly as a session holds it. */
    private val hostNanosNow: () -> Long,
    /** The clock offset in force, for the renderer's report. Zero on the host. */
    private val offsetNanosNow: () -> Long = { 0L },
    private val audioSource: CalibrationAudioSource = CalibrationAudioSource.MIC,
    /** What counts as an arrival on this arm. See [OnDeviceAlignment.readRun]. */
    private val edgeShares: List<Double> = emptyList()
) {
    private val tone = TonePcmSource()

    /**
     * What the recording threw, if it threw.
     *
     * An uncaught throw on any thread takes the whole process with it, which on hardware looks
     * like the app vanishing rather than like a calibration failing. Caught so that a recording
     * that never opened reads as a refused measurement, which is what it is.
     */
    @Volatile private var recordingFailure: String? = null

    @Volatile private var rendererReport: String? = null

    fun run(): PeerCalibrationRun {
        val timing = ownSlot?.let { CalibrationSchedule.of(plan, it, chirpNanos()) }
            ?: CalibrationSchedule.of(plan, role, chirpNanos())
        val calibration = CalibrationRunner(runStore, caseId, audioSource, hostNanosNow)
        val recording = Thread({
            runCatching {
                awaitHostInstant(timing.recordFromHostNanos)
                calibration.record(secondsUntil(timing.recordUntilHostNanos))
            }.onFailure { recordingFailure = "${it.javaClass.simpleName}: ${it.message}" }
        }, "SoundMeshPeerRecord")
        recording.start()
        play(timing)
        recording.join()
        return analyse(calibration)
    }

    /**
     * Warms a renderer on ordinary audio, lets it settle, then makes it emit this side's chirps.
     *
     * The warm-up is not decoration. The archive measured a converged renderer - every alignment
     * run put its chirp at the end of a minute of music - and a renderer handed a chirp and
     * nothing else would still be acquiring, which is a different question wearing the same units.
     */
    private fun play(timing: CalibrationTiming) {
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
            offsetNanosNow = offsetNanosNow,
            hostNanosNow = hostNanosNow
        )
        renderer.endAt(timing.recordUntilHostNanos)
        val thread = Thread({ renderer.run() }, "SoundMeshPeerRender").also { it.start() }
        warmUp(scheduler, timing)
        submitChirps(scheduler, timing)
        thread.join()
        rendererReport = renderer.report(null)
    }

    /**
     * Feeds tone chunks from the warm-up's start until the gap before the first chirp.
     *
     * Paced rather than queued in one go: the scheduler holds only [SCHEDULER_CAPACITY_CHUNKS] and
     * drops the oldest beyond that, so a whole warm-up submitted at once would throw away its own
     * beginning.
     */
    private fun warmUp(scheduler: PlaybackScheduler, timing: CalibrationTiming) {
        val chunks =
            ((timing.warmUpUntilHostNanos - timing.warmUpFromHostNanos) / SyncRenderer.CHUNK_NANOS).toInt()
        for (sequence in 0 until chunks) {
            val playAt = timing.warmUpFromHostNanos + sequence * SyncRenderer.CHUNK_NANOS
            val frameIndex = sequence.toLong() * SyncRenderer.FRAMES_PER_CHUNK
            scheduler.submit(AudioChunk(sequence, playAt, tone.fill(frameIndex, SyncRenderer.FRAMES_PER_CHUNK)))
            awaitHostInstant(playAt - SUBMIT_LEAD_NANOS)
        }
    }

    /**
     * Queues each repeat a second before its instant.
     *
     * Paced rather than submitted up front for the same reason the warm-up is, and because pacing
     * is what leaves a place to notice a recording that died: without one the handset plays its
     * whole schedule out into a quiet room nothing is listening to.
     */
    private fun submitChirps(scheduler: PlaybackScheduler, timing: CalibrationTiming) {
        val chunks = ChirpGenerator.generateStereoChunks(SyncRenderer.FRAMES_PER_CHUNK)
        timing.ownChirpAtHostNanos.forEachIndexed { repeat, at ->
            awaitHostInstant(at - SUBMIT_LEAD_NANOS)
            if (recordingFailure != null) return
            val base = SyncRenderer.CHIRP_SEQUENCE_BASE + repeat * SyncRenderer.CHIRP_REPEAT_STRIDE
            chunks.forEachIndexed { index, pcm ->
                scheduler.submit(AudioChunk(base + index, at + index * SyncRenderer.CHUNK_NANOS, pcm))
            }
        }
    }

    /**
     * Reads this handset's own recording, or says why it could not.
     *
     * Refused rather than thrown: the recording is on the device either way, and a run that played
     * correctly and could not be analysed has to be told apart from one that never played.
     */
    private fun analyse(calibration: CalibrationRunner): PeerCalibrationRun {
        recordingFailure?.let { return refused("the recording failed: $it") }
        val startedAt = calibration.startedAtHostNanos
            ?: return refused("the recording never reported when it opened")
        val recorded = runCatching {
            WavFileReader.readMono(File(runStore.prepareRun(caseId), "calibration.wav"))
        }.getOrElse { return refused("the recording could not be read: ${it.javaClass.simpleName}") }
        val startedNanos = System.nanoTime()
        ownSlot?.let { return room(recorded, startedAt, startedNanos, it) }
        val readings = runCatching {
            OnDeviceAlignment.readRun(
                recorded = recorded,
                reference = ChirpGenerator.generateMono(),
                recordingStartedAtHostNanos = startedAt,
                // The sink's instant on both sides. It is the anchor the analysis is written
                // around, and a host searching from its own staggered one would sit half a second
                // off centre on a window half a second wide.
                firstChirpAtHostNanos = plan.firstChirpAtHostNanos,
                staggerNanos = plan.staggerNanos,
                chirpRepeats = plan.repeats,
                chirpIntervalNanos = plan.intervalNanos,
                edgeShares = edgeShares
            )
        }.getOrElse { return refused("the recording could not be correlated: ${it.javaClass.simpleName}") }
        val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000
        return PeerCalibrationRun(
            readings,
            null,
            json(readings, recorded.size, startedAt, elapsedMillis, null)
        )
    }

    /**
     * The room half of [analyse]: every slot of every repeat, and nothing combined.
     *
     * Its own path rather than a branch inside the pair's, because the two produce different
     * things and the only thing they share is the recording they read.
     */
    private fun room(
        recorded: ShortArray,
        startedAt: Long,
        startedNanos: Long,
        slot: Int
    ): PeerCalibrationRun {
        val arrivals = runCatching {
            OnDeviceAlignment.readRoom(
                recorded = recorded,
                reference = ChirpGenerator.generateMono(),
                recordingStartedAtHostNanos = startedAt,
                firstChirpAtHostNanos = plan.firstChirpAtHostNanos,
                staggerNanos = plan.staggerNanos,
                slotCount = CalibrationSchedule.slotsIn(plan),
                ownSlot = slot,
                chirpRepeats = plan.repeats,
                chirpIntervalNanos = plan.intervalNanos,
                edgeShares = edgeShares
            )
        }.getOrElse { return refused("the recording could not be correlated: ${it.javaClass.simpleName}") }
        val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000
        return PeerCalibrationRun(
            emptyList(),
            null,
            roomJson(arrivals, recorded.size, startedAt, elapsedMillis, slot),
            arrivals
        )
    }

    private fun refused(reason: String) =
        PeerCalibrationRun(emptyList(), reason, json(emptyList(), 0, null, 0, reason))

    private fun json(
        readings: List<AlignmentReading>,
        frames: Int,
        startedAt: Long?,
        elapsedMillis: Long,
        refusal: String?
    ): String =
        "{\"role\":\"$role\",\"caseId\":\"${plan.caseId}\",\"hostId\":\"${plan.hostId}\"," +
            "\"repeats\":${plan.repeats},\"chirpIntervalNanos\":${plan.intervalNanos}," +
            "\"staggerNanos\":${plan.staggerNanos},\"audioSource\":\"$audioSource\"," +
            "\"recordingStartedAtHostNanos\":${startedAt ?: "null"},\"frames\":$frames," +
            "\"elapsedMillis\":$elapsedMillis," +
            "\"refusal\":${refusal?.let { "\"$it\"" } ?: "null"}," +
            "\"pairs\":[" + readings.joinToString(",") { reading ->
                "{\"firstIndex\":${reading.firstIndex ?: "null"},\"secondIndex\":${reading.secondIndex ?: "null"}," +
                    "\"alignmentErrorMs\":${reading.alignmentErrorMs ?: "null"}," +
                    "\"confidence\":\"${reading.confidence}\"," +
                    "\"ratios\":[${reading.ratios.joinToString(",") { it?.toString() ?: "null" }}]," +
                    "\"atSearchEdge\":[${reading.atSearchEdge.joinToString(",") { it?.toString() ?: "null" }}]}"
            } + "],\"renderer\":${rendererReport ?: "null"}}"

    /**
     * The room's report: every slot of every repeat, as heard here.
     *
     * Beside [json] rather than inside it because the two describe different runs. A pair's
     * report lists pairs it worked out; a room's lists arrivals it has not, and a reader handed
     * one shaped like the other would have to guess which kind of run it was looking at.
     */
    private fun roomJson(
        arrivals: List<List<ChirpArrival?>>,
        frames: Int,
        startedAt: Long?,
        elapsedMillis: Long,
        slot: Int
    ): String =
        "{\"role\":\"$role\",\"caseId\":\"${plan.caseId}\",\"hostId\":\"${plan.hostId}\"," +
            "\"repeats\":${plan.repeats},\"chirpIntervalNanos\":${plan.intervalNanos}," +
            "\"staggerNanos\":${plan.staggerNanos},\"audioSource\":\"$audioSource\"," +
            "\"slotIds\":[" + plan.slotIds.joinToString(",") { "\"$it\"" } + "]," +
            "\"ownSlot\":$slot," +
            "\"recordingStartedAtHostNanos\":${startedAt ?: "null"},\"frames\":$frames," +
            "\"elapsedMillis\":$elapsedMillis,\"refusal\":null," +
            "\"window\":[" + arrivals.joinToString(",") { repeat ->
                "[" + repeat.joinToString(",") { arrival ->
                    arrival?.let {
                        "{\"index\":${it.index},\"ratio\":${it.ratio},\"atSearchEdge\":${it.atSearchEdge}," +
                            "\"edgeIndices\":[${it.edgeIndices.joinToString(",")}]}"
                    } ?: "null"
                } + "]"
            } + "],\"renderer\":${rendererReport ?: "null"}}"

    private fun chirpNanos(): Long =
        ChirpGenerator.generateStereoChunks(SyncRenderer.FRAMES_PER_CHUNK).size * SyncRenderer.CHUNK_NANOS

    private fun secondsUntil(hostNanos: Long): Int =
        maxOf(1, ((hostNanos - hostNanosNow()) / 1_000_000_000L).toInt() + 1)

    private fun awaitHostInstant(hostNanos: Long) {
        while (true) {
            val remaining = hostNanos - hostNanosNow()
            if (remaining <= 0) return
            Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
    }

    private companion object {
        /** ~3s of audio at 20ms/chunk, matching the sessions and the harness. */
        const val SCHEDULER_CAPACITY_CHUNKS = 150

        /** How far ahead of a chunk's instant it is handed to the scheduler. */
        const val SUBMIT_LEAD_NANOS = 1_000_000_000L
    }
}
