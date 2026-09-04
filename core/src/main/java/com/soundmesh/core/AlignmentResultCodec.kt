package com.soundmesh.core

/**
 * One handset's whole reading of one run, as it travels to the handset that combines the two.
 *
 * [appliedOffsetMicros] rides along because the correction is cumulative: what this handset
 * measured is the residual left over on top of what it was already correcting by, and only the
 * sender knows what it actually applied. The receiver could read its own copy of the same launch
 * flag instead, and would then compute a silently wrong new correction on any run where the two
 * roles were not started with the same one.
 */
data class AlignmentResultMessage(
    val caseId: String,
    val appliedOffsetMicros: Long,
    val readings: List<AlignmentReading>
)

/**
 * What the combining handset sends back: the correction the other side should stand on from now
 * on, and enough of the verdict for a report on that side to say why.
 *
 * [nextOffsetMicros] is null when this run cannot say - it could not be combined, or its scatter
 * makes the mean meaningless - and the receiver then keeps the correction it already had.
 */
data class CalibrationReply(
    val nextOffsetMicros: Long?,
    val clusterMeanMicros: Long?,
    val passed: Boolean?
)

/** Wire format for [CalibrationReply]: one line, sent back down the same socket. */
object CalibrationReplyCodec {
    const val MAGIC = "soundmesh-calibration"
    const val VERSION = 1

    fun encode(reply: CalibrationReply): String =
        "$MAGIC $VERSION ${reply.nextOffsetMicros ?: "null"} ${reply.clusterMeanMicros ?: "null"} ${reply.passed ?: "null"}"

    fun decode(text: String): CalibrationReply {
        val fields = text.trim().split(" ")
        require(fields.size == 5 && fields[0] == MAGIC) { "not a calibration reply: $text" }
        require(fields[1] == VERSION.toString()) { "unsupported calibration reply version: ${fields[1]}" }
        return CalibrationReply(
            nextOffsetMicros = fields[2].longOrNull(),
            clusterMeanMicros = fields[3].longOrNull(),
            passed = when (fields[4]) {
                "null" -> null
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("unreadable boolean: ${fields[4]}")
            }
        )
    }

    private fun String.longOrNull(): Long? =
        if (this == "null") null else toLongOrNull() ?: throw IllegalArgumentException("unreadable number: $this")
}

/**
 * Wire format for [AlignmentResultMessage].
 *
 * Text rather than a binary layout because it is written once per run, not once per chunk, and a
 * line of it can be read straight out of a log or a report when a run goes wrong. Hand rolled
 * rather than JSON because this module has no Android and no third party on its classpath.
 *
 * The whole reading crosses, not just the two numbers [AlignmentAnalysis.combineFacing] needs.
 * Removing the PC removes the only place the sink's own diagnostics could be read from, so a pair
 * that comes back unreadable would otherwise be unreadable for no stated reason - the ratios and
 * the search-edge flags are exactly what says whether the chirp was quiet, absent, or merely
 * looked for in the wrong place.
 */
object AlignmentResultCodec {
    const val MAGIC = "soundmesh-alignment"
    const val VERSION = 2

    private const val FIELDS_PER_READING = 11
    private const val NULL = "null"

    fun encode(caseId: String, appliedOffsetMicros: Long, readings: List<AlignmentReading>): String {
        require(caseId.isNotEmpty() && caseId.none { it.isWhitespace() }) {
            "caseId must be non-empty and carry no whitespace: it is a header field"
        }
        val lines = ArrayList<String>(readings.size + 1)
        lines.add("$MAGIC $VERSION $caseId $appliedOffsetMicros ${readings.size}")
        for (reading in readings) {
            lines.add(
                listOf(
                    reading.firstIndex.orNull(),
                    reading.secondIndex.orNull(),
                    reading.measuredStaggerFrames.orNull(),
                    reading.alignmentErrorMs.orNull(),
                    reading.propagationCorrectionMs.toString(),
                    reading.separationMetres.toString(),
                    reading.confidence.name,
                    reading.ratios.getOrNull(0).orNull(),
                    reading.ratios.getOrNull(1).orNull(),
                    reading.atSearchEdge.getOrNull(0).orNull(),
                    reading.atSearchEdge.getOrNull(1).orNull()
                ).joinToString(" ")
            )
        }
        return lines.joinToString("\n")
    }

    /**
     * Parses [text], throwing rather than returning a partial message.
     *
     * A truncated stream is the failure that matters here: the sender closes the socket to mark the
     * end, so a connection dropped mid-run looks exactly like a short run. Combining against the
     * pairs that did arrive would answer the alignment question from half a run without saying so.
     */
    fun decode(text: String): AlignmentResultMessage {
        val lines = text.split("\n").map { it.removeSuffix("\r") }
        require(lines.isNotEmpty()) { "empty alignment result" }
        val header = lines[0].split(" ")
        require(header.size == 5 && header[0] == MAGIC) { "not an alignment result: ${lines[0]}" }
        require(header[1] == VERSION.toString()) { "unsupported alignment result version: ${header[1]}" }
        val caseId = header[2]
        val appliedOffsetMicros = header[3].toLongOrNull()
            ?: throw IllegalArgumentException("unreadable applied offset: ${header[3]}")
        val count = header[4].toIntOrNull() ?: throw IllegalArgumentException("unreadable count: ${header[4]}")
        require(count >= 0) { "negative count: $count" }
        require(lines.size == count + 1) {
            "alignment result promised $count readings and carried ${lines.size - 1}"
        }
        val readings = (1..count).map { line ->
            val fields = lines[line].split(" ")
            require(fields.size == FIELDS_PER_READING) {
                "reading $line has ${fields.size} fields, expected $FIELDS_PER_READING"
            }
            AlignmentReading(
                firstIndex = fields[0].toIntOrNullable(),
                secondIndex = fields[1].toIntOrNullable(),
                measuredStaggerFrames = fields[2].toIntOrNullable(),
                alignmentErrorMs = fields[3].toDoubleOrNullable(),
                propagationCorrectionMs = fields[4].toDoubleField(),
                separationMetres = fields[5].toDoubleField(),
                confidence = AlignmentConfidence.valueOf(fields[6]),
                ratios = listOf(fields[7].toDoubleOrNullable(), fields[8].toDoubleOrNullable()),
                atSearchEdge = listOf(fields[9].toBooleanOrNullable(), fields[10].toBooleanOrNullable())
            )
        }
        return AlignmentResultMessage(caseId, appliedOffsetMicros, readings)
    }

    private fun Any?.orNull(): String = this?.toString() ?: NULL

    private fun String.toIntOrNullable(): Int? =
        if (this == NULL) null else toIntOrNull() ?: throw IllegalArgumentException("unreadable integer: $this")

    private fun String.toDoubleOrNullable(): Double? = if (this == NULL) null else toDoubleField()

    private fun String.toDoubleField(): Double =
        toDoubleOrNull() ?: throw IllegalArgumentException("unreadable number: $this")

    private fun String.toBooleanOrNullable(): Boolean? = when (this) {
        NULL -> null
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("unreadable boolean: $this")
    }
}
