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

    private val one = "a1b2c3d4e5f60718"
    private val two = "0918273645abcdef"

    private fun standBy(
        port: Int,
        selfId: String = one,
        onCommand: (RoomCommand) -> Unit
    ): RoomCommandClient = RoomCommandClient("127.0.0.1", port, selfId, onCommand).also { it.start() }

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
        val client = standBy(port, onCommand = onCommand)
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

            client = standBy(port, onCommand = onCommand)
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
        val client = standBy(port, onCommand = onCommand)
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

    /**
     * One handset that connected twice is one handset.
     *
     * Scanning a pairing code again opens a second line without the first one having said
     * anything about going away - nothing is ever written to it, and a socket nobody writes to
     * is a socket nobody notices die. Counting sockets, the host then says three handsets are
     * standing by in a room of two, which is what a listener hit on 09-11 by scanning twice.
     */
    @Test
    fun aHandsetThatConnectedTwiceIsCountedOnce() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (heard, onCommand) = waiting()
        val stale = standBy(port, one, onCommand)
        try {
            assertTrue(connected(stale))
            while (server.standingBy() == 0) Thread.sleep(20)

            val fresh = standBy(port, one, onCommand)
            assertTrue(connected(fresh))
            val until = System.nanoTime() + 10_000_000_000L
            while (server.standingBy() != 1 && System.nanoTime() < until) Thread.sleep(20)

            assertEquals(1, server.standingBy())
            // And it is the new line that is kept, not the one that happened to be first.
            server.send(RoomCommand.STOP)
            assertEquals(RoomCommand.STOP, heard.poll(5, TimeUnit.SECONDS))
            assertNull(heard.poll(500, TimeUnit.MILLISECONDS))
            fresh.close()
        } finally {
            stale.close()
            server.stop()
        }
    }

    /**
     * A handset that walks away stops being counted, without anything being sent to find out.
     *
     * The count is what a person reads before pressing the one button that starts three phones,
     * so it has to be true at that moment rather than true as of the last thing that was said.
     */
    @Test
    fun aHandsetThatLeavesStopsBeingCounted() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, one, onCommand)
        try {
            assertTrue(connected(client))
            while (server.standingBy() == 0) Thread.sleep(20)

            client.close()

            val until = System.nanoTime() + 10_000_000_000L
            while (server.standingBy() > 0 && System.nanoTime() < until) Thread.sleep(20)
            assertEquals(0, server.standingBy())
        } finally {
            client.close()
            server.stop()
        }
    }

    /** A socket that never says which handset it is gets nothing and is not counted. */
    @Test
    fun aLineThatNeverSaysWhoItIsIsNotAHandset() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        try {
            java.net.Socket("127.0.0.1", port).use {
                Thread.sleep(300)
                assertEquals(0, server.standingBy())
            }
        } finally {
            server.stop()
        }
    }

    /** How many are standing by is what the host screen shows, so it has to follow the sockets. */
    @Test
    fun theHostCountsWhoIsStandingBy() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val first = standBy(port, one, onCommand)
        val second = standBy(port, two, onCommand)
        try {
            assertTrue(connected(first))
            assertTrue(connected(second))
            val until = System.nanoTime() + 5_000_000_000L
            while (server.standingBy() < 2 && System.nanoTime() < until) Thread.sleep(20)

            assertEquals(2, server.standingBy())
        } finally {
            first.close()
            second.close()
            server.stop()
        }
    }

    /**
     * Saying it answers how many it was said to.
     *
     * 09-13: a listener pressed the one button four times over three minutes and no handset ever
     * arrived. From the host there was no way to tell whether nobody had been told or everybody
     * had been told and nobody came - two faults in two different handsets, one screen showing
     * the same thing for both. The count of handsets standing by cannot answer it either: obeying
     * means leaving the home screen, so by the time one arrives it has stopped being counted.
     */
    @Test
    fun `saying it answers how many handsets were told`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val first = standBy(port, one, onCommand)
        val second = standBy(port, two, onCommand)
        try {
            assertTrue(connected(first))
            assertTrue(connected(second))
            val until = System.nanoTime() + 5_000_000_000L
            while (server.standingBy() < 2 && System.nanoTime() < until) Thread.sleep(20)

            assertEquals(2, server.send(RoomCommand.PLAY))
        } finally {
            first.close()
            second.close()
            server.stop()
        }
    }

    /** And a host nobody is standing by for says nothing to nobody, which is the case to show. */
    @Test
    fun `a host with nobody standing by tells nobody`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        try {
            assertEquals(0, server.send(RoomCommand.MEASURE_ROOM))
        } finally {
            server.stop()
        }
    }
}
