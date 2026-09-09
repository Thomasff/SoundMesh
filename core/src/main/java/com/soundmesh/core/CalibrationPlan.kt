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
 * What the sink asks for: the kind of run, and which handset is asking.
 *
 * [sinkId] is here because the host serves one sink per press and files what it measured under
 * the peer it measured it with. Without it the host's own half of a run is written to one name
 * per case, so a second sink measured on the same host overwrites the first where it stands -
 * the same failure the plan's own case id was added to stop, one dimension over. A host that
 * cannot name its peer also cannot say whose emission an archived run holds, which is the
 * attribution section 21 of the calibration notes got backwards.
 */
data class CalibrationRequest(val caseId: String, val sinkId: String)

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

    /** The ask, which travels the other way and is its own message. */
    const val REQUEST_MAGIC = "soundmesh-plan-request"
    const val REQUEST_VERSION = 1

    fun encodeRequest(request: CalibrationRequest): String {
        requireField(request.caseId, "caseId")
        requireField(request.sinkId, "sinkId")
        return "$REQUEST_MAGIC $REQUEST_VERSION ${request.caseId} ${request.sinkId}"
    }

    /**
     * Reads the ask, throwing rather than filling in a default for a field that is missing.
     *
     * A sink from before this line carried a name sends a bare case id. Guessing a name for it -
     * "unknown", or the only sink this host has seen - would file that run under a peer it was
     * not measured with, and nothing in any later result could notice. The host refuses instead,
     * and the refusal names the build.
     */
    fun decodeRequest(text: String): CalibrationRequest {
        val fields = text.trim().split(" ")
        require(fields.size == 4 && fields[0] == REQUEST_MAGIC) { "not a calibration request: $text" }
        require(fields[1] == REQUEST_VERSION.toString()) {
            "unsupported calibration request version: ${fields[1]}"
        }
        return CalibrationRequest(caseId = fields[2], sinkId = fields[3])
    }

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
