package com.soundmesh.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockProbeLogTest {
    @Test
    fun encodesDistinctReadingsWithTheCountersThePcNeedsToJudgeTheDevice() {
        val log = ClockProbeLog(sampleRate = 48000, channelCount = 2)

        log.record(nanoTime = 1_000, framePosition = 0, writtenFrames = 4800)
        log.record(nanoTime = 201_000, framePosition = 9600, writtenFrames = 14400)

        assertEquals(
            "{\"schemaVersion\":1,\"sampleRate\":48000,\"channelCount\":2,\"requested\":2," +
                "\"unavailable\":0,\"duplicates\":0,\"failureCode\":null," +
                "\"samples\":[[1000,0,4800],[201000,9600,14400]]}",
            log.toJson(null)
        )
    }

    @Test
    fun dropsAnExactRepeatInsteadOfLettingItWeightTheFit() {
        val log = ClockProbeLog(sampleRate = 48000, channelCount = 2)

        log.record(nanoTime = 1_000, framePosition = 0, writtenFrames = 4800)
        log.record(nanoTime = 1_000, framePosition = 0, writtenFrames = 4800)
        log.record(nanoTime = 1_000, framePosition = 0, writtenFrames = 9600)

        assertEquals(1, log.sampleCount)
        assertTrue(log.toJson(null).contains("\"requested\":3,\"unavailable\":0,\"duplicates\":2"))
    }

    @Test
    fun countsAPollTheDeviceCouldNotAnswerWithoutInventingASample() {
        val log = ClockProbeLog(sampleRate = 48000, channelCount = 2)

        log.recordUnavailable()
        log.record(nanoTime = 1_000, framePosition = 0, writtenFrames = 0)

        assertEquals(1, log.sampleCount)
        assertTrue(log.toJson(null).contains("\"requested\":2,\"unavailable\":1,\"duplicates\":0"))
    }

    @Test
    fun encodesAFailureCodeWithoutBreakingJson() {
        val log = ClockProbeLog(sampleRate = 44100, channelCount = 1)

        assertEquals(
            "{\"schemaVersion\":1,\"sampleRate\":44100,\"channelCount\":1,\"requested\":0," +
                "\"unavailable\":0,\"duplicates\":0,\"failureCode\":\"IllegalStateException\",\"samples\":[]}",
            log.toJson("IllegalStateException")
        )
    }

    @Test
    fun stopsRecordingAtTheCapSoARunawayLoopCannotExhaustDeviceMemory() {
        val log = ClockProbeLog(sampleRate = 48000, channelCount = 2)

        for (index in 0 until ClockProbeLog.MAX_SAMPLES + 500) {
            log.record(nanoTime = index.toLong(), framePosition = index.toLong(), writtenFrames = index.toLong())
        }

        assertEquals(ClockProbeLog.MAX_SAMPLES, log.sampleCount)
    }
}
