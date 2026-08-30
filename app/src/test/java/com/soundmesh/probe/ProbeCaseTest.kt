package com.soundmesh.probe

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProbeCaseTest {
    @Test
    fun acceptsValidC1Configuration() {
        val probeCase = ProbeCase(
            caseId = "C1",
            durationSeconds = 20,
            expectedPackage = "com.netease.cloudmusic",
            mode = ProbeMode.CAPTURE_ONLY
        )

        assertEquals("C1", probeCase.caseId)
        assertEquals(20, probeCase.durationSeconds)
        assertEquals("com.netease.cloudmusic", probeCase.expectedPackage)
        assertEquals(ProbeMode.CAPTURE_ONLY, probeCase.mode)
    }

    @Test
    fun rejectsDurationOutsideFiveToOneHundredTwentySeconds() {
        assertThrows(IllegalArgumentException::class.java) {
            ProbeCase("C1", 4, "com.netease.cloudmusic", ProbeMode.CAPTURE_ONLY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeCase("C1", 121, "com.netease.cloudmusic", ProbeMode.CAPTURE_ONLY)
        }
    }

    @Test
    fun rejectsCaseIdsOutsideUppercaseLetterAndDigits() {
        listOf("c1", "C", "C-1", "C/1", "C1/../C2", "1C").forEach { caseId ->
            assertThrows(IllegalArgumentException::class.java, caseId) {
                ProbeCase(caseId, 20, "com.netease.cloudmusic", ProbeMode.CAPTURE_ONLY)
            }
        }
    }

    @Test
    fun rejectsUnknownExpectedPackage() {
        assertThrows(IllegalArgumentException::class.java) {
            ProbeCase("C1", 20, "com.example.unknown", ProbeMode.CAPTURE_ONLY)
        }
    }

    @Test
    fun createsOnlyThePrivateCaseDirectoryAndWritesStatusAtomically() {
        val filesDir = Files.createTempDirectory("soundmesh-run-store-").toFile()
        try {
            val store = RunStore(filesDir)
            val caseDirectory = store.prepareRun("C1")

            assertEquals(filesDir.resolve("runs/C1").canonicalFile, caseDirectory.canonicalFile)
            assertTrue(caseDirectory.isDirectory)
            assertFalse(filesDir.resolve("C1").exists())

            store.writeStatus("C1", "{\"state\":\"CAPTURING\"}")

            assertEquals(
                "{\"state\":\"CAPTURING\"}",
                store.statusFile("C1").readText(Charsets.UTF_8)
            )
            assertFalse(caseDirectory.resolve("status.json.tmp").exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathSeparatorsInRunIds() {
        val filesDir = Files.createTempDirectory("soundmesh-run-store-").toFile()
        try {
            val store = RunStore(filesDir)

            assertThrows(IllegalArgumentException::class.java) {
                store.prepareRun("C1/../C2")
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.statusFile("C1\\C2")
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
