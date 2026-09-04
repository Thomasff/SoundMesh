package com.soundmesh.probe.sync

import java.io.File

/**
 * The standing alignment correction, and how many runs it is the mean of.
 *
 * The count is not bookkeeping. It is the gain of the loop that maintains this value - see
 * `CalibrationUpdate.fold` - so a correction that arrived without one cannot be averaged into.
 */
data class Calibration(val micros: Long, val observations: Int)

/**
 * The standing alignment correction this handset carries between runs.
 *
 * Until now it lived on a command line: a person read `alignmentErrorMs` out of one run's output
 * and typed it into the next run's `--alignment-offset-ms`. That is the last step of the
 * calibration that still needed a PC and a human, and a pair of unfamiliar handsets - which have
 * no such constant anywhere - could not be calibrated at all without one.
 *
 * One value, not one per peer. The correction is a property of the pair rather than of the
 * handset, so a phone that plays with two different partners will eventually need one apiece -
 * but there is no peer identity to key it on until discovery exists, and inventing one now would
 * be inventing the wrong one. Averaging makes this more pressing rather than less: a long history
 * against one partner is exactly what would be slow to notice a different one.
 */
class StoredCalibration(private val directory: File) {
    /** The correction and its history, or null if this handset has never been calibrated. */
    fun read(): Calibration? {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return null
        // Whole or nothing: a half written file read as a correction would be applied to every
        // emission of the run, which is worse than having no correction at all.
        return runCatching {
            val fields = file.readText().trim().split(" ")
            when (fields.size) {
                // Written before the loop averaged anything, when the file held the offset alone.
                1 -> Calibration(fields[0].toLong(), 1)
                2 -> Calibration(fields[0].toLong(), fields[1].toInt())
                else -> throw IllegalArgumentException("unreadable calibration: ${fields.size} fields")
            }
        }.getOrNull()
    }

    fun write(micros: Long, observations: Int) {
        File(directory, FILE_NAME).writeText("$micros $observations")
    }

    companion object {
        const val FILE_NAME = "calibration-offset-us"
    }
}
