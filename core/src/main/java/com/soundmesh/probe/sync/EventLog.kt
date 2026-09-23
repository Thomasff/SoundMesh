package com.soundmesh.probe.sync

import java.io.File

/**
 * What this handset did, one line each, kept across sessions, restarts and reinstalls of nothing.
 *
 * Written because logcat cannot do this job on the handsets this runs on. On 09-13 the whole of
 * what logcat still held for this package was ninety seconds, and every line of it belonged to the
 * ROM's own services rather than to this app - the app's own lines had been pushed out by a system
 * logging dozens of lines a minute about window orientation. The events being chased happen once
 * in an evening, and are asked about ten minutes later.
 *
 * What survived that day and answered the question was the per-run report: a file, in this app's
 * own directory, named so nothing overwrites it. This is the same idea applied to the things that
 * are not runs - a role being chosen, a handset standing by or going away, a gathering window
 * opening and closing on the wrong number of handsets, a port that was still held. Those are
 * exactly the events a listener cannot reconstruct afterwards, and on 09-13 a listener said so:
 * "事情发生得太多了，我都无法准确记得过程中发生了什么".
 *
 * One timeline rather than one file per subject. Which order two things happened in is most of
 * what makes a sequence explicable, and two files cannot say.
 */
class EventLog(private val filesDir: File) {

    /**
     * Files one event. [what] is one short line of plain text, and the wall clock is added here:
     * the reader is a person asking what happened at ten past one.
     */
    fun write(what: String, atMillis: Long = System.currentTimeMillis()) {
        val file = File(filesDir, FILE_NAME)
        runCatching {
            val line = "$atMillis ${what.replace('\n', ' ').trim()}"
            val kept = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
            val all = kept + line
            // Trimmed from the front: what is being asked about is always the recent end.
            if (all.size > MOST_LINES) {
                file.writeText(all.takeLast(MOST_LINES).joinToString("\n", postfix = "\n"))
            } else {
                file.appendText("$line\n")
            }
        }
    }

    /** Every event filed so far, oldest first. */
    fun lines(): List<String> {
        val file = File(filesDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    }

    companion object {
        const val FILE_NAME = "events"

        /**
         * Bounded because nothing ever deletes this. Large enough that an evening of listening,
         * measuring and restarting fits in it whole, which is the span somebody asks about.
         */
        const val MOST_LINES = 2_000
    }
}
