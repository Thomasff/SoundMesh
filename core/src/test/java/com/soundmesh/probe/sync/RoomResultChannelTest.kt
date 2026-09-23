package com.soundmesh.probe.sync

import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.RoomReply
import com.soundmesh.core.RoomResultMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gathering every handset's hearing before answering any of it.
 *
 * A pair's result server answers on the accept because the pair's answer is made of the two halves
 * already in hand. A room's cannot: the pair between two sinks is made of two deliveries this host
 * has yet to receive, so nothing is answerable until everything has arrived.
 */
class RoomResultChannelTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val one = "a1b2c3d4e5f60718"
    private val two = "0918273645abcdef"
    private val three = "1122334455667788"

    private fun heard(index: Int) = ChirpArrival(
        index = index,
        peak = 40_000.0,
        floor = 1_000.0,
        ratio = 40.0,
        atSearchEdge = false
    )

    private fun hearing(name: String, slot: Int) =
        RoomResultMessage("C94", name, slot, listOf(listOf(heard(48_000), heard(72_000), heard(96_000))))

    /** Every handset delivers at once, which is what finishing the same window looks like. */
    private fun deliverAll(port: Int, names: List<String>): List<RoomReply?> {
        val got = Collections.synchronizedList(arrayOfNulls<RoomReply>(names.size).toMutableList())
        val threads = names.mapIndexed { index, name ->
            Thread {
                got[index] = runCatching {
                    RoomResultClient("127.0.0.1", port).exchange(hearing(name, index))
                }.getOrNull()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }
        return got
    }

    @Test
    fun answersEveryHandsetWithWhatTheWholeRoomMadeOfIt() {
        val port = freePort()
        val server = RoomResultServer(port)
        server.start()
        val names = listOf(one, two, three)
        try {
            var gathered: List<RoomResultMessage> = emptyList()
            val room = Thread {
                gathered = server.awaitRoom(names.size, 20_000) { delivered ->
                    delivered.associate { it.senderId to RoomReply(delivered.size, delivered.size - 1) }
                }
            }
            room.start()

            val replies = deliverAll(port, names)
            room.join(20_000)

            assertEquals(names.size, gathered.size)
            assertEquals(names.toSet(), gathered.map { it.senderId }.toSet())
            assertTrue("every handset was answered", replies.all { it != null })
            assertEquals(setOf(RoomReply(3, 2)), replies.map { it!! }.toSet())
            assertNull(server.failureCode)
        } finally {
            server.stop()
        }
    }

    /**
     * The inversion itself. Combining on the accept would answer the first handset out of a room
     * that was still filling up, and a pair between two sinks would never be answered at all.
     */
    @Test
    fun combinesOnceAndOnlyAfterEverybodyHasDelivered() {
        val port = freePort()
        val server = RoomResultServer(port)
        server.start()
        val combines = AtomicInteger()
        var sawAtCombine = 0
        try {
            val room = Thread {
                server.awaitRoom(3, 20_000) { delivered ->
                    combines.incrementAndGet()
                    sawAtCombine = delivered.size
                    delivered.associate { it.senderId to RoomReply(delivered.size, 2) }
                }
            }
            room.start()

            deliverAll(port, listOf(one, two, three))
            room.join(20_000)

            assertEquals("combined once", 1, combines.get())
            assertEquals("combined over the whole room", 3, sawAtCombine)
        } finally {
            server.stop()
        }
    }

    /**
     * A handset that went quiet costs its own pairs, not everybody else's. Refusing the room would
     * throw away the measurement the rest of it did make.
     */
    @Test
    fun answersTheHandsetsThatCameWhenOneNeverDoes() {
        val port = freePort()
        val server = RoomResultServer(port)
        server.start()
        try {
            var gathered: List<RoomResultMessage> = emptyList()
            val room = Thread {
                gathered = server.awaitRoom(3, 2_000) { delivered ->
                    delivered.associate { it.senderId to RoomReply(delivered.size, 1) }
                }
            }
            room.start()

            val replies = deliverAll(port, listOf(one, two))
            room.join(20_000)

            assertEquals(2, gathered.size)
            assertTrue("the two that came were answered", replies.all { it != null })
            assertEquals("ROOM_SHORT", server.failureCode)
        } finally {
            server.stop()
        }
    }

    /** One handset speaking a wire this build does not know must not cost the room. */
    @Test
    fun letsTheRestOfTheRoomThroughWhenOneDeliveryCannotBeRead() {
        val port = freePort()
        val server = RoomResultServer(port)
        server.start()
        try {
            var gathered: List<RoomResultMessage> = emptyList()
            val room = Thread {
                gathered = server.awaitRoom(2, 4_000) { delivered ->
                    delivered.associate { it.senderId to RoomReply(delivered.size, 0) }
                }
            }
            room.start()
            val garbled = Thread {
                runCatching {
                    java.net.Socket("127.0.0.1", port).use { socket ->
                        socket.getOutputStream().apply { write("hello".toByteArray()); flush() }
                        socket.shutdownOutput()
                        socket.getInputStream().readBytes()
                    }
                }
            }
            garbled.start()
            garbled.join(10_000)

            val replies = deliverAll(port, listOf(one, two))
            room.join(20_000)

            assertEquals(2, gathered.size)
            assertTrue("the readable handsets were answered", replies.all { it != null })
            assertEquals(1, server.unreadableSenders)
        } finally {
            server.stop()
        }
    }

    @Test
    fun saysNobodyDeliveredRatherThanCombiningAnEmptyRoom() {
        val port = freePort()
        val server = RoomResultServer(port)
        server.start()
        try {
            val combines = AtomicInteger()

            val gathered = server.awaitRoom(2, 1_000) { combines.incrementAndGet(); emptyMap() }

            assertEquals(emptyList<RoomResultMessage>(), gathered)
            assertEquals("ROOM_NOBODY_DELIVERED", server.failureCode)
            // Called all the same, and handed nothing: what it does with an empty room is the
            // caller's answer to give, not this server's to assume.
            assertEquals(1, combines.get())
        } finally {
            server.stop()
        }
    }

    /**
     * The handset that delivers first waits out the whole gathering. A room held open longer than
     * that answers into a socket nobody is listening on, and both sides then report something
     * self-consistent and contradictory.
     */
    @Test
    fun refusesToHoldARoomOpenLongerThanAHandsetWillWait() {
        val server = RoomResultServer(freePort())
        server.start()
        try {
            val thrown = runCatching {
                server.awaitRoom(2, RoomResultClient.REPLY_TIMEOUT_MILLIS) { emptyMap() }
            }.exceptionOrNull()

            assertTrue("$thrown", thrown is IllegalArgumentException)
            assertTrue(thrown!!.message, thrown.message!!.contains("outlasts"))
        } finally {
            server.stop()
        }
    }
}
