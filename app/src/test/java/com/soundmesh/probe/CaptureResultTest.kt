package com.soundmesh.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureResultTest {
    @Test
    fun encodesStableOrderedJsonAndEscapesStrings() {
        val pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(16384)
            .putShort(-16384)
            .array()
        val metrics = PcmMetricsAccumulator(48_000, 1).apply {
            accept(pcm, pcm.size, 1_000L)
        }.finish()
        val result = CaptureResult(
            schemaVersion = 1,
            caseId = "case\"\\\n",
            state = CaptureState.COMPLETE,
            requestedFormat = PcmFormat(48_000, 1, "PCM16"),
            actualFormat = PcmFormat(48_000, 1, "PCM16"),
            expectedBytes = 4L,
            capturedBytes = 4L,
            metrics = metrics,
            failureCode = null,
            artifactFiles = listOf("capture.wav", "analysis\".json")
        )

        assertEquals(
            "{\"schemaVersion\":1,\"caseId\":\"case\\\"\\\\\\n\",\"state\":\"COMPLETE\",\"requestedFormat\":{\"sampleRate\":48000,\"channelCount\":1,\"encoding\":\"PCM16\"},\"actualFormat\":{\"sampleRate\":48000,\"channelCount\":1,\"encoding\":\"PCM16\"},\"expectedBytes\":4,\"capturedBytes\":4,\"metrics\":{\"sampleRate\":48000,\"channelCount\":1,\"sampleCount\":2,\"nonZeroCount\":2,\"nonZeroRatio\":1.0,\"sumSquares\":0.5,\"peakAbsolute\":16384,\"peakLinear\":0.5,\"rmsLinear\":0.5,\"peakDbfs\":-6.020599913279624,\"rmsDbfs\":-6.020599913279624,\"clippedCount\":0,\"receivedBytes\":4,\"incompleteByteCount\":0,\"firstTimestampNanos\":1000,\"lastTimestampNanos\":1000,\"maxTimestampGapNanos\":0},\"failureCode\":null,\"artifactFiles\":[\"capture.wav\",\"analysis\\\".json\"]}",
            result.toJson()
        )
    }

    @Test
    fun encodesAbsentActualFormatAndMetricsAsNull() {
        val result = CaptureResult(
            schemaVersion = 1,
            caseId = "failed",
            state = "FAILED",
            requestedFormat = PcmFormat(48_000, 2),
            actualFormat = null,
            expectedBytes = 0L,
            capturedBytes = 0L,
            metrics = null,
            failureCode = "CAPTURE_DENIED",
            artifactFiles = emptyList()
        )

        assertEquals(
            "{\"schemaVersion\":1,\"caseId\":\"failed\",\"state\":\"FAILED\",\"requestedFormat\":{\"sampleRate\":48000,\"channelCount\":2,\"encoding\":\"PCM16\"},\"actualFormat\":null,\"expectedBytes\":0,\"capturedBytes\":0,\"metrics\":null,\"failureCode\":\"CAPTURE_DENIED\",\"artifactFiles\":[]}",
            result.toJson()
        )
    }
}
