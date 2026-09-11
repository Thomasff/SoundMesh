package com.soundmesh.product

import androidx.annotation.StringRes
import com.soundmesh.core.PeerBadge
import com.soundmesh.probe.R

/** One row of the state panel: what it is called, and what it currently reads. */
data class Counter(@StringRes val label: Int, val value: String)

/**
 * The renderer's own JSON, turned into rows a person can read off a table.
 *
 * Reads the report rather than re-deriving anything, so the screen and the file cannot disagree -
 * the same reason [com.soundmesh.session.SessionActivity] does it that way. What is different here
 * is the audience: the harness screen prints field names because ADB reads it, and this one is
 * read by whoever is listening to the two handsets.
 *
 * Every value is language-neutral - a number, a duration, a unit. The words belong to strings.xml,
 * which is what lets this whole file be tested without a device.
 *
 * A field that is not in the report gets no row, which is what tells the two roles apart: only a
 * sink dials anybody, only a host broadcasts. The alternative - a row of zeroes on the host - reads
 * as a host that survived nothing rather than one with nothing to survive.
 */
object SessionReadout {
    fun counters(report: String?): List<Counter> {
        if (report == null) return emptyList()
        val rows = mutableListOf<Counter>()
        whole(report, "played")?.let { rows += Counter(R.string.counter_played, duration(it)) }
        whole(report, "releaseTrims")?.let { rows += Counter(R.string.counter_trims, "$it") }
        whole(report, "trimmedFrames")?.let { rows += Counter(R.string.counter_trimmed_frames, "$it") }
        whole(report, "silenceWrites")?.let { rows += Counter(R.string.counter_silence, "$it") }
        whole(report, "droppedLate")?.let { rows += Counter(R.string.counter_dropped, "$it") }
        whole(report, "droppedOverflow")?.let { rows += Counter(R.string.counter_overflow, "$it") }
        whole(report, "trackUnderruns")?.let { rows += Counter(R.string.counter_underruns, "$it") }
        // A sink's own, keyed off the one field only a sink writes. The clock row is built from two
        // fields, and the grading can be absent while the estimate is not, so the row is decided by
        // the counter that is always there rather than by either half of what it shows.
        whole(report, "reconnects")?.let {
            rows += Counter(R.string.counter_reconnects, "$it")
            rows += Counter(R.string.counter_rediscoveries, "${whole(report, "rediscoveries") ?: 0}")
            rows += Counter(R.string.counter_clock, clock(report))
            // The one reading here that a listener can check against their own patience, and
            // the only account of it before this row existed was the room saying "十几秒".
            rows += Counter(R.string.counter_silent_start, silentStart(report))
        }
        // A host's own, and the only rows that say anything about the other handsets rather than
        // about this one. Keyed off the connection count and not off the roster, because a host
        // with no name of its own has connections and no roster at all: the number it knows still
        // gets a row, and the room it does not have does not get an empty one.
        whole(report, "sinks")?.let { sinks ->
            val room = text(report, "roomPeerIds")
                ?.split(",")
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            // The same numbers the drawing labels its icons with, so one screen's "42" is the
            // other screen's "42". The colour is the other half of that name and cannot come
            // here: this is built from the report alone and the report carries no colour, which
            // is the limit that keeps this row from being the thing that says which one dropped.
            if (room.isNotEmpty()) {
                rows += Counter(R.string.counter_room, room.joinToString(" ") { "${PeerBadge.numberOf(it)}" })
            }
            // Two numbers, and neither is a reading on its own: how many sockets are open says
            // nothing about how many were expected, and how many announced themselves says nothing
            // about how many are still listening. It is their disagreement that says a handset
            // left. It cannot say which one - the audio sockets carry a name of their own now,
            // and the report even carries it, but naming a handset takes a colour this row has no
            // way to reach - so this is still a prompt to look at the phones rather than an
            // answer about them.
            rows += Counter(
                R.string.counter_sinks,
                if (room.isEmpty()) "$sinks" else "$sinks/${room.size - 1}"
            )
        }
        // A host's own. The slowest broadcast is the counter that would say the per-sink queues had
        // stopped absorbing a peer that vanished without closing its socket - it read 26,953 ms the
        // day before those queues existed.
        whole(report, "maxBroadcastNanos")?.let {
            rows += Counter(R.string.counter_broadcast, millis(it, 0))
            rows += Counter(R.string.counter_to_sinks, "${whole(report, "droppedToSinks") ?: 0}")
            rows += Counter(R.string.counter_source_late, "${whole(report, "lateChunks") ?: 0}")
            rows += Counter(R.string.counter_songs_skipped, "${whole(report, "skippedSongs") ?: 0}")
        }
        return rows
    }

    /** Chunks are 20 ms by construction, so the renderer's own count is the better clock. */
    private fun duration(chunks: Long): String {
        val seconds = chunks * CHUNK_MILLIS / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun clock(report: String): String {
        val health = text(report, "clockHealth") ?: "-"
        return "$health ${millis(whole(report, "worstUncertaintyNanos") ?: 0L, 2)}"
    }

    /** Seconds, because this is the row compared against a person waiting, not against a budget. */
    private fun silentStart(report: String): String {
        val nanos = whole(report, "silentUntilFirstEstimateNanos") ?: -1L
        // Negative is "no estimate yet", which a zero would misreport as "answered instantly".
        return if (nanos < 0) "—"
        else String.format(null as java.util.Locale?, "%.1f s", nanos / 1e9)
    }

    private fun millis(nanos: Long, decimals: Int): String =
        "${String.format(null as java.util.Locale?, "%.${decimals}f", nanos / 1e6)} ms"

    private fun whole(report: String, name: String): Long? =
        Regex("\"$name\":(-?\\d+)").find(report)?.groupValues?.get(1)?.toLongOrNull()

    private fun text(report: String, name: String): String? =
        Regex("\"$name\":\"([^\"]*)\"").find(report)?.groupValues?.get(1)

    private const val CHUNK_MILLIS = 20
}
