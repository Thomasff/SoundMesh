package com.soundmesh.probe.sync

import com.soundmesh.core.HostId
import java.io.File

/**
 * A correction this handset never measured against this peer, taken off a room round instead.
 *
 * A separate file from [StoredCalibration], and the separation is the whole point. Somebody has
 * to be able to answer "which of these phones is running on a guess" - on a screen, at a party,
 * without reading a log - and a guess written into the file a measurement lives in can never be
 * told apart from a measurement again.
 *
 * Where it comes from: a room round measures how far apart every pair stands by the half
 * difference of two recordings, and the half sum of the same two readings is how far apart that
 * pair fired. The second half was computed and thrown away until 2026-09-13. Measured that day
 * against two handsets whose constant was known the long way, it read 0.59 and 1.23 ms of
 * residual - so it agrees with the long way to about a millisecond, while a handset carrying
 * nothing at all was 36 ms out and audible.
 *
 * So: good enough to keep a room from being tens of milliseconds apart when there is no time to
 * walk to each handset, not good enough to be called a measurement. It carries no observation
 * count because nothing folds into it - [CalibrationUpdate] maintains an estimate from repeated
 * observations of the same pair, and this is one reading from a different arm. A real pair
 * calibration does not average with it; it replaces it, and [forget] is called when it does.
 */
class StoredApproximateCalibration(private val directory: File, private val peerId: String) {
    init {
        // The peer id is the file name, and it can arrive off a scanned screen. Checked here for
        // the same reason StoredCalibration checks it: this is the last point before it is a path.
        require(HostId.isValid(peerId) || peerId == StoredCalibration.ANONYMOUS_PEER) {
            "unusable peer id"
        }
    }

    /** The approximation, or null if this handset holds none for this peer. */
    fun read(): Long? {
        val file = file()
        if (!file.isFile) return null
        return runCatching { file.readText().trim().toLong() }.getOrNull()
    }

    fun write(micros: Long) {
        file().writeText(micros.toString())
    }

    /**
     * Drops it, which is what a real measurement of the same pair does.
     *
     * Deleted rather than left under a measurement that outranks it. Reading order alone would
     * be enough while both exist, but the pair flow can also drop its constant - and a forgotten
     * measurement falling back to a months-old guess is the kind of quiet that this whole file
     * exists to prevent.
     */
    fun forget() {
        file().delete()
    }

    private fun file() = File(directory, "$FILE_PREFIX$peerId")

    companion object {
        const val FILE_PREFIX = "approximate-offset-us-"
    }
}
