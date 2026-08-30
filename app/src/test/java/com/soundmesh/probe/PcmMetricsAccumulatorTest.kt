package com.soundmesh.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMetricsAccumulatorTest {
    @Test
    fun computesNonZeroRatioPeakAndRms() {
        val samples = shortArrayOf(0, 0, 16384, -16384)
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)

        val metrics = PcmMetricsAccumulator(48_000, 2).apply {
            accept(bytes.array(), bytes.array().size, 1_000_000_000L)
        }.finish()

        assertEquals(0.5, metrics.nonZeroRatio, 0.0001)
        assertEquals(0.5, metrics.peakLinear, 0.0001)
        assertEquals(sqrt(0.125), metrics.rmsLinear, 0.0001)
        assertEquals(4L, metrics.sampleCount)
        assertEquals(8L, metrics.receivedBytes)
    }

    @Test
    fun reportsExactDigitalSilenceAsNegativeInfinityDbfs() {
        val metrics = PcmMetricsAccumulator(48_000, 2).apply {
            accept(ByteArray(8), 8, 10L)
        }.finish()

        assertEquals(0.0, metrics.nonZeroRatio, 0.0)
        assertEquals(0.0, metrics.peakLinear, 0.0)
        assertEquals(0.0, metrics.rmsLinear, 0.0)
        assertTrue(metrics.peakDbfs.isInfinite() && metrics.peakDbfs < 0.0)
        assertTrue(metrics.rmsDbfs.isInfinite() && metrics.rmsDbfs < 0.0)
    }

    @Test
    fun countsBothSignedPcm16EndpointsAsClipping() {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(Short.MIN_VALUE)
            .putShort(Short.MAX_VALUE)
            .array()

        val metrics = PcmMetricsAccumulator(48_000, 1).apply {
            accept(bytes, bytes.size, 10L)
        }.finish()

        assertEquals(2L, metrics.clippedCount)
        assertEquals(1.0, metrics.peakLinear, 0.0)
    }

    @Test
    fun carriesAnIncompleteTrailingByteIntoTheNextChunk() {
        val accumulator = PcmMetricsAccumulator(48_000, 1)
        accumulator.accept(byteArrayOf(0x01), 1, 10L)
        accumulator.accept(byteArrayOf(0x00, 0x02, 0x00, 0x7f), 4, 20L)

        val metrics = accumulator.finish()

        assertEquals(2L, metrics.sampleCount)
        assertEquals(5L, metrics.receivedBytes)
        assertEquals(1L, metrics.incompleteByteCount)
        assertEquals(1.0, metrics.nonZeroRatio, 0.0)
    }

    @Test
    fun recordsTimestampGapLongerThan500Milliseconds() {
        val bytes = byteArrayOf(0, 0)
        val metrics = PcmMetricsAccumulator(48_000, 1).apply {
            accept(bytes, bytes.size, 1_000_000_000L)
            accept(bytes, bytes.size, 1_600_000_001L)
        }.finish()

        assertEquals(600_000_001L, metrics.maxTimestampGapNanos)
        assertTrue(metrics.hasTimestampGapOver500Ms)
    }
}
