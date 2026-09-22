package com.soundmesh.core

/**
 * What a handset did with one streamed marker at the instant it handed it to the output.
 *
 * [MarkerPlay] says when a marker was meant to be heard; this says what the handset did about it.
 * The pair is the whole point: their difference is the schedule-to-emission map, read on the
 * device rather than inferred from a recording.
 *
 * A host's map is one straight line per round, so fitting a line through the arrivals recovers it.
 * A sink's is not: every chunk it plays is converted through [offsetNanos], a clock estimate that
 * moves inside a round - 0.847 / 0.883 / 1.511 / 2.128 ms measured over four rounds of O21, and
 * the two rounds that could be read at all were the two smallest. So the sink's half of the
 * streamed calibration was unreadable, and a line through it would not have failed loudly; it
 * would have answered with a number.
 *
 * [localNanos] and [offsetNanos] are the two halves of that conversion, kept apart deliberately:
 * their sum is where the chunk went, and [offsetNanos] alone is the estimate's contribution to it.
 * [depthNanos] is how far ahead of being heard the handover was, [trimFrames] what was cut to make
 * the release land, and [filteredErrorFrames] what the drift loop thought it was correcting - the
 * two places a moving estimate could be absorbed before it ever reaches the speaker.
 */
data class MarkerRelease(
    val sequence: Int,
    val localNanos: Long,
    val offsetNanos: Long,
    val depthNanos: Long,
    val trimFrames: Int,
    val filteredErrorFrames: Int
)

/**
 * Carries [MarkerRelease] through a run's report, written by the handset and read by the analysis.
 *
 * Its own object for the reason [MarkerPlayCodec] is: the field names are the contract, and a
 * reader looking for a name the writer stopped using does not fail - it reports a run that
 * released markers as having released none.
 */
object MarkerReleaseCodec {
    /** The field this array is written under, inside the renderer's own object. */
    const val FIELD = "markerReleases"

    fun encode(releases: List<MarkerRelease>): String =
        releases.joinToString(",", "[", "]") {
            "{\"sequence\":${it.sequence},\"localNanos\":${it.localNanos}," +
                "\"offsetNanos\":${it.offsetNanos},\"depthNanos\":${it.depthNanos}," +
                "\"trimFrames\":${it.trimFrames},\"filteredErrorFrames\":${it.filteredErrorFrames}}"
        }

    /**
     * The releases in [report], which may be the array alone or a whole run report around it.
     *
     * Empty for a report that has no such field. Every round taken before the field existed is
     * one, including the four this field was added to explain.
     */
    fun decode(report: String): List<MarkerRelease> {
        // Anchored on the field name, never on the report's first bracket: this array sits inside
        // the renderer's object with chirpPlays and the estimate history already written above it,
        // so a search for the first bracket would find one of those and read nothing out of it.
        val named = report.indexOf("\"$FIELD\"")
        val start = if (named < 0) {
            if (report.trimStart().startsWith("[")) report.indexOf('[') else return emptyList()
        } else {
            report.indexOf('[', named)
        }
        if (start < 0) return emptyList()
        // No entry contains a bracket of its own, so the first one closes the array.
        val end = report.indexOf(']', start)
        if (end < 0) return emptyList()
        return ENTRY.findAll(report.substring(start, end)).map {
            MarkerRelease(
                sequence = it.groupValues[1].toInt(),
                localNanos = it.groupValues[2].toLong(),
                offsetNanos = it.groupValues[3].toLong(),
                depthNanos = it.groupValues[4].toLong(),
                trimFrames = it.groupValues[5].toInt(),
                filteredErrorFrames = it.groupValues[6].toInt()
            )
        }.toList()
    }

    /** The entry pattern's source, so a test can assert on the escaping itself. See [ENTRY]. */
    fun entryPattern(): String = ENTRY.pattern

    /**
     * Every brace escaped, both of them.
     *
     * Android's `java.util.regex` is ICU underneath and refuses a bare `}` that desktop Java
     * accepts. [MarkerPlayCodec] found that out the expensive way - a whole round of audio
     * recorded, then ExceptionInInitializerError at the one moment the run had nothing left to do
     * but write its report. The JVM tests here cannot reproduce it, so the escaping is asserted
     * instead.
     */
    private val ENTRY = Regex(
        "\\{\"sequence\":(-?\\d+),\"localNanos\":(-?\\d+),\"offsetNanos\":(-?\\d+)," +
            "\"depthNanos\":(-?\\d+),\"trimFrames\":(-?\\d+),\"filteredErrorFrames\":(-?\\d+)\\}"
    )
}
