package com.soundmesh.probe

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class DelayedPlaybackReportTest {
    @Test
    fun encodesTheReplayFactsThePcNeedsToTellSilenceFromFailure() {
        val report = DelayedPlaybackReport(
            usage = "ACCESSIBILITY",
            startedPlayback = true,
            underrunCount = 2,
            routedDeviceType = 2,
            failureCode = null,
            buffer = DelayedPcmStats(queuedFrames = 100, maxQueuedFrames = 4800, droppedFrames = 7, underflows = 3)
        )

        assertEquals(
            "{\"schemaVersion\":1,\"usage\":\"ACCESSIBILITY\",\"startedPlayback\":true," +
                "\"underrunCount\":2,\"routedDeviceType\":2,\"failureCode\":null," +
                "\"queuedFrames\":100,\"maxQueuedFrames\":4800,\"droppedFrames\":7,\"underflows\":3}",
            report.toJson()
        )
    }

    @Test
    fun encodesAFailedReplayWithoutBreakingJson() {
        val report = DelayedPlaybackReport(
            usage = "ALARM",
            startedPlayback = false,
            underrunCount = 0,
            routedDeviceType = null,
            failureCode = "IllegalStateException",
            buffer = DelayedPcmStats(0, 0, 0, 0)
        )

        assertEquals(
            "{\"schemaVersion\":1,\"usage\":\"ALARM\",\"startedPlayback\":false," +
                "\"underrunCount\":0,\"routedDeviceType\":null,\"failureCode\":\"IllegalStateException\"," +
                "\"queuedFrames\":0,\"maxQueuedFrames\":0,\"droppedFrames\":0,\"underflows\":0}",
            report.toJson()
        )
    }

    @Test
    fun writesTheReplayReportBesideTheOtherPrivateCaseArtifacts() {
        val filesDir = Files.createTempDirectory("soundmesh-replay-").toFile()
        try {
            val store = RunStore(filesDir)
            store.writeReplayJson("R2", "{\"schemaVersion\":1}")

            assertEquals("{\"schemaVersion\":1}", store.replayFile("R2").readText(Charsets.UTF_8))
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
