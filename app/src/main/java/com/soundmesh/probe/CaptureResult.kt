package com.soundmesh.probe

/** PCM details carried in capture reports. */
data class PcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: String = "PCM16"
)

enum class CaptureState { PENDING, RUNNING, COMPLETE, FAILED, CANCELLED }

/** Deterministic, dependency-free JSON result for Node fixture comparisons. */
data class CaptureResult(
    val schemaVersion: Int,
    val caseId: String,
    val state: String,
    val requestedFormat: PcmFormat,
    val actualFormat: PcmFormat?,
    val expectedBytes: Long,
    val capturedBytes: Long,
    val metrics: PcmMetrics?,
    val failureCode: String?,
    val artifactFiles: List<String>
) {
    constructor(
        schemaVersion: Int,
        caseId: String,
        state: CaptureState,
        requestedFormat: PcmFormat,
        actualFormat: PcmFormat?,
        expectedBytes: Long,
        capturedBytes: Long,
        metrics: PcmMetrics?,
        failureCode: String?,
        artifactFiles: List<String>
    ) : this(schemaVersion, caseId, state.name, requestedFormat, actualFormat, expectedBytes,
        capturedBytes, metrics, failureCode, artifactFiles)

    fun toJson(): String = buildString {
        append('{')
        append("\"schemaVersion\":").append(schemaVersion)
        append(",\"caseId\":"); appendJsonString(this, caseId)
        append(",\"state\":"); appendJsonString(this, state)
        append(",\"requestedFormat\":"); appendFormatJson(this, requestedFormat)
        append(",\"actualFormat\":")
        if (actualFormat == null) append("null") else appendFormatJson(this, actualFormat)
        append(",\"expectedBytes\":").append(expectedBytes)
        append(",\"capturedBytes\":").append(capturedBytes)
        append(",\"metrics\":")
        if (metrics == null) append("null") else append(metrics.toJson())
        append(",\"failureCode\":")
        if (failureCode == null) append("null") else appendJsonString(this, failureCode)
        append(",\"artifactFiles\":[")
        artifactFiles.forEachIndexed { index, file ->
            if (index != 0) append(',')
            appendJsonString(this, file)
        }
        append("]}")
    }
}

private fun appendFormatJson(builder: StringBuilder, format: PcmFormat) {
    builder.append("{\"sampleRate\":").append(format.sampleRate)
        .append(",\"channelCount\":").append(format.channelCount)
        .append(",\"encoding\":")
    appendJsonString(builder, format.encoding)
    builder.append('}')
}

private fun appendJsonString(builder: StringBuilder, value: String) {
    builder.append('"')
    value.forEach { character ->
        when (character) {
            '"' -> builder.append("\\\"")
            '\\' -> builder.append("\\\\")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> if (character.code < 0x20) {
                builder.append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                builder.append(character)
            }
        }
    }
    builder.append('"')
}
