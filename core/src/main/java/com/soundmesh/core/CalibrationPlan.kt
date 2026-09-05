package com.soundmesh.core

/**
 * The schedule both handsets of a pair run, decided by the host and handed to the sink.
 *
 * A calibration is two handsets doing the same thing at instants they have to agree on to the
 * millisecond, and the only clock they share is the host's. So the host names the instants in its
 * own `nanoTime` and the sink converts them, exactly as it converts the play instant on every
 * audio chunk. Nothing here is negotiated: a plan is told, not agreed, because a negotiation has a
 * failure mode - the two ends standing on different answers - that nothing in a calibration can
 * detect and no calibration survives.
 *
 * [firstChirpAtHostNanos] is the **sink's** first chirp. The host's trails it by [staggerNanos],
 * which is what lets a recording holding both tell them apart, and it is the convention every
 * archived run and [AlignmentAnalysis] already use.
 *
 * [hostId] is carried even though the sink already knows whose code it scanned, because it is the
 * file name the resulting constant is stored under. A correction filed against the wrong peer is
 * applied silently on every later session, and there is nothing in a result to notice it by.
 */
data class CalibrationPlan(
    val caseId: String,
    val hostId: String,
    val firstChirpAtHostNanos: Long,
    val staggerNanos: Long,
    val repeats: Int,
    val intervalNanos: Long
)

/**
 * Wire format for [CalibrationPlan]: one line, sent down the socket the sink opened.
 *
 * Text and hand rolled on the same terms as [AlignmentResultCodec] - written once per run rather
 * than once per chunk, readable straight out of a log when a run goes wrong, and this module has
 * no Android and no third party on its classpath.
 */
object CalibrationPlanCodec {
    const val MAGIC = "soundmesh-plan"
    const val VERSION = 1

    fun encode(plan: CalibrationPlan): String {
        requireField(plan.caseId, "caseId")
        requireField(plan.hostId, "hostId")
        return "$MAGIC $VERSION ${plan.caseId} ${plan.hostId} ${plan.firstChirpAtHostNanos} " +
            "${plan.staggerNanos} ${plan.repeats} ${plan.intervalNanos}"
    }

    fun decode(text: String): CalibrationPlan {
        val fields = text.trim().split(" ")
        require(fields.size == 8 && fields[0] == MAGIC) { "not a calibration plan: $text" }
        require(fields[1] == VERSION.toString()) { "unsupported calibration plan version: ${fields[1]}" }
        return CalibrationPlan(
            caseId = fields[2],
            hostId = fields[3],
            firstChirpAtHostNanos = fields[4].asLong(),
            staggerNanos = fields[5].asLong(),
            repeats = fields[6].asLong().toInt(),
            intervalNanos = fields[7].asLong()
        )
    }

    private fun requireField(value: String, name: String) {
        require(value.isNotEmpty() && value.none { it.isWhitespace() }) {
            "$name must be non-empty and carry no whitespace: the line is split on spaces"
        }
    }

    private fun String.asLong(): Long =
        toLongOrNull() ?: throw IllegalArgumentException("unreadable number: $this")
}
