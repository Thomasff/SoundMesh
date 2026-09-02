package com.soundmesh.probe

/**
 * Collects raw audio clock readings for the PC to fit.
 * No analysis happens here on purpose: the device records facts, the PC decides what they mean.
 */
class ClockProbeLog(private val sampleRate: Int, private val channelCount: Int) {
    private val nanoTimes = ArrayList<Long>()
    private val framePositions = ArrayList<Long>()
    private val writtenFrames = ArrayList<Long>()
    private var requested = 0
    private var unavailable = 0
    private var duplicates = 0

    val sampleCount: Int get() = nanoTimes.size

    /** A poll the device refused to answer still counts; the refusal rate is itself a result. */
    @Synchronized
    fun recordUnavailable() {
        requested++
        unavailable++
    }

    @Synchronized
    fun record(nanoTime: Long, framePosition: Long, writtenFrames: Long) {
        requested++
        val last = nanoTimes.size - 1
        // Devices repeat the previous reading until the DAC advances; keeping repeats would
        // weight one instant many times over and flatten the fitted rate.
        if (last >= 0 && nanoTimes[last] == nanoTime && framePositions[last] == framePosition) {
            duplicates++
            return
        }
        if (nanoTimes.size >= MAX_SAMPLES) return
        nanoTimes.add(nanoTime)
        framePositions.add(framePosition)
        this.writtenFrames.add(writtenFrames)
    }

    @Synchronized
    fun toJson(failureCode: String?): String = buildString {
        append("{\"schemaVersion\":1")
        append(",\"sampleRate\":").append(sampleRate)
        append(",\"channelCount\":").append(channelCount)
        append(",\"requested\":").append(requested)
        append(",\"unavailable\":").append(unavailable)
        append(",\"duplicates\":").append(duplicates)
        append(",\"failureCode\":").append(failureCode?.let { "\"$it\"" } ?: "null")
        append(",\"samples\":[")
        for (index in nanoTimes.indices) {
            if (index > 0) append(',')
            append('[').append(nanoTimes[index])
                .append(',').append(framePositions[index])
                .append(',').append(writtenFrames[index]).append(']')
        }
        append("]}")
    }

    companion object {
        const val MAX_SAMPLES = 20_000
    }
}
