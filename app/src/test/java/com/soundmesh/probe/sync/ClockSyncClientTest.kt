package com.soundmesh.probe.sync

import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockOffsetEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * The interrupt is how every caller stops this loop, and [SinkSession.stop] says so in as many
     * words: it lands in the sleep between exchanges. Left to propagate it leaves the thread's
     * runnable by throwing, and an uncaught throw on any thread is the whole process.
     *
     * That is not hypothetical. The pair calibration screen interrupts this thread in the finally
     * that ends a run, and the process died there twice on hardware - once on 09-06 after the run
     * had already written its result, and once on 09-08 before the screen could say why the run
     * had failed. The second one cost the reason: the message was on screen for as long as it took
     * the process to go.
     */
    @Test
    fun anInterruptEndsTheLoopRatherThanThrowingOutOfTheThread() {
        val client = ClockSyncClient("127.0.0.1", freePort(), ClockOffsetEstimator())
        val thrown = AtomicReference<Throwable?>(null)
        val thread = Thread { client.runFor(seconds = 60, intervalMillis = 50) }
        thread.setUncaughtExceptionHandler { _, throwable -> thrown.set(throwable) }
        thread.start()
        // Long enough to be inside the loop rather than still opening the socket.
        Thread.sleep(300)

        thread.interrupt()
        thread.join(5_000)

        assertFalse("the loop ignored the interrupt and kept running", thread.isAlive)
        assertNull("the interrupt left the thread by throwing", thrown.get())
    }

    /**
     * A cycle whose fit is rejected leaves the previous answer standing.
     *
     * tools/src/clock-replay.mjs already says the run behaves this way - its test reads "its cache
     * keeps the last estimate that succeeded" - and the run did not: it wrote the rejection through
     * as a null, and the caller's own fallback then reached past every good estimate of the last
     * twenty seconds to the one taken before the chirps began. That is a divergence between the
     * shipped run and the replay it is meant to be scored against, which is the one thing
     * ClockReplayGoldenTest exists to prevent.
     *
     * It cost 7 ms on hardware: in the 17:44 run of 2026-09-08 the third chirp was emitted against
     * an offset fifty-four exchanges stale, and the run's worst single point was 9.167 ms against a
     * 3.0 gate. Held instead, the same recording gives 2.156 ms.
     *
     * The guard itself is right and stays: a rogue exchange with the shortest round trip of all and
     * an offset a hundred milliseconds out is exactly what the best-of cut is bound to keep, and
     * the slope is what catches it.
     */
    @Test
    fun aRejectedCycleLeavesTheLastEstimateThatSucceededStanding() {
        val held = HeldEstimate()
        val first = ClockEstimate(offsetNanos = 5L, uncertaintyNanos = 1L, driftPpm = 0.1, sampleCount = 8)
        val later = ClockEstimate(offsetNanos = 9L, uncertaintyNanos = 2L, driftPpm = 0.2, sampleCount = 8)

        assertNull("nothing is in force before the first fit comes back", held.current())

        held.offer(first)
        assertEquals(first, held.current())

        held.offer(null)
        assertEquals("a rejected fit erased the answer in force", first, held.current())

        held.offer(later)
        assertEquals(later, held.current())
    }
}
