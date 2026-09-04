package com.soundmesh.core

/** One handset's whole reading of one run, as it travels to the handset that combines the two. */
data class AlignmentResultMessage(val caseId: String, val readings: List<AlignmentReading>)

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
    const val VERSION = 1

    private const val FIELDS_PER_READING = 11
    private const val NULL = "null"

    fun encode(caseId: String, readings: List<AlignmentReading>): String {
        require(caseId.isNotEmpty() && caseId.none { it.isWhitespace() }) {
            "caseId must be non-empty and carry no whitespace: it is a header field"
        }
        val lines = ArrayList<String>(readings.size + 1)
        lines.add("$MAGIC $VERSION $caseId ${readings.size}")
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
        require(header.size == 4 && header[0] == MAGIC) { "not an alignment result: ${lines[0]}" }
        require(header[1] == VERSION.toString()) { "unsupported alignment result version: ${header[1]}" }
        val caseId = header[2]
        val count = header[3].toIntOrNull() ?: throw IllegalArgumentException("unreadable count: ${header[3]}")
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
        return AlignmentResultMessage(caseId, readings)
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
