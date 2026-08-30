package com.soundmesh.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRunnerTest {
    @Test
    fun completesDurationAfterWritingAllAvailableChunks() {
        val reader = RecordingReader(listOf(1920, 1920))
        val sink = RecordingSink()
        val summary = CaptureRunner.run(
            CaptureRunConfig(
                reader = reader,
                sink = sink,
                durationNanos = 100L,
                clock = ScriptedClock(0L, 0L, 0L, 1L, 1L, 100L)
            )
        )

        assertEquals(CaptureState.COMPLETE, summary.state)
        assertEquals(3_840L, summary.capturedBytes)
        assertEquals(0L, summary.startedAtNanos)
        assertEquals(100L, summary.endedAtNanos)
        assertEquals(listOf(1920, 1920), sink.writes)
        assertEquals(1, reader.startCalls)
        assertEquals(1, reader.stopCalls)
        assertEquals(1, reader.closeCalls)
        assertEquals(1, sink.closeCalls)
        assertTrue(summary.failureCode == null)
    }

    @Test
    fun preservesNegativeAndroidErrorWhenCleanupAlsoFails() {
        val events = mutableListOf<String>()
        val reader = RecordingReader(listOf(-6), events, failStop = true)
        val sink = RecordingSink(events)

        val summary = CaptureRunner.run(
            CaptureRunConfig(reader, sink, 100L, ScriptedClock(0L, 0L, 0L))
        )

        assertEquals(CaptureState.FAILED, summary.state)
        assertEquals("READER_ERROR_-6", summary.failureCode)
        assertEquals(-6, summary.readerErrorCode)
        assertEquals(listOf("start", "stop", "reader.close", "sink.close"), events)
        assertEquals(1, reader.closeCalls)
        assertEquals(1, sink.closeCalls)
    }

    @Test
    fun reportsReaderExceptionAndClosesBothResources() {
        val events = mutableListOf<String>()
        val reader = RecordingReader(listOf(0), events, failRead = true)
        val sink = RecordingSink(events)

        val summary = CaptureRunner.run(
            CaptureRunConfig(reader, sink, 100L, ScriptedClock(0L, 0L, 0L))
        )

        assertEquals(CaptureState.FAILED, summary.state)
        assertEquals("READER_EXCEPTION", summary.failureCode)
        assertEquals(listOf("start", "stop", "reader.close", "sink.close"), events)
    }

    @Test
    fun reportsSinkExceptionWithoutCountingUnwrittenBytes() {
        val events = mutableListOf<String>()
        val reader = RecordingReader(listOf(64), events)
        val sink = RecordingSink(events, failWrite = true)

        val summary = CaptureRunner.run(
            CaptureRunConfig(reader, sink, 100L, ScriptedClock(0L, 0L, 0L))
        )

        assertEquals(CaptureState.FAILED, summary.state)
        assertEquals("SINK_EXCEPTION", summary.failureCode)
        assertEquals(0L, summary.capturedBytes)
        assertEquals(listOf("start", "stop", "reader.close", "sink.close"), events)
    }

    @Test
    fun failsAfterOneHundredConsecutiveZeroReads() {
        val reader = RecordingReader(List(100) { 0 })
        val sink = RecordingSink()

        val summary = CaptureRunner.run(
            CaptureRunConfig(reader, sink, Long.MAX_VALUE, RepeatingClock(0L))
        )

        assertEquals(CaptureState.FAILED, summary.state)
        assertEquals("READER_STALLED", summary.failureCode)
        assertEquals(100, reader.readCalls)
        assertEquals(1, reader.stopCalls)
        assertEquals(1, reader.closeCalls)
        assertEquals(1, sink.closeCalls)
    }

    @Test
    fun cancellationStopsBeforeTheFirstReadAndClosesResources() {
        val events = mutableListOf<String>()
        val reader = RecordingReader(listOf(64), events)
        val sink = RecordingSink(events)

        val summary = CaptureRunner.run(
            CaptureRunConfig(
                reader = reader,
                sink = sink,
                durationNanos = 100L,
                clock = RepeatingClock(0L),
                stopRequested = { true }
            )
        )

        assertEquals(CaptureState.CANCELLED, summary.state)
        assertEquals("CANCELLED", summary.failureCode)
        assertEquals(0, reader.readCalls)
        assertEquals(listOf("start", "stop", "reader.close", "sink.close"), events)
    }

    private class RecordingReader(
        private val reads: List<Int>,
        private val events: MutableList<String> = mutableListOf(),
        private val failStop: Boolean = false,
        private val failRead: Boolean = false
    ) : PcmReader {
        override val sampleRate = 48_000
        override val channelCount = 2
        var startCalls = 0
        var stopCalls = 0
        var closeCalls = 0
        var readCalls = 0
        private var index = 0

        override fun start() { startCalls++; events += "start" }
        override fun read(target: ByteArray): Int {
            readCalls++
            if (failRead) throw IllegalStateException("reader failed")
            return reads.getOrElse(index++) { 0 }.also { length ->
            repeat(length.coerceAtLeast(0)) { target[it] = 1 }
            }
        }
        override fun stop() { stopCalls++; events += "stop"; if (failStop) throw IllegalStateException("stop failed") }
        override fun close() { closeCalls++; events += "reader.close" }
    }

    private class RecordingSink(
        private val events: MutableList<String> = mutableListOf(),
        private val failWrite: Boolean = false
    ) : PcmSink {
        val writes = mutableListOf<Int>()
        var closeCalls = 0
        override fun write(bytes: ByteArray, length: Int, timestampNanos: Long) {
            if (failWrite) throw IllegalStateException("sink failed")
            writes += length
        }
        override fun close() { closeCalls++; events += "sink.close" }
    }

    private class ScriptedClock(private vararg val readings: Long) : MonotonicClock {
        private var index = 0
        override fun nowNanos(): Long = readings.getOrElse(index++) { readings.last() }
    }

    private class RepeatingClock(private val reading: Long) : MonotonicClock {
        override fun nowNanos(): Long = reading
    }
}
