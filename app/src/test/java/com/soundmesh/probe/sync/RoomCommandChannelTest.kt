package com.soundmesh.probe.sync

import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomOrder
import com.soundmesh.core.RoomExcuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The standing channel: the one thing in this project that makes a handset act without anybody
 * touching it.
 */
class RoomCommandChannelTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waiting(): Pair<ArrayBlockingQueue<RoomOrder>, (RoomOrder) -> Unit> {
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        return heard to { order -> heard.offer(order) }
    }

    private val one = "a1b2c3d4e5f60718"
    private val two = "0918273645abcdef"

    private fun standBy(
        port: Int,
        selfId: String = one,
        carrying: Long? = null,
        approximately: Long? = null,
        called: String? = null,
        volumeNow: (() -> VolumeSaid)? = null,
        onCommand: (RoomOrder) -> Unit
    ): RoomCommandClient =
        RoomCommandClient(
            "127.0.0.1", port, selfId, carrying, approximately, called, volumeNow, onCommand
        )
            .also { it.start() }

    /** The announce is read on a thread of its own, so what it said arrives after it connected. */
    private fun until(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun connected(client: RoomCommandClient): Boolean {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            if (client.connected) return true
            Thread.sleep(20)
        }
        return false
    }

    /**
     * "How many are standing by" used to mean "how many sockets nothing has failed to write to".
     *
     * That is not the same number. A handset that walks out of the network leaves every socket to
     * it ESTABLISHED for as long as the kernel keeps retransmitting - minutes - and nothing on
     * this channel is ever written in between, so the host goes on saying three phones are waiting
     * in a room with one. What separates them is that a handset that is there keeps saying so.
     */
    @Test
    fun stopsCountingAHandsetThatStoppedSayingItIsThere() {
        val port = freePort()
        val server = RoomCommandServer(port)
        val gone = ArrayBlockingQueue<String>(4)
        server.onLeft = { peerId, _ -> gone.offer(peerId) }
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, onCommand = onCommand)
        try {
            assertTrue(connected(client))
            assertTrue(client.sayHere())
            assertTrue(until { server.standingBy() == 1 })

            // Long enough after the last word from it that three beats have been missed.
            server.letGoOfTheQuiet(System.currentTimeMillis() + RoomCommandServer.GONE_QUIET_MILLIS)

            assertEquals(0, server.standingBy())
            assertEquals(one, gone.poll(5, TimeUnit.SECONDS))
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * And takes its volume control off the screen with it.
     *
     * Reported on 2026-09-14 from a room of two: the handset was correctly shown as dropped and
     * its slider stayed, still draggable, still reading whatever it last said. A control for a
     * phone that is not there is worse than no control - it is the only thing on that screen that
     * looks like it would do something.
     */
    @Test
    fun takesAwayTheVolumeOfAHandsetItLetGoOf() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, volumeNow = { VolumeSaid(5, 15, "MEDIA") }, onCommand = onCommand)
        try {
            assertTrue(connected(client))
            assertTrue(until { server.volumes().containsKey(one) })

            server.letGoOfTheQuiet(System.currentTimeMillis() + RoomCommandServer.GONE_QUIET_MILLIS)

            assertEquals(emptyMap<String, VolumeSaid>(), server.volumes())
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * Each "I am still here" puts the clock back, which is the whole point of repeating them.
     *
     * The two judgements above are both satisfied by the one said on the way in, so neither of
     * them would go red if every later one were thrown away - which is exactly the fault this
     * pair went to hardware with: a handset saying it on connecting and never again, let go of
     * every eight seconds for the rest of the evening.
     */
    @Test
    fun everySayingOfItPutsTheClockBack() {
        val port = freePort()
        val server = RoomCommandServer(port, quietAfterMillis = 400L)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, onCommand = onCommand)
        try {
            assertTrue(connected(client))
            assertTrue(until { server.standingBy() == 1 })

            // Ten times the window, beaten through: the one said on connecting is long stale.
            repeat(20) {
                assertTrue(client.sayHere())
                Thread.sleep(100)
                server.letGoOfTheQuiet(System.currentTimeMillis())
            }

            assertEquals(1, server.standingBy())
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * A handset that was let go and dialled again can still say things.
     *
     * The shape reported from a room of two on 2026-09-14: the host let go of a handset every
     * eight seconds, it came back three seconds later, and in between it wrote a line a second
     * into its own log saying nothing had gone out. This is that cycle, on this side of it.
     */
    @Test
    fun canStillSayThingsAfterBeingLetGoOfAndDiallingAgain() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, onCommand = onCommand)
        try {
            assertTrue(connected(client))
            assertTrue(client.sayHere())

            server.letGoOfTheQuiet(System.currentTimeMillis() + RoomCommandServer.GONE_QUIET_MILLIS)
            assertTrue("it never dialled again", until { server.standingBy() == 1 })
            assertTrue(connected(client))

            assertTrue("it said: ${client.lastRefusal}", client.sayHere())
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * And when something does not go out it says which of the three faults it was.
     *
     * They shared one sentence until 2026-09-14, and that sentence named the one fault it was
     * not: "no line to say it on", written every second while the host was reading commands off
     * that very socket. A wrong answer in a log costs a whole round of guessing.
     */
    @Test
    fun saysWhichOfTheThreeReasonsAThingDidNotGoOut() {
        val port = freePort()
        val (_, onCommand) = waiting()
        val client = standBy(port, onCommand = onCommand)
        try {
            assertFalse(client.sayHere())
            assertEquals("there is no socket yet", client.lastRefusal)

            val server = RoomCommandServer(port)
            server.start()
            assertTrue(connected(client))
            assertTrue(client.sayHere())
            // Cleared when the frame actually goes out, which is not on this thread.
            assertTrue(until { client.lastRefusal == null })

            server.stop()
            assertTrue(until { !client.connected })
            assertFalse(client.sayHere())
            assertEquals("the line is down", client.lastRefusal)
        } finally {
            client.close()
        }
    }

    /** And says nothing about it while it is still saying it is there. */
    @Test
    fun keepsCountingAHandsetThatIsStillSayingIt() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, onCommand = onCommand)
        try {
            assertTrue(connected(client))
            assertTrue(client.sayHere())
            assertTrue(until { server.standingBy() == 1 })

            server.letGoOfTheQuiet(System.currentTimeMillis() + RoomCommandServer.GONE_QUIET_MILLIS - 1_000L)

            assertEquals(1, server.standingBy())
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * A handset of a build that never learned to say it is there is left alone rather than
     * declared gone.
     *
     * Those builds are in the room: the APKs are handed round by hand, and on 09-14 a handset
     * running one from two commits earlier obeyed everything and reported nothing. Judging a
     * handset by a signal it does not send would report every one of them as dropped while it sat
     * on somebody's home screen doing exactly what it was told.
     */
    @Test
    fun leavesAloneAHandsetThatHasNeverSaidItIsThere() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            socket.getOutputStream().apply { write(SpatialFrame.encode(two)); flush() }
            assertTrue(until { server.standingBy() == 1 })

            server.letGoOfTheQuiet(System.currentTimeMillis() + 10 * RoomCommandServer.GONE_QUIET_MILLIS)

            assertEquals(1, server.standingBy())
            socket.close()
        } finally {
            server.stop()
        }
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

            assertEquals(RoomOrder(RoomCommand.MEASURE_OVERHEAD), heard.poll(5, TimeUnit.SECONDS))
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

                assertEquals(RoomOrder(RoomCommand.STOP), heard.poll(10, TimeUnit.SECONDS))
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
        val stale = standBy(port, one, onCommand = onCommand)
        try {
            assertTrue(connected(stale))
            while (server.standingBy() == 0) Thread.sleep(20)

            val fresh = standBy(port, one, onCommand = onCommand)
            assertTrue(connected(fresh))
            val until = System.nanoTime() + 10_000_000_000L
            while (server.standingBy() != 1 && System.nanoTime() < until) Thread.sleep(20)

            assertEquals(1, server.standingBy())
            // And it is the new line that is kept, not the one that happened to be first.
            server.send(RoomCommand.STOP)
            assertEquals(RoomOrder(RoomCommand.STOP), heard.poll(5, TimeUnit.SECONDS))
            assertNull(heard.poll(500, TimeUnit.MILLISECONDS))
            fresh.close()
        } finally {
            stale.close()
            server.stop()
        }
    }

    /**
     * A handset going says which of the three ways it went.
     *
     * The count on screen going down is the only trace otherwise, and a number cannot tell a
     * phone put in a pocket from one whose write failed under it from one replaced by a second
     * line of its own. On 09-13 a room went from three standing to none and there was nothing
     * afterwards to read.
     */
    @Test
    fun saysWhichWayAHandsetWent() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val gone = ArrayBlockingQueue<Pair<String, String>>(8)
        server.onLeft = { peerId, why -> gone.offer(peerId to why) }
        val (_, onCommand) = waiting()
        val client = standBy(port, one, onCommand = onCommand)
        try {
            assertTrue(connected(client))
            assertTrue(until { server.standingBy() == 1 })

            client.close()

            val went = gone.poll(5, TimeUnit.SECONDS)
            assertEquals(one, went?.first)
            assertTrue("$went", went!!.second.isNotEmpty())
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * One handset can be told something the rest of the room is not.
     *
     * For the phone standing next to a wall. The room is still the default and this is the
     * exception: a room told one handset at a time is a room nobody can get level again, which
     * is why the next drag of the room slider goes to everybody including this one.
     */
    @Test
    fun tellsOneHandsetWithoutTellingTheRoom() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (mine, onMine) = waiting()
        val (theirs, onTheirs) = waiting()
        val client = standBy(port, one, onCommand = onMine)
        val other = standBy(port, two, onCommand = onTheirs)
        try {
            assertTrue(connected(client))
            assertTrue(connected(other))
            assertTrue(until { server.standingBy() == 2 })

            assertTrue(server.sendTo(two, RoomOrder(RoomCommand.SET_VOLUME, 40)))

            assertEquals(RoomOrder(RoomCommand.SET_VOLUME, 40), theirs.poll(5, TimeUnit.SECONDS))
            assertNull("the rest of the room was told too", mine.poll(500, TimeUnit.MILLISECONDS))

            // And the other way, in the same test: told to whichever was named, rather than to
            // whichever happens to be first in a list two threads raced to join.
            assertTrue(server.sendTo(one, RoomOrder(RoomCommand.SET_VOLUME, 20)))

            assertEquals(RoomOrder(RoomCommand.SET_VOLUME, 20), mine.poll(5, TimeUnit.SECONDS))
            assertNull("the rest of the room was told too", theirs.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            client.close()
            other.close()
            server.stop()
        }
    }

    /** A handset that is not standing by is not told, and the caller finds that out. */
    @Test
    fun saysSoWhenTheHandsetItNamedIsNotThere() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        try {
            assertFalse(server.sendTo(two, RoomOrder(RoomCommand.SET_VOLUME, 40)))
        } finally {
            server.stop()
        }
    }

    /**
     * A handset says what its volume actually came to, and the host keeps the latest per handset.
     *
     * Said rather than assumed, and that is the whole of the message: setStreamVolume has been
     * seen on these handsets to take a value, throw nothing and move nothing, and under
     * do-not-disturb it throws instead. A host that showed what it asked for would show a room in
     * agreement that is not one - and the disagreement is invisible by construction otherwise.
     */
    @Test
    fun keepsWhatEachHandsetSaysItsVolumeCameTo() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, one, onCommand = onCommand)
        try {
            assertTrue(connected(client))
            while (server.standingBy() == 0) Thread.sleep(20)

            client.sayVolume(9, 15, "MEDIA")

            assertTrue(until { server.volumes()[one] != null })
            assertEquals(VolumeSaid(9, 15, "MEDIA"), server.volumes()[one])
            // The percentage is worked out from the two numbers, because that is the only form
            // of it every handset in a room can be compared in.
            assertEquals(60, server.volumes()[one]!!.percent)

            // A state and not an event: the newest replaces the last rather than joining it.
            client.sayVolume(3, 15, "MEDIA")

            assertTrue(until { server.volumes()[one]?.index == 3 })
            assertEquals(1, server.volumes().size)
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * A handset says what its volume is as it arrives, without being asked.
     *
     * The host keeps these per connection, so a host that has just started - or a handset that
     * has just come back from measuring and dialled again - has no line for it at all. The
     * control for one handset on its own is drawn from that line, so until the first room-wide
     * change reached everybody there was nothing on screen to set one phone with. Reported on
     * 2026-09-13 as being unable to set a single handset from the host.
     */
    @Test
    fun saysItsVolumeOnTheWayInRatherThanWaitingToBeTold() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, one, volumeNow = { VolumeSaid(6, 15, "MEDIA") }, onCommand = onCommand)
        try {
            assertTrue(until { server.volumes()[one] != null })
            assertEquals(VolumeSaid(6, 15, "MEDIA"), server.volumes()[one])
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * Saying a volume answers whether it actually went out.
     *
     * The handset writes down what it has said so that it stops repeating itself, and until this
     * answered, a write that never left - no line open yet, or one that had just gone - was
     * written down as said all the same. From then on that handset never mentioned its volume
     * again: the host row for it froze at whatever last got through while the phone itself went
     * on changing. Reported on 2026-09-13 as two handsets whose sliders snapped back while their
     * volume really did move.
     */
    @Test
    fun sayingAVolumeAnswersWhetherItWentOut() {
        val port = freePort()
        val (_, onCommand) = waiting()
        // Started against a port nothing is listening on, which is every handset for the first
        // moment of its life and any handset whose host has gone away.
        val client = standBy(port, one, onCommand = onCommand)
        try {
            assertFalse(client.sayVolume(6, 15, "MEDIA"))

            val server = RoomCommandServer(port)
            server.start()
            try {
                assertTrue(until { client.sayVolume(9, 15, "MEDIA") })
                assertTrue(until { server.volumes()[one]?.index == 9 })
            } finally {
                server.stop()
            }
        } finally {
            client.close()
        }
    }

    /** Nobody has said anything about a handset that has never been told to change. */
    @Test
    fun saysNothingAboutAHandsetThatHasNotSaid() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, one, onCommand = onCommand)
        try {
            assertTrue(connected(client))
            while (server.standingBy() == 0) Thread.sleep(20)

            assertTrue(server.volumes().isEmpty())
        } finally {
            client.close()
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
        val client = standBy(port, one, onCommand = onCommand)
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
        val first = standBy(port, one, onCommand = onCommand)
        val second = standBy(port, two, onCommand = onCommand)
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
        val first = standBy(port, one, onCommand = onCommand)
        val second = standBy(port, two, onCommand = onCommand)
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

    /**
     * The constant that decides whether a handset plays in time lives on that handset, keyed by
     * the host it follows, so the host cannot look it up. Said on the way in, because the room
     * screen is where somebody is standing when it matters - on 2026-09-13 two handsets played a
     * whole afternoon carrying nothing, and what found it was a listener saying one sounded early.
     */
    @Test
    fun `a handset carrying no constant says so, and the host counts it`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, carrying = null, onCommand = onCommand)
        try {
            assertTrue(connected(client))

            assertTrue("the host never heard what it carries", until { server.uncalibrated() == 1 })
            assertEquals(1, server.standingBy())
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun `a handset carrying a constant is not one of the unmeasured`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, carrying = 35_352L, onCommand = onCommand)
        try {
            assertTrue(connected(client))

            // Waited for, not assumed: uncalibrated() is also 0 before it has said anything.
            assertTrue("the host never heard what it carries", until { server.unsaid() == 0 })
            assertEquals(0, server.uncalibrated())
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * A build from before this said nothing, and silence is its own answer: calling it
     * uncalibrated would send somebody to recalibrate a handset that is already fine.
     */
    @Test
    fun `a handset that never says what it carries is not called uncalibrated`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            socket.getOutputStream().apply {
                write(SpatialFrame.encode(one))
                flush()
            }

            assertTrue(until { server.standingBy() == 1 })
            assertTrue(until { server.unsaid() == 1 })
            assertEquals("an older build was called uncalibrated", 0, server.uncalibrated())
        } finally {
            runCatching { socket.close() }
            server.stop()
        }
    }

    /**
     * A handset correcting off a room round is neither of the other two.
     *
     * Counted apart because the two ask for different things from whoever is reading the host
     * screen: an unmeasured handset is tens of milliseconds out and has to be fixed before
     * anybody presses play, and this one is about a millisecond out and can wait.
     */
    @Test
    fun `a handset correcting off a room round says so, and is counted on its own`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, approximately = -35_948L, onCommand = onCommand)
        try {
            assertTrue(connected(client))

            assertTrue("the host never heard what it carries", until { server.approximate() == 1 })
            assertEquals("a room round was called a measurement", 0, server.unsaid())
            assertEquals("a room round was called no correction at all", 0, server.uncalibrated())
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * A handset that says why it is not measuring is heard, and is not standing by.
     *
     * Both halves matter and the second one more. It is on its way to doing nothing, so counting
     * it would put the host back where 09-13 left it: a number on screen that does not mean what
     * it says. The count of who is holding the line has to stay a count of who is holding the line.
     */
    @Test
    fun `a handset says why it is not measuring, and is not counted as standing by`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val heard = ArrayBlockingQueue<Pair<String, RoomExcuse>>(4)
        server.onExcuse = { peerId, excuse -> heard.offer(peerId to excuse) }
        try {
            tellHostWhy("127.0.0.1", port, one, RoomExcuse.NO_MICROPHONE)

            assertEquals(one to RoomExcuse.NO_MICROPHONE, heard.poll(5, TimeUnit.SECONDS))
            assertEquals(mapOf(one to RoomExcuse.NO_MICROPHONE), server.excuses())
            // Given a moment to be wrong in: the socket is closed from the far end, and a count
            // read too early would pass whether or not this works.
            Thread.sleep(300)
            assertEquals("an apology was counted as a handset standing by", 0, server.standingBy())
            assertEquals(0, server.uncalibrated())
        } finally {
            server.stop()
        }
    }

    /** An excuse is about one press of one button, so a round that starts drops the last one. */
    @Test
    fun `starting a round forgets what was said about the last one`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        try {
            tellHostWhy("127.0.0.1", port, one, RoomExcuse.SLOW_LINK)
            assertTrue(until { server.excuses().isNotEmpty() })

            server.forgetExcuses()

            assertEquals(emptyMap<String, RoomExcuse>(), server.excuses())
        } finally {
            server.stop()
        }
    }

    /**
     * A handset says what to call it, and the host still knows after it has gone.
     *
     * Kept past the socket on purpose, because every screen that names a handset names one that
     * has just left this channel: obeying means leaving the home screen, so a handset is never
     * standing by at the moment its result or its excuse arrives.
     */
    @Test
    fun `a handset says what to call it, and it is still known after it leaves`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, one, called = "Thomas de Ping Ban", onCommand = onCommand)
        try {
            assertTrue(connected(client))
            assertTrue("the host never heard what to call it", until { server.nameOf(one) != null })

            client.close()

            assertTrue(until { server.standingBy() == 0 })
            assertEquals("the name went with the socket", "Thomas de Ping Ban", server.nameOf(one))
        } finally {
            client.close()
            server.stop()
        }
    }

    /**
     * Two handsets called the same thing are told apart, and only then.
     *
     * A name is chosen by a person, so a room of two identical phones is the expected case rather
     * than a strange one. The fallback is the half of the identity that cannot collide - which is
     * unreadable, which is why it appears only when the readable half has run out.
     */
    @Test
    fun `two handsets called the same thing are told apart by their ids`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val first = standBy(port, one, called = "phone", onCommand = onCommand)
        val second = standBy(port, two, called = "phone", onCommand = onCommand)
        try {
            assertTrue(connected(first))
            assertTrue(connected(second))
            assertTrue(until { server.nameOf(one) != null && server.nameOf(two) != null })

            assertEquals("phone (${one.takeLast(4)})", server.nameOf(one))
            assertEquals("phone (${two.takeLast(4)})", server.nameOf(two))
        } finally {
            first.close()
            second.close()
            server.stop()
        }
    }

    /** A handset from before this says nothing, and nothing is not a name. */
    @Test
    fun `a handset that never says what to call it has no name here`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val client = standBy(port, one, called = null, onCommand = onCommand)
        try {
            assertTrue(connected(client))
            assertTrue(until { server.standingBy() == 1 })
            Thread.sleep(300)

            assertNull(server.nameOf(one))
        } finally {
            client.close()
            server.stop()
        }
    }

    /** A handset that walks out and back is one handset, and one answer, not two. */
    @Test
    fun `a handset that reconnects is counted once`() {
        val port = freePort()
        val server = RoomCommandServer(port)
        server.start()
        val (_, onCommand) = waiting()
        val first = standBy(port, carrying = null, onCommand = onCommand)
        assertTrue(connected(first))
        assertTrue(until { server.uncalibrated() == 1 })
        first.close()
        val second = standBy(port, carrying = null, onCommand = onCommand)
        try {
            assertTrue(connected(second))

            assertTrue(until { server.standingBy() == 1 && server.uncalibrated() == 1 })
        } finally {
            second.close()
            server.stop()
        }
    }
}
