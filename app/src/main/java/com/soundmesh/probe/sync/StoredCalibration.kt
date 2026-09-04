package com.soundmesh.probe.sync

import java.io.File

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
 * be inventing the wrong one.
 */
class StoredCalibration(private val directory: File) {
    /** The correction in microseconds, or null if this handset has never been calibrated. */
    fun read(): Long? {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return null
        // Whole or nothing: a half written file read as a correction would be applied to every
        // emission of the run, which is worse than having no correction at all.
        return runCatching { file.readText().trim().toLong() }.getOrNull()
    }

    fun write(micros: Long) {
        File(directory, FILE_NAME).writeText(micros.toString())
    }

    companion object {
        const val FILE_NAME = "calibration-offset-us"
    }
}
