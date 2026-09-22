package com.soundmesh.core

/**
 * The instant a streamed marker was scheduled for, as the handset that scheduled it wrote it down.
 *
 * [playAtHostNanos] is the whole reason this crosses the wire. A recording says when a marker was
 * heard; only the handset knows when it was meant to be heard, and the gap between the two is the
 * placement error under test. O18 had to remove a straight line through the arrivals to stand in
 * for that schedule, which worked - and took the absolute offset with it, leaving "which level
 * does playback sit on" unanswerable. With the schedule itself there is nothing to remove.
 */
data class MarkerPlay(val index: Int, val sequence: Int, val playAtHostNanos: Long)

/**
 * Carries [MarkerPlay] through a run's report, written by the handset and read by the analysis.
 *
 * One definition of the field names rather than a writer here and a reader there. The two would
 * agree on the day they were written and part on the first change to either, and the way that
 * failure shows is a reader finding nothing and reporting a marked run as having no markers in it.
 */
object MarkerPlayCodec {
    /** The field this array is written under in a run's report. */
    const val FIELD = "markerPlays"

    /**
     * The field the *other* handset's grid is written under, by each side, from what it played.
     *
     * A facing run puts two grids in the air half a stride apart, and reading the pair needs both
     * instants. Neither side can derive the other's: the schedule is computed live on the host
     * (`System.nanoTime() + LEAD_NANOS` once per chunk) and its marker-to-marker gaps have been
     * measured at 4998.17-5001.99 ms rather than the flat 5000 a formula would assume, so a grid
     * rebuilt by arithmetic would be wrong by a millisecond at exactly the precision under test.
     * Both sides see every chunk's instant, so both simply write down what they saw.
     */
    const val FACING_FIELD = "facingMarkerPlays"

    fun encode(plays: List<MarkerPlay>): String =
        plays.joinToString(",", "[", "]") {
            "{\"index\":${it.index},\"sequence\":${it.sequence},\"playAtHostNanos\":${it.playAtHostNanos}}"
        }

    /**
     * The markers in [report], which may be the array alone or a whole run report around it.
     *
     * Empty for a report that has no such field, which every run taken before the field existed
     * is. Deliberately not an error: the reader's own check is that it found as many markers in
     * the recording as the handset says it scheduled, and that check needs a number to compare.
     */
    fun decode(report: String, field: String = FIELD): List<MarkerPlay> {
        val start = report.indexOf("\"$field\"").let {
            if (it < 0) report.indexOf('[') else report.indexOf('[', it)
        }
        if (start < 0) return emptyList()
        // No entry contains a bracket of its own, so the first one closes the array. A scan for a
        // matching bracket would be the same answer with somewhere else for it to go wrong.
        val end = report.indexOf(']', start)
        if (end < 0) return emptyList()
        return ENTRY.findAll(report.substring(start, end)).map {
            MarkerPlay(
                index = it.groupValues[1].toInt(),
                sequence = it.groupValues[2].toInt(),
                playAtHostNanos = it.groupValues[3].toLong()
            )
        }.toList()
    }

    /**
     * Both braces are escaped, and the closing one is the reason this is worth a comment.
     *
     * Desktop Java accepts a bare `}` in a pattern; Android's `java.util.regex` is ICU underneath
     * and refuses it. So the JVM tests here all passed and the handset threw
     * ExceptionInInitializerError out of this object's initialiser, at the one moment the run had
     * nothing left to do but write its report - a whole round of audio recorded and then discarded.
     */
    private val ENTRY =
        Regex("\\{\"index\":(-?\\d+),\"sequence\":(-?\\d+),\"playAtHostNanos\":(-?\\d+)\\}")
}
