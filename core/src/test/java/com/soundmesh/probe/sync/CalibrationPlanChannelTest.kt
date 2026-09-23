package com.soundmesh.probe.sync

import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationRequest
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPlanChannelTest {
    private val SINK = "a1b2c3d4e5f60718"

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun planAt(atHostNanos: Long) = CalibrationPlan(
        caseId = "C1",
        hostId = "6cd33f5d070b332e",
        firstChirpAtHostNanos = atHostNanos,
        staggerNanos = 500_000_000L,
        repeats = 5,
        intervalNanos = 5_000_000_000L
    )

    @Test
    fun theHostsPlanReachesTheSinkWhole() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        try {
            val plan = planAt(100_000_000_000L)
            val received = ArrayBlockingQueue<CalibrationPlan>(1)
            Thread { received.put(CalibrationPlanClient("127.0.0.1", port).request("C90", SINK)) }.start()

            val served = server.awaitRequest(5_000) { plan }

            assertNull(server.failureCode)
            assertEquals(plan, served)
            assertEquals(plan, received.poll(5, TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }

    /**
     * The instants in a plan are in the host's own clock and only a few seconds out. A host that
     * minted its plan when it started waiting would hand out one already in the past by however
     * long it took somebody to pick up the other phone, and every chirp on it would be dropped as
     * late - a whole run lost to a delay that is entirely normal.
     */
    @Test
    fun thePlanIsMintedWhenTheSinkAsksRatherThanWhenTheHostStartedWaiting() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        val startedWaitingAt = System.nanoTime()
        try {
            Thread.sleep(100)
            val received = ArrayBlockingQueue<CalibrationPlan>(1)
            Thread { received.put(CalibrationPlanClient("127.0.0.1", port).request("C90", SINK)) }.start()

            val served = server.awaitRequest(5_000) { planAt(System.nanoTime()) }

            assertNotNull(served)
            assertTrue(
                "the plan was made before the sink asked for it",
                served!!.firstChirpAtHostNanos - startedWaitingAt >= 100_000_000L
            )
            assertEquals(served, received.poll(5, TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }

    /**
     * A sink that never connects has to leave the host able to say so. Without a bounded wait the
     * screen sits on "waiting" forever, with nothing to tell that from a run in progress.
     */
    @Test
    fun givesUpWhenNobodyAsks() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        try {
            assertNull(server.awaitRequest(200) { planAt(0L) })
            assertEquals("PLAN_TIMEOUT", server.failureCode)
        } finally {
            server.stop()
        }
    }

    /**
     * Which case a run is filed under is the sink's to say - it is the side that knows whether it
     * is measuring or checking - and the host has to hear it, or both kinds of run land in one
     * directory and the later one overwrites the earlier. The verification did exactly that to the
     * measurement's host-side artifacts before this was carried.
     */
    @Test
    fun theCaseTheSinkAsksForIsTheOneTheHostPlans() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        try {
            val asked = ArrayBlockingQueue<CalibrationRequest>(1)
            Thread { CalibrationPlanClient("127.0.0.1", port).request("C91", SINK) }.start()

            val served = server.awaitRequest(5_000) { request ->
                asked.put(request)
                planAt(100_000_000_000L).copy(caseId = request.caseId)
            }

            assertEquals(CalibrationRequest("C91", SINK), asked.poll(5, TimeUnit.SECONDS))
            assertEquals("C91", served?.caseId)
        } finally {
            server.stop()
        }
    }

    /**
     * The case id arrives over the network and names a directory the run store will create, so a
     * host that served whatever it was handed would write wherever it was told to. A host that
     * will not run the ask says so instead of throwing it at the run store.
     */
    @Test
    fun aCaseTheHostWillNotRunIsRefusedRatherThanServed() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        try {
            Thread { runCatching { CalibrationPlanClient("127.0.0.1", port).request("Z9", SINK) } }.start()

            val served = server.awaitRequest(5_000) { request ->
                require(request.caseId == "C90") { "not a case this handset runs: ${request.caseId}" }
                planAt(100_000_000_000L)
            }

            assertNull(served)
            assertEquals("PLAN_REFUSED", server.failureCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun aServerThatNeverBoundSaysSoRatherThanThrowing() {
        val server = CalibrationPlanServer(freePort())

        assertNull(server.awaitRequest(200) { planAt(0L) })
        assertEquals("PLAN_UNBOUND", server.failureCode)
    }
}
