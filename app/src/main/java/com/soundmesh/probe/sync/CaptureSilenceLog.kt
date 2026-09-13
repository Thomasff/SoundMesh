package com.soundmesh.probe.sync

import java.io.File

/**
 * Every stretch of digital silence a capture handed over, one line each, kept across sessions.
 *
 * A field in the session report would not do, for two reasons that both came from watching the
 * fault happen. The report is rewritten when the next session stops, and an evening of listening
 * is several sessions; and the report is only written when a session stops cleanly, which is not
 * how an evening of listening ends. What this is chasing happened twice in two days, in the middle
 * of a song, and was over before anybody could have looked at a screen.
 *
 * One line per spell rather than per chunk: a spell is already the rare thing, and the loop that
 * would write a line per chunk is the audio loop.
 */
class CaptureSilenceLog(private val filesDir: File) {

    /**
     * Files one spell. [atMillis] is the wall clock rather than a monotonic count, because the
     * whole use of this file is somebody saying "it went quiet around nine" and looking.
     */
    fun append(atMillis: Long, silentMillis: Long, recovered: Boolean) {
        val file = File(filesDir, FILE_NAME)
        runCatching {
            val kept = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
            val line = "$atMillis $silentMillis ${if (recovered) RECOVERED else STILL_SILENT}"
            val all = kept + line
            // Trimmed from the front: the last spell is the one somebody is asking about.
            if (all.size > MOST_LINES) {
                file.writeText(all.takeLast(MOST_LINES).joinToString("\n", postfix = "\n"))
            } else {
                file.appendText("$line\n")
            }
        }
    }

    /** Every spell filed so far, oldest first. Empty before the first one, which is the usual case. */
    fun lines(): List<String> {
        val file = File(filesDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    }

    companion object {
        const val FILE_NAME = "capture-silence"

        /**
         * Bounded because nothing ever deletes this. A fault recurring every few minutes for a
         * week should still leave a file a person can read to the end of.
         */
        const val MOST_LINES = 500

        private const val RECOVERED = "recovered"
        private const val STILL_SILENT = "still-silent"
    }
}
