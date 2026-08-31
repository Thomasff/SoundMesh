package com.soundmesh.probe

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class RunStoreTest {
    @Test fun writesExactPermissionStatesInPrivateRun() {
        val root = Files.createTempDirectory("soundmesh").toFile()
        val store = RunStore(root)
        store.writeStatus("C1", RunStatus.json("AWAITING_PERMISSION"))
        assertEquals("{\"state\":\"AWAITING_PERMISSION\",\"failureCode\":null}", store.statusFile("C1").readText())
        store.writeStatus("C1", RunStatus.json("FAILED"))
        assertEquals("{\"state\":\"FAILED\",\"failureCode\":null}", store.statusFile("C1").readText())
    }
}
