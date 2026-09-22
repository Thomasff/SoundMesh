package com.soundmesh.probe.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatorShapeIsAFlagTest {
    /**
     * Every estimator the probe builds takes the run's shape, not the library default.
     *
     * A run that asks for a different window and gets the default back is the failure this
     * project keeps paying for: the flag is accepted, the report looks ordinary, and the arm
     * that was measured is not the arm that was asked for. The probe builds one estimator for
     * a clock-only run and another for a full sink round, and a flag threaded through one of
     * them only is worse than no flag, because half the rounds would silently be controls.
     *
     * Read as source text because the activity needs a device to run.
     */
    @Test
    fun everyEstimatorTheProbeBuildsTakesTheRequestedShape() {
        val source = File("src/main/java/com/soundmesh/probe/sync/SyncActivity.kt").readText(Charsets.UTF_8)
        // The arguments are themselves calls, so the closing bracket is not the first one.
        val built = Regex("""ClockOffsetEstimator\((?:[^()]|\([^()]*\))*\)""")
            .findAll(source).map { it.value }.toList()

        assertTrue("the probe builds no estimator at all any more", built.isNotEmpty())
        for (call in built) {
            assertTrue(
                "this estimator would run at the library default whatever the run asked for: $call",
                call.contains("estimatorWindowRequested()") && call.contains("estimatorBestRequested()")
            )
        }
    }

    /**
     * Out of range asks fall back rather than being honoured.
     *
     * A window below the estimator's own MIN_SAMPLES can never answer, and a kept count above
     * the window keeps everything, which is no selection at all - both are configurations that
     * produce a run rather than an error, which is the expensive kind of wrong.
     */
    @Test
    fun theShapeFlagsHaveBounds() {
        val source = File("src/main/java/com/soundmesh/probe/sync/SyncActivity.kt").readText(Charsets.UTF_8)
        for (name in listOf("estimatorWindowRequested", "estimatorBestRequested")) {
            val body = Regex("""private fun $name\(\)[\s\S]*?\n    }""").find(source)?.value
                ?: throw AssertionError("$name not found in SyncActivity")
            assertTrue(
                "$name honours whatever it is handed: $body",
                body.contains("in ") || body.contains("coerce")
            )
        }
    }
}
