package com.soundmesh.probe.sync

import java.io.File

/**
 * One file per calibration attempt, named by when it happened, and never written over.
 *
 * [com.soundmesh.probe.RunStore] files a run under its case id, and a case id names a directory
 * rather than a run: it only ever mkdirs, so a second run of the same case overwrites the first
 * where it stands. In place, which is the part that hides it - the directory's own mtime does not
 * move, so a listing afterwards shows the day of the run that was overwritten and nothing at all
 * says the later ones happened. Four runs on 2026-09-08 went into one C91 and only the last
 * survived, under a directory still dated two days earlier.
 *
 * Additive on purpose. The case directory keeps working exactly as it did, recording and all; this
 * holds a second copy of the one artifact that is the evidence, under a name no later run can
 * claim. And it is the only place a refused run can go: a run the link gate turns away never
 * reaches the chirps, so it has no case directory, and until this existed every measurement of a
 * link too slow to align on was discarded at the moment it was made - the gate throwing away the
 * only data that could say where the gate belongs.
 */
class PeerRunLog(private val filesDir: File) {

    /**
     * Files [json] under [label] and answers where it went.
     *
     * [atMillis] is the wall clock rather than [System.nanoTime]: the name is read by a person over
     * adb, and a monotonic count from an unspecified epoch names nothing. The cost is that a clock
     * that steps back can land on a name already taken, so the name is looked for rather than
     * assumed - which is the whole point of this class, and would be a poor thing to reintroduce
     * in its own file names.
     */
    fun write(label: String, json: String, atMillis: Long): File {
        require(label.isNotEmpty() && label.length <= MAX_LABEL_LENGTH && label.all { it.isNameSafe() }) {
            "a label becomes a file name, so it may only be ASCII letters, digits and dashes: $label"
        }
        val directory = File(filesDir, DIRECTORY)
        require(directory.isDirectory || directory.mkdirs()) {
            "unable to create the peer run directory"
        }
        return unclaimedFile(directory, label, atMillis).also { it.writeText(json) }.also { trim(directory) }
    }

    /** Every attempt filed so far, oldest name first. Empty before the first one. */
    fun filed(): List<File> =
        File(filesDir, DIRECTORY).listFiles()?.sortedBy { it.name } ?: emptyList()

    /**
     * Drops the oldest attempts once there are more than [MOST_FILES] of them.
     *
     * Run after the write rather than before it, so the count being capped is the count that will
     * be on the disk: trimming first leaves the directory one over every time, and the one file
     * the caller is about to be handed is the newest, so it is never among those taken.
     *
     * A failure is swallowed for the same reason the whole class is best-effort - an attempt that
     * was measured and filed is not worth throwing away because the housekeeping after it could
     * not run.
     */
    private fun trim(directory: File) {
        runCatching {
            val filed = directory.listFiles()?.sortedBy { it.name } ?: return
            if (filed.size <= MOST_FILES) return
            for (old in filed.take(filed.size - MOST_FILES)) old.delete()
        }
    }

    private fun unclaimedFile(directory: File, label: String, atMillis: Long): File {
        var candidate = File(directory, "$atMillis-$label.json")
        var attempt = 2
        while (candidate.exists()) {
            candidate = File(directory, "$atMillis-$label-$attempt.json")
            attempt++
        }
        return candidate
    }

    private fun Char.isNameSafe(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-'

    companion object {
        const val DIRECTORY = "peer-runs"

        /**
         * Bounded because nothing else ever deletes these and a handset calibrates for as long as
         * somebody owns it. At a few kilobytes an attempt this is under a megabyte, and it is far
         * more than the recent end anybody reads: a fortnight of building this, calibrating many
         * times a day, filed 334.
         */
        const val MOST_FILES = 200

        /** Long enough for a role and a case id, short enough that the timestamp still reads. */
        /**
         * Long enough for a role, a case and a sixteen-character peer name with dashes between.
         *
         * It was 32, which fits a label naming only what kind of run it was. Once a host serves
         * more than one sink the peer's name belongs in the label too, and a cap that silently
         * refused it would send the signature back to the JSON body where a directory listing
         * cannot see it.
         */
        const val MAX_LABEL_LENGTH = 48
    }
}
