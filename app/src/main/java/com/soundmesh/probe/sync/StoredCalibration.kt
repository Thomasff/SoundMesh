package com.soundmesh.probe.sync

import com.soundmesh.core.HostId
import java.io.File

/**
 * The standing alignment correction, and how many runs it is the mean of.
 *
 * The count is not bookkeeping. It is the gain of the loop that maintains this value - see
 * `CalibrationUpdate.fold` - so a correction that arrived without one cannot be averaged into.
 */
data class Calibration(val micros: Long, val observations: Int)

/**
 * The standing alignment correction this handset carries between runs, per peer.
 *
 * Until recently it lived on a command line: a person read `alignmentErrorMs` out of one run's
 * output and typed it into the next run's `--alignment-offset-ms`. That was the last step of the
 * calibration that still needed a PC and a human, and a pair of unfamiliar handsets - which have
 * no such constant anywhere - could not be calibrated at all without one.
 *
 * One file per peer, because the correction is a property of the pair rather than of the handset.
 * There was no peer identity to key it on until the host started advertising one, so until then
 * there was a single file and a phone that played with two partners would quietly average them
 * together. Averaging made that worse rather than better: a long history against one partner is
 * exactly the state that is slowest to notice a different one.
 *
 * The file the un-keyed version wrote is left where it is and never read. Deciding which peer it
 * belonged to is the same silent guess this loop refuses everywhere else, and the cost of not
 * guessing is one run - the loop takes the first measurement whole.
 */
class StoredCalibration(private val directory: File, private val peerId: String) {
    init {
        // The peer id can arrive off a scanned screen, and it is the file name. Checked here as
        // well as at the decoder, because this is the last point before it becomes a path.
        require(HostId.isValid(peerId) || peerId == ANONYMOUS_PEER) { "unusable peer id" }
    }

    /** The correction and its history, or null if this handset has never been calibrated here. */
    fun read(): Calibration? {
        val file = file()
        if (!file.isFile) return null
        // Whole or nothing: a half written file read as a correction would be applied to every
        // emission of the run, which is worse than having no correction at all.
        return runCatching {
            val fields = file.readText().trim().split(" ")
            require(fields.size == 2) { "unreadable calibration: ${fields.size} fields" }
            Calibration(fields[0].toLong(), fields[1].toInt())
        }.getOrNull()
    }

    fun write(micros: Long, observations: Int) {
        file().writeText("$micros $observations")
    }

    private fun file() = File(directory, "$FILE_PREFIX$peerId")

    companion object {
        const val FILE_PREFIX = "calibration-offset-us-"

        /**
         * Where a run keeps its correction when it was handed an address instead of finding a host.
         *
         * That path never learns who it connected to, so it has nothing to file under. Named rather
         * than left to share one peer's file, so a report that says `anonymous` says exactly what
         * happened: this correction is not attached to anybody.
         */
        const val ANONYMOUS_PEER = "anonymous"
    }
}
