package com.soundmesh.probe

/** Objective replay facts that let the PC tell "system muted it" apart from "playback never ran". */
data class DelayedPlaybackReport(
    val usage: String,
    val startedPlayback: Boolean,
    val underrunCount: Int,
    val routedDeviceType: Int?,
    val failureCode: String?,
    val buffer: DelayedPcmStats
) {
    fun toJson(): String = buildString {
        append("{\"schemaVersion\":1")
        append(",\"usage\":\"").append(usage).append('"')
        append(",\"startedPlayback\":").append(startedPlayback)
        append(",\"underrunCount\":").append(underrunCount)
        append(",\"routedDeviceType\":").append(routedDeviceType?.toString() ?: "null")
        append(",\"failureCode\":").append(failureCode?.let { "\"$it\"" } ?: "null")
        append(",\"queuedFrames\":").append(buffer.queuedFrames)
        append(",\"maxQueuedFrames\":").append(buffer.maxQueuedFrames)
        append(",\"droppedFrames\":").append(buffer.droppedFrames)
        append(",\"underflows\":").append(buffer.underflows)
        append('}')
    }
}
