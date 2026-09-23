package com.soundmesh.probe.sync

import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.CalibrationWindow
import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpGenerator

/**
 * Reads a finished calibration recording into what the schedule says is in it - one alignment
 * reading per chirp pair, or one arrival per handset in a room - on the handset that made it.
 *
 * This is what a cable and a PC used to do. The analysis itself is the ported one in
 * AlignmentAnalysis; what belongs here is the schedule - a run puts several pairs in one file, and
 * the instants they were queued for are the only thing that says which stretch of the recording
 * holds which pair.
 *
 * Every reading is taken with no distance correction, because a reading is half a measurement.
 * The two handsets record the same chirps and the air enters their readings with opposite signs,
 * so the flight time cancels when the pair is combined - and the single-sided correction, which
 * assumes the host's geometry, would be applied to the sink's recording with the wrong sign.
 */
object OnDeviceAlignment {
    /**
     * Both chirps of a pair belong to the same emission, so the schedule places both. The stagger
     * is a fixed part of the protocol rather than something measured, and it is what tells the two
     * apart; whatever is left over after subtracting it is the error.
     */
    fun readRun(
        recorded: ShortArray,
        reference: ShortArray,
        recordingStartedAtHostNanos: Long,
        firstChirpAtHostNanos: Long,
        staggerNanos: Long,
        chirpRepeats: Int,
        chirpIntervalNanos: Long,
        uncertaintyFrames: Int = CalibrationWindow.DEFAULT_UNCERTAINTY_FRAMES,
        /**
         * What counts as an arrival, or empty to take the loudest one.
         *
         * Handed in rather than decided here because it is an arm of the protocol, not a property
         * of a recording: the arms that align are read the way every archived run of them was
         * read, and only the arm whose answer is a distance reads the first arrival instead.
         */
        edgeShares: List<Double> = emptyList()
    ): List<AlignmentReading> = (0 until maxOf(chirpRepeats, 1)).map { pair ->
        val from = firstChirpAtHostNanos + pair * chirpIntervalNanos
        val window = CalibrationWindow.searchWindow(
            recordingStartedAtHostNanos = recordingStartedAtHostNanos,
            fromHostNanos = from,
            toHostNanos = from + staggerNanos,
            uncertaintyFrames = uncertaintyFrames
        )
        AlignmentAnalysis.read(
            recorded = recorded,
            reference = reference,
            staggerFrames = (staggerNanos * ChirpGenerator.SAMPLE_RATE / 1_000_000_000L).toInt(),
            searchRadiusFrames = SEARCH_RADIUS_FRAMES,
            separationMetres = 0.0,
            searchFrom = window.first,
            searchTo = window.last,
            edgeShares = edgeShares
        )
    }

    /**
     * The same schedule read a room at a time: every slot's chirp in one window, once per repeat.
     *
     * [readRun] places two chirps because a pair's window holds two. A room's window holds one per
     * handset, spaced by the same stagger, and all of them are read off the same instant - which is
     * what a round of pairs cannot do: two rounds are two snapshots with the clocks drifting
     * between them, and the pair between two sinks never comes up in a host-centred round at all.
     *
     * The anchor is this device's own chirp, looked for in its own slot only - within
     * [uncertaintyFrames] of where the opening instant puts it - and the rest are a known number of
     * stagger lengths from it. It used to be the loudest arrival in the whole window, on the grounds
     * that a handset's speaker is centimetres from its microphone. A computer's is not: its own
     * chirp came back the quietest of three, the loudest was the host lying beside it, and every
     * slot was read a stagger out (09-23). Two handsets lying side by side had done the same. The
     * opening instant is good to far better than one slot: a handset's landed within 163 ms of it
     * over every room round archived, a computer's within 60.
     *
     * Returns one list per repeat, each as long as the room. Nothing is combined here - a handset
     * has no business deciding which of two other handsets chirped first, and getting that backwards
     * answers a pair with every distance inside out. See [com.soundmesh.core.RoomResultMessage].
     */
    fun readRoom(
        recorded: ShortArray,
        reference: ShortArray,
        recordingStartedAtHostNanos: Long,
        firstChirpAtHostNanos: Long,
        staggerNanos: Long,
        slotCount: Int,
        ownSlot: Int,
        chirpRepeats: Int,
        chirpIntervalNanos: Long,
        uncertaintyFrames: Int = SEARCH_RADIUS_FRAMES,
        edgeShares: List<Double> = emptyList()
    ): List<List<ChirpArrival?>> = (0 until maxOf(chirpRepeats, 1)).map { repeat ->
        val own = firstChirpAtHostNanos + repeat * chirpIntervalNanos + ownSlot * staggerNanos
        // Narrower than a slot, or a neighbour sits inside this window and can win it by being louder.
        require(uncertaintyFrames < staggerNanos * ChirpGenerator.SAMPLE_RATE / 1_000_000_000L) {
            "uncertaintyFrames must be smaller than a slot, or a neighbour can be taken for this device's own chirp"
        }
        val window = CalibrationWindow.searchWindow(
            recordingStartedAtHostNanos = recordingStartedAtHostNanos,
            fromHostNanos = own,
            toHostNanos = own,
            uncertaintyFrames = uncertaintyFrames
        )
        AlignmentAnalysis.readSlots(
            recorded = recorded,
            reference = reference,
            ownSlot = ownSlot,
            slotCount = slotCount,
            slotFrames = (staggerNanos * ChirpGenerator.SAMPLE_RATE / 1_000_000_000L).toInt(),
            searchRadiusFrames = SEARCH_RADIUS_FRAMES,
            searchFrom = window.first,
            searchTo = window.last,
            edgeShares = edgeShares
        )
    }

    /**
     * How far either side of the first chirp its partner is looked for. Matches SEARCH_RADIUS_FRAMES
     * in run-sync.mjs, and the reason is the same: one handset's output buffer measured 210.7 ms,
     * so a window of 100 ms puts a partner on a differing model outside it - and a chirp just past
     * the edge is worse than a miss, because the partial overlap left on the boundary can still
     * clear the confidence ratio. Still well under the 24000 frame stagger, which the analysis
     * requires so that the two chirps stay tellable apart.
     */
    const val SEARCH_RADIUS_FRAMES = 12000
}
