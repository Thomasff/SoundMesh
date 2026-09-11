package com.soundmesh.probe.sync

import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/**
 * Gathering a room before answering it, rather than answering each handset as it asks.
 *
 * A pair's plan can be minted on the accept because it names only the two handsets already in the
 * conversation. A room's cannot: it names every slot, so it does not exist until everybody has
 * asked. That inversion is the whole of what this adds, and the plan still has to be minted at the
 * end rather than the beginning - it names instants a few seconds out in the host's clock, and one
 * minted while waiting for somebody to pick up a second phone would already be in the past.
 */
class CalibrationRoomChannelTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val one = "a1b2c3d4e5f60718"
    private val two = "0918273645abcdef"
    private val three = "1122334455667788"

    private fun planNaming(asks: List<CalibrationRequest>) = CalibrationPlan(
        caseId = asks.first().caseId,
        hostId = "ffffffffffffffff",
        firstChirpAtHostNanos = 10_000_000_000L,
        staggerNanos = 500_000_000L,
        repeats = 2,
        intervalNanos = 5_000_000_000L,
        slotIds = asks.map { it.sinkId } + "ffffffffffffffff"
    )

    /** Every sink asks at once, as three people pressing three buttons roughly together do. */
    private fun askAll(port: Int, names: List<String>): List<CalibrationPlan?> {
        val got = Collections.synchronizedList(arrayOfNulls<CalibrationPlan>(names.size).toMutableList())
        val threads = names.mapIndexed { index, name ->
            Thread {
                got[index] = runCatching {
                    CalibrationPlanClient("127.0.0.1", port).request("C94", name)
                }.getOrNull()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }
        return got
    }

    @Test
    fun handsEveryHandsetOnePlanNamingThemAll() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        val names = listOf(one, two, three)
        try {
            val gathered = Thread { server.awaitRoom(8_000, 600, 20_000, ::planNaming) }
            gathered.start()

            val plans = askAll(port, names)
            gathered.join(20_000)

            assertTrue("every handset was answered", plans.all { it != null })
            // The same plan, not three plans that agree: a room where two handsets hold schedules
            // minted a moment apart is two rooms that will not add up.
            assertEquals(1, plans.map { it!! }.toSet().size)
            assertEquals(names.toSet() + "ffffffffffffffff", plans.first()!!.slotIds.toSet())
        } finally {
            server.stop()
        }
    }

    /** One plan, minted once, after the last handset asked rather than after the first. */
    @Test
    fun mintsThePlanOnceAndOnlyAfterEverybodyHasAsked() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        val mints = AtomicInteger()
        val asksSeen = AtomicInteger()
        try {
            val gathered = Thread {
                server.awaitRoom(8_000, 600, 20_000) { asks ->
                    mints.incrementAndGet()
                    asksSeen.set(asks.size)
                    planNaming(asks)
                }
            }
            gathered.start()

            askAll(port, listOf(one, two, three))
            gathered.join(20_000)

            assertEquals(1, mints.get())
            assertEquals(3, asksSeen.get())
        } finally {
            server.stop()
        }
    }

    /**
     * The room closes when nobody else asks, not when the long wait runs out. The long wait is for
     * somebody to pick up the first phone; once they have, the room is only as slow as the gap
     * between two people pressing two buttons.
     */
    @Test
    fun closesTheRoomOnceNobodyElseAsks() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        try {
            val started = System.nanoTime()
            val gathered = Thread { server.awaitRoom(30_000, 500, 25_000, ::planNaming) }
            gathered.start()
            askAll(port, listOf(one, two))
            gathered.join(20_000)

            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("waited the whole long wait: $tookMillis ms", tookMillis < 10_000)
        } finally {
            server.stop()
        }
    }

    /**
     * One handset speaking another version costs that handset and not the room. The alternative -
     * the whole room failing - makes one out-of-date phone look like a broken host.
     */
    @Test
    fun letsTheRestOfTheRoomThroughWhenOneAskCannotBeRead() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        try {
            val gathered = Thread { server.awaitRoom(8_000, 800, 20_000, ::planNaming) }
            gathered.start()
            Thread {
                runCatching {
                    java.net.Socket("127.0.0.1", port).use { socket ->
                        socket.getOutputStream().apply { write("hello".toByteArray()); flush() }
                        socket.shutdownOutput()
                        socket.getInputStream().readBytes()
                    }
                }
            }.also { it.start() }.join(5_000)

            val plans = askAll(port, listOf(one, two))
            gathered.join(20_000)

            assertTrue("the readable handsets were answered", plans.all { it != null })
            assertEquals(1, server.refusedSinks)
        } finally {
            server.stop()
        }
    }

    @Test
    fun saysNobodyCameRatherThanOpeningAnEmptyRoom() {
        val port = freePort()
        val server = CalibrationPlanServer(port)
        server.start()
        try {
            assertNull(server.awaitRoom(300, 300, 20_000, ::planNaming))
            assertEquals(CalibrationPlanServer.TIMEOUT, server.failureCode)
        } finally {
            server.stop()
        }
    }

    /**
     * The handset that asked first waits out the whole gathering, so a room held open longer than a
     * sink is willing to wait answers into a socket nobody is listening on any more - and the sink
     * reports a host that never replied while the host reports a room it served.
     */
    @Test
    fun refusesToHoldARoomOpenLongerThanASinkWillWait() {
        val server = CalibrationPlanServer(freePort())
        try {
            server.awaitRoom(1_000, 500, CalibrationPlanClient.REPLY_TIMEOUT_MILLIS, ::planNaming)
            throw AssertionError("expected to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("wait"))
        }
    }
}
