package com.soundmesh.probe

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The aggregate measurements produced by [PcmMetricsAccumulator].
 *
 * Samples are scalar PCM samples (one value per channel), rather than frames.
 * Linear amplitudes are normalized against the signed PCM16 full-scale value
 * of 32768, so -32768 has an amplitude of exactly 1.0.
 */
data class PcmMetrics(
    val sampleRate: Int,
    val channelCount: Int,
    val sampleCount: Long,
    val nonZeroCount: Long,
    val sumSquares: Double,
    val peakAbsolute: Int,
    val clippedCount: Long,
    val receivedBytes: Long,
    val incompleteByteCount: Long,
    val firstTimestampNanos: Long?,
    val lastTimestampNanos: Long?,
    val maxTimestampGapNanos: Long
) {
    val nonZeroRatio: Double
        get() = if (sampleCount == 0L) 0.0 else nonZeroCount.toDouble() / sampleCount

    val peakLinear: Double
        get() = peakAbsolute.toDouble() / PCM16_FULL_SCALE

    val rmsLinear: Double
        get() = if (sampleCount == 0L) 0.0 else sqrt(sumSquares / sampleCount)

    val peakDbfs: Double
        get() = linearToDbfs(peakLinear)

    val rmsDbfs: Double
        get() = linearToDbfs(rmsLinear)

    /** Android and Node report this threshold in milliseconds. */
    val hasTimestampGapOver500Ms: Boolean
        get() = maxTimestampGapNanos > MAX_TIMESTAMP_GAP_NANOS

    // Keep common spelling aliases at the boundary; the JSON schema uses the
    // canonical *Dbfs names above.
    val peakDbFS: Double
        get() = peakDbfs

    val rmsDbFS: Double
        get() = rmsDbfs

    val receivedByteCount: Long
        get() = receivedBytes

    val maxGapNanos: Long
        get() = maxTimestampGapNanos

    val frameCount: Long
        get() = if (channelCount == 0) 0L else sampleCount / channelCount

    /** A stable representation used by [CaptureResult]. */
    fun toJson(): String = buildString { appendMetricsJson(this@PcmMetrics, this) }

    companion object {
        const val PCM16_FULL_SCALE = 32768.0
        const val MAX_TIMESTAMP_GAP_NANOS = 500_000_000L

        private fun linearToDbfs(value: Double): Double =
            if (value == 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(value)
    }
}

/**
 * Incrementally computes measurements for signed little-endian PCM16 data.
 *
 * A chunk may end on a byte boundary. One incomplete byte is carried into the
 * next chunk and is intentionally not counted as a sample if the stream ends.
 */
class PcmMetricsAccumulator(
    val sampleRate: Int,
    val channelCount: Int
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
    }

    private var sampleCount = 0L
    private var nonZeroCount = 0L
    private var sumSquares = 0.0
    private var peakAbsolute = 0
    private var clippedCount = 0L
    private var receivedBytes = 0L
    private var pendingByte: Int? = null
    private var firstTimestampNanos: Long? = null
    private var lastTimestampNanos: Long? = null
    private var maxTimestampGapNanos = 0L

    /**
     * Accepts up to [length] bytes from [bytes] at the chunk timestamp.
     * Timestamps must be monotonic; a negative timestamp is not otherwise
     * special and is valid when supplied by a relative test clock.
     */
    fun accept(bytes: ByteArray, length: Int, timestampNanos: Long) {
        require(length >= 0 && length <= bytes.size) {
            "length must be between 0 and bytes.size"
        }
        if (length == 0) return

        val previousTimestamp = lastTimestampNanos
        if (previousTimestamp != null) {
            require(timestampNanos >= previousTimestamp) {
                "timestampNanos must be monotonic"
            }
            maxTimestampGapNanos = maxOf(
                maxTimestampGapNanos,
                timestampNanos - previousTimestamp
            )
        } else {
            firstTimestampNanos = timestampNanos
        }
        lastTimestampNanos = timestampNanos
        receivedBytes += length.toLong()

        var offset = 0
        val carried = pendingByte
        if (carried != null) {
            if (length > 0) {
                processSample(carried or ((bytes[0].toInt() and 0xff) shl 8))
                pendingByte = null
                offset = 1
            }
        }

        while (offset + 1 < length) {
            val low = bytes[offset].toInt() and 0xff
            val high = bytes[offset + 1].toInt() and 0xff
            processSample(low or (high shl 8))
            offset += 2
        }
        if (offset < length) {
            pendingByte = bytes[offset].toInt() and 0xff
        }
    }

    fun finish(): PcmMetrics = PcmMetrics(
        sampleRate = sampleRate,
        channelCount = channelCount,
        sampleCount = sampleCount,
        nonZeroCount = nonZeroCount,
        sumSquares = sumSquares,
        peakAbsolute = peakAbsolute,
        clippedCount = clippedCount,
        receivedBytes = receivedBytes,
        incompleteByteCount = if (pendingByte == null) 0L else 1L,
        firstTimestampNanos = firstTimestampNanos,
        lastTimestampNanos = lastTimestampNanos,
        maxTimestampGapNanos = maxTimestampGapNanos
    )

    private fun processSample(encodedSample: Int) {
        val sample = encodedSample.toShort().toInt()
        val absolute = abs(sample)
        val normalized = sample.toDouble() / PcmMetrics.PCM16_FULL_SCALE

        sampleCount++
        if (sample != 0) nonZeroCount++
        sumSquares += normalized * normalized
        if (absolute > peakAbsolute) peakAbsolute = absolute
        // The positive endpoint is 32767, while the negative endpoint is
        // -32768. Both are digital full-scale/clipped samples.
        if (sample == Short.MIN_VALUE.toInt() || sample == Short.MAX_VALUE.toInt()) {
            clippedCount++
        }
    }
}

