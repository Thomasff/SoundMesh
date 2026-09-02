package com.soundmesh.probe

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceRejectionTest {
    private fun withStore(block: (RunStore, java.io.File) -> Unit) {
        val filesDir = Files.createTempDirectory("soundmesh-rejection-").toFile()
        try {
            block(RunStore(filesDir), filesDir)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun recordsTheRejectionReasonAgainstTheRequestedCase() = withStore { store, _ ->
        val recorded = ServiceRejection.record(store, "C3", IllegalStateException("A capture case is already running"))

        assertEquals("C3", recorded)
        assertEquals(
            "{\"state\":\"FAILED\",\"failureCode\":\"A capture case is already running\"}",
            store.statusFile("C3").readText(Charsets.UTF_8)
        )
    }

    @Test
    fun fallsBackToTheExceptionTypeWhenThereIsNoMessage() {
        assertEquals("IllegalStateException", ServiceRejection.failureCode(IllegalStateException()))
        assertEquals("INVALID_REQUEST", ServiceRejection.failureCode(object : RuntimeException() {}))
    }

    @Test
    fun keepsTheRecordedReasonValidJson() {
        assertEquals(
            "missing _session_id_ for line_1",
            ServiceRejection.failureCode(IllegalArgumentException("missing \"session_id\" for line\n1"))
        )
        assertEquals(120, ServiceRejection.failureCode(IllegalStateException("x".repeat(200))).length)
    }

    @Test
    fun recordsNothingForAMissingOrUnsafeCaseId() = withStore { store, filesDir ->
        assertNull(ServiceRejection.record(store, null, IllegalStateException("no case")))
        assertNull(ServiceRejection.record(store, "C1/../C2", IllegalStateException("unsafe")))
        assertFalse(filesDir.resolve("runs").exists())
    }
}
