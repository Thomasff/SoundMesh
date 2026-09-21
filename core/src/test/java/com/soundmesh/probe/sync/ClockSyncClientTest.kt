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

    /**
     * A sink is silent until the estimator answers, and it will not answer below MIN_SAMPLES. At the
     * session cadence of two seconds that is fourteen seconds of nothing - measured from the code
     * and confirmed by the room, where the second handset has always taken "十几秒". Nothing is
     * being computed in that time; the eighth ping is being waited for.
     *
     * Bursting the first few costs no accuracy. Replayed on 2026-09-10's eighteen runs, a first
     * eight gathered 250 ms apart landed 0.85 ± 1.69 ms from that run's mature reading against
     * 0.71 ± 2.53 for a first eight gathered 2000 ms apart - same mean, tighter spread, and a worst
     * case of 3.8 ms against 13.6. See on-device-calibration.md 28.1.
     */
    @Test
    fun theFirstExchangesComeFastSoASinkIsNotSilentWaitingForTheEighth() {
        val port = freePort()
        val server = ClockSyncServer(port)
        server.start()
        try {
            val client = ClockSyncClient("127.0.0.1", port, ClockOffsetEstimator())
            client.runFor(
                seconds = 2,
                intervalMillis = SETTLED_MILLIS,
                burstExchanges = BURST,
                burstIntervalMillis = BURST_MILLIS
            )
            val gaps = client.recordedExchanges().map { it.t1 }.zipWithNext { a, b -> (b - a) / 1_000_000L }

            assertTrue("too few exchanges to see a cadence: ${gaps.size}", gaps.size > BURST)
            // The burst is the point: the eighth exchange has to arrive in a fraction of the time the
            // settled cadence would have taken to reach it.
            val toEighth = gaps.take(BURST - 1).sum()
            assertTrue(
                "the first $BURST took $toEighth ms, no better than the settled cadence",
                toEighth < (BURST - 1) * SETTLED_MILLIS / 2
            )
            assertTrue("burst gaps were not fast: ${gaps.take(BURST - 1)}", gaps.take(BURST - 1).all { it < SETTLED_MILLIS / 2 })
            // And it has to stop bursting, or the sink pays the extra traffic for the whole session.
            assertTrue("the cadence never settled: ${gaps.drop(BURST)}", gaps.drop(BURST).all { it >= SETTLED_MILLIS / 2 })
        } finally {
            server.stop()
        }
    }

    /**
     * Callers that ask for no burst get exactly what they got before it existed. The pair
     * calibration screen is one: its 250 ms cadence is already a burst that never stops, and a
     * second one layered on it would change what every archived run is being compared against.
     */
    @Test
    fun aRunThatAsksForNoBurstKeepsOneCadenceThroughout() {
        val port = freePort()
        val server = ClockSyncServer(port)
        server.start()
        try {
            val client = ClockSyncClient("127.0.0.1", port, ClockOffsetEstimator())
            client.runFor(seconds = 1, intervalMillis = 60)
            val gaps = client.recordedExchanges().map { it.t1 }.zipWithNext { a, b -> (b - a) / 1_000_000L }

            assertTrue("too few exchanges: ${gaps.size}", gaps.size > 4)
            assertTrue("a cadence nobody asked to vary varied: $gaps", gaps.all { it >= 30 })
        } finally {
            server.stop()
        }
    }

    private companion object {
        const val BURST = 8
        const val BURST_MILLIS = 10L
        const val SETTLED_MILLIS = 200L
    }
}
