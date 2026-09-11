package com.soundmesh.probe.sync

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialFieldCodec
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class SpatialFieldChannelTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val here = "a1b2c3d4e5f60718"
    private val there = "0918273645abcdef"

    private fun field(mode: SpatialMode) = SpatialField(
        mode = mode,
        layout = SpatialLayout(
            listOf(
                SpatialPosition(here, -1.0, 0.5),
                SpatialPosition(there, 1.0, 0.5)
            )
        )
    )

    /** Polls rather than sleeps a fixed span: delivery crosses two threads and a socket. */
    private fun awaitTrue(what: String, test: () -> Boolean) {
        val deadline = System.nanoTime() + 6_000_000_000L
        while (System.nanoTime() < deadline) {
            if (test()) return
            Thread.sleep(5L)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun aSinkReceivesTheRuleTheHostPublishes() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        val seen = AtomicReference<SpatialField?>(null)
        val client = SpatialFieldClient("127.0.0.1", port, here) { seen.set(it) }
        server.start()
        try {
            client.start()
            awaitTrue("the sink to connect") { server.clientCount() == 1 }
            server.publish(field(SpatialMode.ROTATE))

            awaitTrue("the rule to arrive") { seen.get() != null }
            assertEquals(SpatialMode.ROTATE, seen.get()!!.mode)
            assertEquals(2, seen.get()!!.layout.positions.size)
        } finally {
            client.stop()
            server.stop()
        }
    }

    /**
     * The defect the remembered rule exists for. A handset joining between two touches of the
     * screen would otherwise render nothing until the listener happened to move a control, and a
     * room silently one handset short of the drawing is a room nobody would think to check.
     */
    @Test
    fun aSinkThatJoinsLaterIsToldTheRuleWithoutAnybodyTouchingAnything() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        val seen = AtomicReference<SpatialField?>(null)
        val client = SpatialFieldClient("127.0.0.1", port, here) { seen.set(it) }
        server.start()
        try {
            server.publish(field(SpatialMode.SPLIT))
            client.start()

            awaitTrue("the rule to arrive") { seen.get() != null }
            assertEquals(SpatialMode.SPLIT, seen.get()!!.mode)
        } finally {
            client.stop()
            server.stop()
        }
    }

    /**
     * One rule a handset cannot read must not leave it deaf to the next one - which is what
     * ending the stream on the first unreadable message would do. The count is what turns a build
     * speaking another version into something a report can say.
     */
    @Test
    fun anUnreadableRuleIsCountedAndTheNextOneStillArrives() {
        val port = freePort()
        val listener = ServerSocket(port)
        val seen = AtomicReference<SpatialField?>(null)
        val client = SpatialFieldClient("127.0.0.1", port, here) { seen.set(it) }
        try {
            val accepted = Thread {
                listener.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(SpatialFrame.encode("soundmesh-spatial 99 ROTATE 1 0.0 0 0"))
                        write(SpatialFrame.encode(SpatialFieldCodec.encode(field(SpatialMode.PAN))))
                        flush()
                    }
                    awaitTrue("the readable rule to arrive") { seen.get() != null }
                }
            }
            accepted.start()
            client.start()
            accepted.join(5_000L)

            assertEquals(SpatialMode.PAN, seen.get()?.mode)
            assertEquals(1, client.unreadableRules())
        } finally {
            client.stop()
            listener.close()
        }
    }

    /** A rule carries a line per handset, so the frame's length is what ends it, not a newline. */
    @Test
    fun aRuleWithALinePerHandsetSurvivesFraming() {
        val text = SpatialFieldCodec.encode(field(SpatialMode.ROTATE))
        val framed = SpatialFrame.encode(text)

        assertEquals(text, SpatialFrame.read(ByteArrayInputStream(framed)))
        assertTrue("the payload spans lines", text.contains("\n"))
    }

    /**
     * A sender that vanished mid-message reads as no message rather than as a short one. A partial
     * rule that decoded would be a room with handsets missing, which is itself a valid room.
     */
    @Test
    fun aFrameCutShortYieldsNothing() {
        val framed = SpatialFrame.encode(SpatialFieldCodec.encode(field(SpatialMode.ROTATE)))

        assertNull(SpatialFrame.read(ByteArrayInputStream(framed.copyOfRange(0, framed.size - 1))))
        assertNull(SpatialFrame.read(ByteArrayInputStream(framed.copyOfRange(0, 2))))
    }

    /**
     * Published until it lets go rather than once: a socket the far end closed accepts the first
     * write into its buffer and fails the one after, so a single publish is not the guarantee.
     * What is guaranteed is that a sink which left stops being counted, not when.
     */
    @Test
    fun theServerEventuallyLetsGoOfASinkThatDisconnected() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        server.start()
        try {
            val socket = Socket("127.0.0.1", port)
            socket.getOutputStream().apply { write(SpatialFrame.encode(here)); flush() }
            awaitTrue("the sink to connect") { server.clientCount() == 1 }
            socket.close()

            awaitTrue("the sink to be let go") {
                server.publish(field(SpatialMode.ROTATE))
                server.clientCount() == 0
            }
        } finally {
            server.stop()
        }
    }
    /**
     * A handset that comes back is the same handset, and the roster has to say so once.
     *
     * The roster holds a name until a write to that socket fails, which is a write nobody makes
     * while nobody is touching the drawing - so a sink that dropped and returned was in it twice,
     * and a drawing cannot be made of a room where one handset stands in two places. It took the
     * host's process down.
     *
     * The waiting here is on the replacement having been *registered*, not on a count: a count of
     * one is also what the roster reads a moment before the second connection joins it, so a test
     * that waited for that would pass against the very bug it is here for. A rule published ahead
     * of time is pushed to a sink as it registers, so reading it is the sink saying it is in.
     */
    @Test
    fun aSinkThatComesBackIsInTheRoomOnceNotTwice() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        server.start()
        try {
            val first = Socket("127.0.0.1", port)
            first.getOutputStream().apply { write(SpatialFrame.encode(here)); flush() }
            awaitTrue("the first connection to be named") { server.peerIds() == listOf(here) }
            server.publish(field(SpatialMode.ROTATE))
            assertEquals(
                SpatialFieldCodec.encode(field(SpatialMode.ROTATE)),
                SpatialFrame.read(first.getInputStream().buffered())
            )

            val second = Socket("127.0.0.1", port)
            second.getOutputStream().apply { write(SpatialFrame.encode(here)); flush() }
            // Only a registered sink is handed the standing rule, so this read is the handshake.
            assertEquals(
                SpatialFieldCodec.encode(field(SpatialMode.ROTATE)),
                SpatialFrame.read(second.getInputStream().buffered())
            )

            assertEquals(listOf(here), server.peerIds())
            assertEquals(1, server.clientCount())
            // And the one it replaced is closed, not left parked on a thread of its own.
            awaitTrue("the replaced connection to be closed") {
                first.soTimeout = 50
                runCatching { first.getInputStream().read() == -1 }.getOrDefault(false)
            }
            second.close()
        } finally {
            server.stop()
        }
    }

    /**
     * Two different handsets are two entries, which is the half of this the other test cannot see.
     *
     * A roster that answered "one" to everything would pass the test above and lose a phone.
     */
    @Test
    fun twoHandsetsAreStillTwoEntries() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        server.start()
        try {
            val one = Socket("127.0.0.1", port)
            one.getOutputStream().apply { write(SpatialFrame.encode(here)); flush() }
            awaitTrue("the first handset to be named") { server.peerIds() == listOf(here) }

            val other = Socket("127.0.0.1", port)
            other.getOutputStream().apply { write(SpatialFrame.encode(there)); flush() }
            awaitTrue("both handsets to be named") { server.peerIds().size == 2 }

            assertEquals(setOf(here, there), server.peerIds().toSet())
            one.close()
            other.close()
        } finally {
            server.stop()
        }
    }

    /**
     * What the roster is for. Chunks travel over anonymous sockets, so before the announce the
     * host could count its sinks and not name one - and an icon that is not a particular handset
     * is an icon dragging moves nothing in particular.
     */
    @Test
    fun theHostLearnsTheNameOfEverySinkThatJoined() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        val first = SpatialFieldClient("127.0.0.1", port, here) {}
        val second = SpatialFieldClient("127.0.0.1", port, there) {}
        server.start()
        try {
            first.start()
            second.start()

            awaitTrue("both sinks to be named") { server.peerIds().size == 2 }
            assertEquals(setOf(here, there), server.peerIds().toSet())
        } finally {
            first.stop()
            second.stop()
            server.stop()
        }
    }

    /**
     * A sink that says nothing usable is let go rather than kept as an anonymous socket the host
     * would have to send rules to without being able to draw it. Counted, because from the room
     * this looks like one handset quietly not joining in - and a build speaking another version
     * would do it to every handset at once.
     */
    @Test
    fun aSinkThatNeverSaysItsNameIsLetGoAndCounted() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        server.start()
        try {
            val socket = Socket("127.0.0.1", port)
            socket.getOutputStream().apply { write(SpatialFrame.encode("not-a-host-id")); flush() }

            awaitTrue("the nameless sink to be let go") { server.unnamedSinks() == 1 }
            assertEquals(0, server.clientCount())
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun aHandsetWithoutAUsableNameOfItsOwnRefusesToJoin() {
        val thrown = runCatching { SpatialFieldClient("127.0.0.1", 1, "nope") {} }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }
/**
     * The other half of the same guarantee, and the one that leaks rather than misbehaves: a sink
     * that connects and then says nothing at all would park a thread and hold a socket the roster
     * never learned about, so stop() would not close it either.
     */
    @Test
    fun aSinkThatConnectsAndSaysNothingIsLetGoToo() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        server.start()
        try {
            val socket = Socket("127.0.0.1", port)

            awaitTrue("the silent sink to be let go") { server.unnamedSinks() == 1 }
            assertEquals(0, server.clientCount())
            socket.close()
        } finally {
            server.stop()
        }
    }
    /**
     * A sink that joins between two songs still learns which one is playing.
     *
     * The same property the rule has, and needed for the same reason but more so: a rule changes
     * when somebody touches the screen, and a song changes once every few minutes. A handset that
     * connected just after a song started and was told nothing would sit there naming nothing for
     * the length of a song - seventeen minutes, on the file this was tested against.
     */
    @Test
    fun aSinkThatJoinsBetweenSongsIsStillToldWhichOneIsPlaying() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        val playing = AtomicReference<String?>(null)
        val client = SpatialFieldClient("127.0.0.1", port, here, onNowPlaying = { playing.set(it) }) {}
        server.start()
        try {
            // Said before anybody is listening, which is the case under test.
            server.publishNowPlaying("倔强.mp3")
            client.start()

            awaitTrue("the name to arrive") { playing.get() != null }
            assertEquals("倔强.mp3", playing.get())
        } finally {
            client.stop()
            server.stop()
        }
    }

    /**
     * The rule and the name are remembered separately, so a sink that missed both is told both.
     *
     * This is the deterministic half of "one does not throw the other away". The other half - two
     * published a moment apart while a sink is already connected - **cannot be tested from out
     * here**, and a test that looked like it was is worse than none: the first attempt published a
     * rule and a name back to back and asserted both arrived, and it passed just as happily with
     * the two sharing one slot, because the sender thread drains within microseconds of the first
     * publish and the collision window is never open when the test looks. What is pinned here is
     * the storage, which is the part a mutation can actually reach.
     */
    @Test
    fun aSinkThatMissedBothIsToldBoth() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        val seen = AtomicReference<SpatialField?>(null)
        val playing = AtomicReference<String?>(null)
        server.start()
        try {
            // Both before anybody is listening, so neither can be delivered as it is published.
            server.publish(field(SpatialMode.SPLIT))
            server.publishNowPlaying("和你一样.mp3")
            val client = SpatialFieldClient(
                "127.0.0.1", port, here, onNowPlaying = { playing.set(it) }
            ) { seen.set(it) }
            try {
                client.start()

                awaitTrue("the name to arrive") { playing.get() != null }
                awaitTrue("the rule to arrive") { seen.get() != null }
                assertEquals("和你一样.mp3", playing.get())
                assertEquals(SpatialMode.SPLIT, seen.get()!!.mode)
            } finally {
                client.stop()
            }
        } finally {
            server.stop()
        }
    }

    /**
     * A handset on an older build is told a message it has never heard of, and keeps playing.
     *
     * This is what makes a second kind of message on this channel safe to add at all: the reader
     * already had one place where a message it cannot read is counted and stepped over, so the
     * failure mode was designed before the feature was.
     */
    @Test
    fun aMessageAReaderDoesNotKnowIsCountedRatherThanFatal() {
        val port = freePort()
        val server = SpatialFieldServer(port)
        val seen = AtomicReference<SpatialField?>(null)
        // No onNowPlaying, which is exactly the shape of a caller written before this existed.
        val client = SpatialFieldClient("127.0.0.1", port, here) { seen.set(it) }
        server.start()
        try {
            client.start()
            awaitTrue("the sink to connect") { server.clientCount() == 1 }
            server.publishNowPlaying("倔强.mp3")
            server.publish(field(SpatialMode.ROTATE))

            // The rule still arrives, which is the half that matters: the name was stepped over.
            awaitTrue("the rule to arrive after an unknown message") { seen.get() != null }
            assertEquals(SpatialMode.ROTATE, seen.get()!!.mode)
        } finally {
            client.stop()
            server.stop()
        }
    }
    /**
     * A sink is told its colour without anybody touching anything, and the host is in the table.
     *
     * Both halves are quiet when wrong. A table that reached nobody shows as a handset with a
     * number and no colour, which is also what an older host looks like. A table that left the
     * host out shows as a room where one icon is a different colour on two screens - and the two
     * screens are read side by side, which is the only way anybody would find out.
     */
    @Test
    fun aSinkIsToldWhichColourItHoldsAndTheHostIsInTheTableToo() {
        val port = freePort()
        val server = SpatialFieldServer(port, selfId = there)
        val table = AtomicReference<Map<String, Int>?>(null)
        val client = SpatialFieldClient("127.0.0.1", port, here, onBadges = { table.set(it) }) {}
        server.start()
        try {
            client.start()

            awaitTrue("the table to name both handsets") { table.get()?.size == 2 }
            val seen = table.get()!!
            assertEquals(server.places(), seen)
            assertNotEquals("two handsets, one colour", seen[here], seen[there])
        } finally {
            client.stop()
            server.stop()
        }
    }

    /**
     * A colour goes back to the room once the departure is noticed, which is on the next thing the
     * room is told rather than when the socket closes.
     *
     * Written this way because the first version of it asserted the departure was noticed at once,
     * and it is not: what notices a handset has gone is a write that fails, and while nobody
     * touches a control there is nothing to write. That is older than this table and deliberate -
     * the audio channel writes fifty times a second and so has a roster that is nearly live, and
     * comparing the two rosters is how a room says a handset stopped playing. A colour released
     * late costs one of twelve in a room of three; noticing here would cost that comparison.
     */
    @Test
    fun aColourGoesBackOnceSomethingWrittenNoticesTheHandsetLeft() {
        val port = freePort()
        val server = SpatialFieldServer(port, selfId = there)
        val client = SpatialFieldClient("127.0.0.1", port, here) {}
        server.start()
        try {
            client.start()
            awaitTrue("the sink to be given a colour") { server.places().containsKey(here) }

            client.stop()

            awaitTrue("the colour to be given back") {
                // Inside the wait rather than before it: a socket the peer has closed takes one
                // write without complaining and fails on a later one.
                server.publishNowPlaying("anything")
                !server.places().containsKey(here)
            }
            assertTrue("the host kept its own", server.places().containsKey(there))
        } finally {
            client.stop()
            server.stop()
        }
    }
}
