package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.probe.RunStore

/**
 * A round's sound on a handset: the scheduler and renderer the round has always played through.
 *
 * Lifted out of [PeerCalibrationRunner] when that moved to core, argument for argument - the chirp
 * travels the same scheduling, output depth compensation and drift correction the music does, and
 * that path is what the constants in the archive were measured through.
 */
class HandsetRoundSpeaker(
    offsetNanosNow: () -> Long,
    hostNanosNow: () -> Long
) : RoundSpeaker {
    private val scheduler = PlaybackScheduler(
        SyncRenderer.FRAMES_PER_CHUNK,
        SCHEDULER_CAPACITY_CHUNKS,
        earlyReleaseNanos = SyncRenderer.earlyReleaseNanos(SyncRenderer.TRIM_DEADBAND_FRAMES),
        exactReleaseFromSequence = SyncRenderer.CHIRP_SEQUENCE_BASE
    )
    private val renderer = SyncRenderer(
        scheduler,
        DriftController(),
        trimDeadbandFrames = SyncRenderer.TRIM_DEADBAND_FRAMES,
        offsetNanosNow = offsetNanosNow,
        hostNanosNow = hostNanosNow
    )
    private var thread: Thread? = null

    override fun start(endAtHostNanos: Long) {
        renderer.endAt(endAtHostNanos)
        thread = Thread({ renderer.run() }, "SoundMeshPeerRender").also { it.start() }
    }

    override fun submit(chunk: AudioChunk) {
        scheduler.submit(chunk)
    }

    override fun stopNow() = renderer.stopNow()

    override fun finish(): String? {
        thread?.join()
        return renderer.report(null)
    }

    private companion object {
        /** ~3s of audio at 20ms/chunk, matching the sessions and the harness. */
        const val SCHEDULER_CAPACITY_CHUNKS = 150
    }
}

/**
 * A [PeerCalibrationRunner] on this handset's own audio, with the arguments it always took.
 *
 * Every screen that runs a round builds it through here, so the handset's renderer and recorder
 * are chosen in one place and the runner in core never has to know about either.
 */
fun handsetPeerCalibrationRunner(
    runStore: RunStore,
    caseId: String,
    role: CalibrationRole,
    plan: CalibrationPlan,
    ownSlot: Int? = null,
    hostNanosNow: () -> Long,
    offsetNanosNow: () -> Long = { 0L },
    audioSource: CalibrationAudioSource = CalibrationAudioSource.MIC,
    edgeShares: List<Double> = emptyList(),
    calledOff: () -> Boolean = { false },
    keepsRecording: Boolean = true
): PeerCalibrationRunner = PeerCalibrationRunner(
    runStore = runStore,
    caseId = caseId,
    role = role,
    plan = plan,
    ownSlot = ownSlot,
    hostNanosNow = hostNanosNow,
    offsetNanosNow = offsetNanosNow,
    audioSource = audioSource.name,
    edgeShares = edgeShares,
    calledOff = calledOff,
    keepsRecording = keepsRecording,
    recorder = CalibrationRunner(runStore, caseId, audioSource, hostNanosNow),
    speaker = { HandsetRoundSpeaker(offsetNanosNow, hostNanosNow) }
)
