package com.soundmesh.probe.sync

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialFieldCodec
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class SpatialFieldChannelTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun field(mode: SpatialMode) = SpatialField(
        mode = mode,
        layout = SpatialLayout(
            listOf(
                SpatialPosition("a1b2c3d4e5f60718", -1.0, 0.5),
                SpatialPosition("0918273645abcdef", 1.0, 0.5)
            )
        )
    )

    /** Polls rather than sleeps a fixed span: delivery crosses two threads and a socket. */
    private fun awaitTrue(what: String, test: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
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
        val client = SpatialFieldClient("127.0.0.1", port) { seen.set(it) }
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
        val client = SpatialFieldClient("127.0.0.1", port) { seen.set(it) }
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
        val client = SpatialFieldClient("127.0.0.1", port) { seen.set(it) }
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
}
