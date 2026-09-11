package com.soundmesh.probe.sync

import com.soundmesh.core.RoomCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The standing channel: the one thing in this project that makes a handset act without anybody
 * touching it.
 */
class RoomCommandChannelTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waiting(): Pair<ArrayBlockingQueue<RoomCommand>, (RoomCommand) -> Unit> {
        val heard = ArrayBlockingQueue<RoomCommand>(8)
        return heard to { command -> heard.offer(command) }
    }

    private fun standBy(port: Int, onCommand: (RoomCommand) -> Unit): RoomCommandClient =
        RoomCommandClient("127.0.0.1", port, onCommand).also { it.start() }

    private fun connected(client: RoomCommandClient): Boolean {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            if (client.connected) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun aHandsetStandingByIsToldWhatTheHostSaid() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (heard, onCommand) = waiting()
        val client = standBy(port, onCommand)
        try {
            assertTrue(connected(client))

            server.send(RoomCommand.MEASURE_OVERHEAD)

            assertEquals(RoomCommand.MEASURE_OVERHEAD, heard.poll(5, TimeUnit.SECONDS))
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * Nothing is replayed to a handset that arrives afterwards.
     *
     * The other server on this shape - [SpatialFieldServer] - remembers its last rule precisely so
     * that a late joiner is caught up, and copying that here would be a bug wearing the clothes of
     * a feature: a rule says what the room is, a command says what happened at an instant. A phone
     * that walks in ten minutes later and starts playing on its own because the host once said so
     * is a phone nobody told to do anything.
     */
    @Test
    fun aHandsetThatArrivesAfterwardsIsToldNothing() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (heard, onCommand) = waiting()
        var client: RoomCommandClient? = null
        try {
            server.send(RoomCommand.PLAY)
            Thread.sleep(200)

            client = standBy(port, onCommand)
            assertTrue(connected(client))

            assertNull(heard.poll(1, TimeUnit.SECONDS))
        } finally {
            client?.close()
            server.stop()
        }
    }

    /**
     * A handset holds the line open again after the host puts its own down.
     *
     * The host end is bound while somebody is being a host and closed while they are not, so a
     * sink that gave up on one refused connection would need a person to press something - which
     * is the whole of what this exists to remove.
     */
    @Test
    fun aHandsetKeepsStandingByAcrossTheHostComingAndGoing() {
        val port = freePort()
        val first = RoomCommandServer(port)
        first.start()
        val (heard, onCommand) = waiting()
        val client = standBy(port, onCommand)
        try {
            assertTrue(connected(client))
            first.stop()

            val second = RoomCommandServer(port)
            second.start()
            try {
                val until = System.nanoTime() + 20_000_000_000L
                while (second.standingBy() == 0 && System.nanoTime() < until) Thread.sleep(20)
                assertEquals(1, second.standingBy())

                second.send(RoomCommand.STOP)

                assertEquals(RoomCommand.STOP, heard.poll(10, TimeUnit.SECONDS))
            } finally {
                second.stop()
            }
        } finally {
            client.close()
        }
    }

    /** How many are standing by is what the host screen shows, so it has to follow the sockets. */
    @Test
    fun theHostCountsWhoIsStandingBy() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val one = standBy(port, onCommand)
        val two = standBy(port, onCommand)
        try {
            assertTrue(connected(one))
            assertTrue(connected(two))
            val until = System.nanoTime() + 5_000_000_000L
            while (server.standingBy() < 2 && System.nanoTime() < until) Thread.sleep(20)

            assertEquals(2, server.standingBy())
        } finally {
            one.close()
            two.close()
            server.stop()
        }
    }
}
