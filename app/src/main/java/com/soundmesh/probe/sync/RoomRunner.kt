package com.soundmesh.probe.sync

import android.util.Log
import com.soundmesh.core.AudioChunk
import com.soundmesh.core.CalibrationSchedule
import com.soundmesh.core.CalibrationTiming
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.RoomGrid
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.PlaybackUsage
import com.soundmesh.probe.RunStore
import java.io.File

/**
 * Lends this handset to another machine as a microphone in the room.
 *
 * The other machine can put a sound out at a stated instant and has no way to hear when the air
 * moved - a laptop's own capture endpoint is a different device with its own unknown delay, and
 * on the machine this was written for it returns silence. So the handset records the room, and
 * the measurement is read out of that recording afterwards on the PC.
 *
 * A recording on its own is not enough, and that is the whole reason this class plays anything.
 * [CalibrationRunner.startedAtHostNanos] is accurate to about half a second and its own KDoc says
 * it is a search hint and never an input to a measurement, so a recording carrying only the other
 * machine's chirp is pinned to the host clock no better than that. This handset therefore puts
 * chirps of its own into the same recording at instants it chose. Each arrival is an anchor:
 * everything between a pair of arrivals is measured in samples, which is exact, and the handset's
 * own output delay and the few centimetres from its speaker to its microphone enter as a constant.
 *
 * That constant is never measured here and does not have to be. The other machine records the same
 * exchange, so between the two recordings everything that scales with the distance between them
 * leaves the answer: see RoomPair on the PC side for the arithmetic and for the part that does not
 * leave. Nothing on this side has to know which of the two arrangements it is feeding.
 *
 * **How the two machines agree on an instant without talking.** Both hang their chirps on the same
 * ten-second grid of the host clock: this handset emits on a grid instant, the PC has the offset
 * from the clock exchange and so knows the same grid, and emits [RoomGrid.PEER_SLOT_NANOS] after
 * it. No command channel, no new wire format, and the two chirps cannot land on top of each other.
 *
 * **Why the pair repeats.** A single exchange cannot answer this. Measured 2026-09-21: five runs
 * at one placement with nothing touched between them put the pair constant on two levels 1.3 ms
 * apart, and the clock and the PC's own loop were both ruled out, which leaves the emission - a
 * step this handset has been measured to take before. Emission jitter enters both recordings with
 * the same sign, so it lands in the half sum, which is exactly the quantity wanted; the half
 * difference cannot carry it and held to 4.6 cm across the same five. So the pair runs
 * [RoomGrid.REPEATS] times in one window and the answer is a cluster, which is what the shipped
 * pair flow has always done.
 *
 * The emission mirrors [OutputLeadRunner.play] - a real [SyncRenderer] on a real
 * [PlaybackScheduler], warmed up with ordinary audio first, so the chirp travels the scheduling,
 * output-depth and drift path the music does. It is written out again here rather than shared
 * because the two runs answer different questions and produce different files, and the calibration
 * path is a measured, archived arrangement that a harness mode has no business reshaping.
 */