private fun appendMetricsJson(metrics: PcmMetrics, builder: StringBuilder) {
    builder.append('{')
        .append("\"sampleRate\":").append(metrics.sampleRate)
        .append(",\"channelCount\":").append(metrics.channelCount)
        .append(",\"sampleCount\":").append(metrics.sampleCount)
        .append(",\"nonZeroCount\":").append(metrics.nonZeroCount)
        .append(",\"nonZeroRatio\":").append(metrics.nonZeroRatio)
        .append(",\"sumSquares\":").append(metrics.sumSquares)
        .append(",\"peakAbsolute\":").append(metrics.peakAbsolute)
        .append(",\"peakLinear\":").append(metrics.peakLinear)
        .append(",\"rmsLinear\":").append(metrics.rmsLinear)
        .append(",\"peakDbfs\":")
    appendJsonDoubleOrNull(builder, metrics.peakDbfs)
    builder.append(",\"rmsDbfs\":")
    appendJsonDoubleOrNull(builder, metrics.rmsDbfs)
    builder.append(",\"clippedCount\":").append(metrics.clippedCount)
        .append(",\"receivedBytes\":").append(metrics.receivedBytes)
        .append(",\"incompleteByteCount\":").append(metrics.incompleteByteCount)
        .append(",\"firstTimestampNanos\":")
    appendJsonLongOrNull(builder, metrics.firstTimestampNanos)
    builder.append(",\"lastTimestampNanos\":")
    appendJsonLongOrNull(builder, metrics.lastTimestampNanos)
    builder.append(",\"maxTimestampGapNanos\":").append(metrics.maxTimestampGapNanos)
        .append('}')
}

private fun appendJsonDoubleOrNull(builder: StringBuilder, value: Double) {
    if (value.isFinite()) builder.append(value) else builder.append("null")
}

private fun appendJsonLongOrNull(builder: StringBuilder, value: Long?) {
    if (value == null) builder.append("null") else builder.append(value)
}
