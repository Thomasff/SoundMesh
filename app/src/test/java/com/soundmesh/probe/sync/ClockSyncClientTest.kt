package com.soundmesh.probe.sync

import com.soundmesh.core.ClockOffsetEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket

class ClockSyncClientTest {
    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    /**
     * The recorded exchanges are what makes an estimator design answerable offline: without them a
     * candidate can only be compared by running the phones again, one run per arm, which is how the
     * last comparison ended up unable to separate a real improvement from run-to-run scatter.
     */
    @Test
    fun keepsEveryExchangeItFedTheEstimator() {
        val port = freePort()
        val server = ClockSyncServer(port)
        server.start()
        try {
            val estimator = ClockOffsetEstimator()
            val client = ClockSyncClient("127.0.0.1", port, estimator)

            val estimates = client.runFor(seconds = 1, intervalMillis = 40)
            val exchanges = client.recordedExchanges()

            assertTrue("expected exchanges, got none", exchanges.isNotEmpty())
            assertTrue(exchanges.zipWithNext().all { (earlier, later) -> earlier.t1 < later.t1 })

            // The point of keeping them: replayed through a fresh estimator, the recorded exchanges
            // must reproduce the run exactly. Anything less and an offline comparison would be
            // scoring designs against input the run never actually saw.
            val replayed = ClockOffsetEstimator()
            val fromReplay = exchanges.mapNotNull { replayed.record(it); replayed.estimate(it.t4) }

            assertEquals(estimates, fromReplay)
        } finally {
            server.stop()
        }
    }
}
