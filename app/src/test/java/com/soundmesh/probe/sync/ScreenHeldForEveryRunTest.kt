package com.soundmesh.probe.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenHeldForEveryRunTest {
    /**
     * The window flags belong to a run, not to the activity's creation.
     *
     * A run clears FLAG_KEEP_SCREEN_ON when it ends, and every round after the first arrives
     * through onNewIntent, which does not rebuild the window. Arming them in onCreate alone
     * therefore protected the first round and no other - measured on two handsets, where the
     * unprotected rounds read twelve to twenty-seven frames further out than the protected ones
     * on the same parameters. Read as source text because the activity needs a device to run.
     */
    @Test
    fun theRunThatClearsTheScreenFlagIsAlsoTheRunThatArmsIt() {
        val source = File("src/main/java/com/soundmesh/probe/sync/SyncActivity.kt").readText(Charsets.UTF_8)
        val startRun = Regex("""private fun startRun\([\s\S]*?\n    private fun""").find(source)?.value
            ?: throw AssertionError("startRun not found in SyncActivity")

        assertTrue(
            "startRun is where a run's screen hold is released, so it is also where it must be " +
                "taken: $startRun",
            startRun.contains("holdScreenForRun()")
        )
        assertTrue(
            "this test is guarding the wrong function if startRun no longer clears the flag",
            startRun.contains("clearFlags")
        )
    }
}