class RoomRunner(
    private val runStore: RunStore,
    private val caseId: String,
    private val audioSource: CalibrationAudioSource = CalibrationAudioSource.MIC
) {
    private val tone = TonePcmSource()

    @Volatile private var recordingFailure: String? = null

    /** The instant this handset put its own chirp out on, once [run] has chosen it. */
    @Volatile
    var chirpAtHostNanos: Long = 0L
        private set

    fun run(): String {
        val grid = RoomGrid.nextInstant(System.nanoTime() + START_LEAD_NANOS)
        chirpAtHostNanos = grid
        // Laid out by the schedule the pair flow already runs on, rather than by arithmetic of its
        // own. Both machines build this from the one instant they share, so the PC's chirps are
        // this plan's other slot rather than a formula written out twice - and the repeats, which
        // are the whole point of this pass, come from a constant neither side can hold alone.
        val timing = CalibrationSchedule.of(RoomGrid.planFor(caseId, grid), HANDSET_SLOT, chirpNanos())
        // Written the moment it is chosen, because the PC needs it before the run ends rather than
        // after: it has to have scheduled its own chirp by the time this one plays, and the json
        // is not written until everything is over.
        //
        // A file rather than logcat, which is how this was first done. The handset it was written
        // against drops every line an app logs - 7370 lines of system log over the run and not one
        // of the app's - so the channel worked where it was written and carried nothing here, with
        // no error anywhere. A stale one from an earlier run under the same case id reads as an
        // instant in the past, which is what the PC's own lead check already refuses.
        File(runStore.prepareRun(caseId), GRID_FILE).writeText(grid.toString())
        Log.i(
            TAG,
            "ROOM grid=$grid peerSlotNanos=${RoomGrid.PEER_SLOT_NANOS} " +
                "repeats=${RoomGrid.REPEATS} repeatNanos=${RoomGrid.REPEAT_NANOS}"
        )

        val calibration = CalibrationRunner(runStore, caseId, audioSource) { System.nanoTime() }
        awaitHostInstant(timing.recordFromHostNanos - RECORD_LEAD_NANOS)
        val recordSeconds = secondsUntil(timing.recordUntilHostNanos)
        val recording = Thread({
            runCatching { calibration.record(recordSeconds) }
                .onFailure { recordingFailure = "${it.javaClass.simpleName}: ${it.message}" }
        }, "SoundMeshRoomRecord")
        recording.start()

        // Asked after the recorder has had its chance to open, not the instant the thread was
        // started: a check taken immediately would race the open and always read "fine". Playing
        // to a recording that never opened is several seconds of noise in somebody's quiet room
        // that nothing will ever read, and it looks exactly like a run that worked.
        awaitRecorder(calibration)
        val report = if (recordingFailure == null) play(timing) else null
        recording.join()

        // Deliberately no correlating on this side. The recording holds two chirps whose meaning
        // is split across two machines, and only the PC has the other machine's instant and the
        // clock offset that relates the two. A number computed here would be one the handset
        // cannot check.
        return "\"room\":{\"chirpAtHostNanos\":$grid,\"peerSlotNanos\":${RoomGrid.PEER_SLOT_NANOS}," +
            "\"repeats\":${RoomGrid.REPEATS},\"repeatNanos\":${RoomGrid.REPEAT_NANOS}," +
            "\"gridNanos\":${RoomGrid.GRID_NANOS},\"sampleRate\":${ChirpGenerator.SAMPLE_RATE}," +
            "\"audioSource\":\"$audioSource\"," +
            "\"openedSource\":${calibration.openedSource?.let { "\"$it\"" } ?: "null"}," +
            "\"recordingStartedAtHostNanos\":${calibration.startedAtHostNanos ?: "null"}," +
            "\"recordingSeconds\":$recordSeconds," +
            "\"recordingFailure\":${recordingFailure?.let { "\"$it\"" } ?: "null"}," +
            "\"pass\":${report ?: "null"}}"
    }

    /** Warms a renderer on the ordinary output, lets it settle, then emits this side's chirps. */
    private fun play(timing: CalibrationTiming): String {
        val scheduler = PlaybackScheduler(
            SyncRenderer.FRAMES_PER_CHUNK,
            OutputLeadRunner.SCHEDULER_CAPACITY_CHUNKS,
            earlyReleaseNanos = SyncRenderer.earlyReleaseNanos(SyncRenderer.TRIM_DEADBAND_FRAMES),
            exactReleaseFromSequence = SyncRenderer.CHIRP_SEQUENCE_BASE
        )
        val renderer = SyncRenderer(
            scheduler,
            DriftController(),
            trimDeadbandFrames = SyncRenderer.TRIM_DEADBAND_FRAMES,
            playbackUsage = PlaybackUsage.MEDIA
        ) { System.nanoTime() }
        renderer.endAt(timing.ownChirpAtHostNanos.last() + chirpNanos() + CHIRP_DRAIN_NANOS)
        val thread = Thread({ renderer.run() }, "SoundMeshRoomRender").also { it.start() }
        warmUp(scheduler, timing)
        submitChirps(scheduler, timing)
        thread.join()
        return renderer.report(null)
    }

    /**
     * Queues each repeat a second before its instant, the way [PeerCalibrationRunner] does.
     *
     * Paced rather than submitted up front for the reason the warm-up is paced, and each repeat
     * gets its own stretch of sequence numbers so the renderer's exact-release rule applies to
     * every one of them rather than only to the first.
     *
     * The gaps between repeats are longer than anything this renderer is fed in ordinary use, and
     * that is not new ground: the shipped pair flow spaces its five repeats five seconds apart
     * through the same code, and these sit closer than that.
     */
    private fun submitChirps(scheduler: PlaybackScheduler, timing: CalibrationTiming) {
        val chunks = ChirpGenerator.generateStereoChunks(SyncRenderer.FRAMES_PER_CHUNK)
        timing.ownChirpAtHostNanos.forEachIndexed { repeat, at ->
            awaitHostInstant(at - SUBMIT_LEAD_NANOS)
            // Stops feeding a room nothing is recording, rather than playing the rest of a
            // schedule out into it - the same check the pass already makes before it starts.
            if (recordingFailure != null) return
            val base = SyncRenderer.CHIRP_SEQUENCE_BASE + repeat * SyncRenderer.CHIRP_REPEAT_STRIDE
            chunks.forEachIndexed { index, pcm ->
                scheduler.submit(AudioChunk(base + index, at + index * SyncRenderer.CHUNK_NANOS, pcm))
            }
        }
    }

    /**
     * Feeds tone chunks from [passStart] until the gap before the chirp.
     *
     * Paced rather than queued in one go, for the reason [OutputLeadRunner.warmUp] is: the
     * scheduler holds a fixed number of chunks and drops the oldest past it, so a warm-up
     * submitted all at once would throw away its own beginning.
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
     * Waits until the recorder has either opened or failed, and no longer than the lead the
     * schedule already leaves before the first sound.
     *
     * Bounded rather than open: a recorder that neither opens nor throws would otherwise hold the
     * whole run still, and a run that stands silent is the one failure nobody reads as a failure.
     */
    private fun awaitRecorder(calibration: CalibrationRunner) {
        val deadline = System.nanoTime() + RECORD_LEAD_NANOS
        while (System.nanoTime() < deadline) {
            if (calibration.startedAtHostNanos != null || recordingFailure != null) return
            Thread.sleep(5L)
        }
    }

    private fun chirpNanos(): Long =
        ChirpGenerator.generateStereoChunks(SyncRenderer.FRAMES_PER_CHUNK).size * SyncRenderer.CHUNK_NANOS

    private fun secondsUntil(hostNanos: Long): Int =
        maxOf(1, ((hostNanos - System.nanoTime()) / 1_000_000_000L).toInt() + 1)

    private fun awaitHostInstant(hostNanos: Long) {
        while (true) {
            val remaining = hostNanos - System.nanoTime()
            if (remaining <= 0) return
            Thread.sleep(minOf(remaining / 1_000_000L, 50L).coerceAtLeast(1L))
        }
    }

    companion object {
        /** Where the chosen instant is left for the PC to read, inside the run's own directory. */
        const val GRID_FILE = "grid.txt"

        /** This side chirps first, which is slot zero of the plan both machines lay out. */
        const val HANDSET_SLOT = 0

        private const val TAG = "SoundMeshRoom"

        /**
         * How far ahead the grid instant is chosen.
         *
         * It has to cover everything the PC does after this handset has picked: a person reading
         * the instant off logcat, launching the PC side, and its clock exchange running long
         * enough to fill the estimator's window. Generous on purpose - the cost of a lead that is
         * too long is a few seconds of waiting, and the cost of one that is too short is a run
         * where the PC's chirp is not in the recording at all.
         */
        const val START_LEAD_NANOS = 30_000_000_000L

        private const val CHIRP_DRAIN_NANOS = OutputLeadRunner.CHIRP_DRAIN_NANOS
        private const val SUBMIT_LEAD_NANOS = 1_000_000_000L
        private const val RECORD_LEAD_NANOS = 1_000_000_000L
    }
}
