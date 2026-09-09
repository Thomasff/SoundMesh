package com.soundmesh.probe.sync

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialFieldCodec
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Length prefixed framing for one spatial rule.
 *
 * A rule carries a line per handset, so a newline cannot end it. Four big endian bytes of length
 * ahead of the UTF-8 text, the same shape [ChunkCodec] uses and for the same reason: a reader has
 * to know how much to wait for before it can tell a sender that went away mid-message from one
 * that has simply not sent anything yet.
 */
internal object SpatialFrame {
    const val LENGTH_PREFIX_BYTES = 4
    const val MAX_PAYLOAD_BYTES = 1 shl 16

    fun encode(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size in 1..MAX_PAYLOAD_BYTES) { "implausible spatial rule: ${payload.size} bytes" }
        val frame = ByteArray(LENGTH_PREFIX_BYTES + payload.size)
        for (index in 0 until LENGTH_PREFIX_BYTES) {
            frame[index] = (payload.size ushr (24 - index * 8)).toByte()
        }
        payload.copyInto(frame, LENGTH_PREFIX_BYTES)
        return frame
    }

    /** Reads one frame, or null once the stream cannot yield a whole one. */
    fun read(stream: InputStream): String? {
        val prefix = readFully(stream, LENGTH_PREFIX_BYTES) ?: return null
        var length = 0
        for (byte in prefix) length = (length shl 8) or (byte.toInt() and 0xFF)
        if (length !in 1..MAX_PAYLOAD_BYTES) return null
        val payload = readFully(stream, length) ?: return null
        return String(payload, Charsets.UTF_8)
    }

    private fun readFully(stream: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = stream.read(buffer, filled, count - filled)
            if (read < 0) return null
            filled += read
        }
        return buffer
    }
}

/**
 * Pushes the current spatial rule to every sink, and to each new one as it arrives.
 *
 * The rule is remembered rather than only forwarded, and that is the point of this class. A
 * handset that joins between two touches of the screen would otherwise render nothing until the
 * listener happened to move a control. A room that is silently one handset short of the drawing
 * is a room nobody would think to check.
 *
 * A queue and a thread per sink, on the same terms as [ChunkServer]: a handset that leaves the
 * network without closing its socket does not fail a write, it blocks it, measured there at 26.9
 * seconds. This is published from whichever thread the listener touched, so blocking it would
 * freeze the screen rather than the audio, which is no better.
 *
 * The queue holds one rule, and a new one replaces what is waiting rather than queueing behind
 * it. That is the opposite of [ChunkServer]'s rule, for a plain reason: an audio chunk that missed
 * its instant is worthless and so is the next one, while for a rule the newest is exactly the one
 * wanted and everything before it is superseded.
 */
class SpatialFieldServer(private val port: Int) {
    private class Client(val socket: Socket, val stream: OutputStream) {
        val outbox = ArrayBlockingQueue<ByteArray>(1)

        fun offerLatest(frame: ByteArray) {
            outbox.clear()
            outbox.offer(frame)
        }
    }

    private val clients = Collections.synchronizedList(ArrayList<Client>())

    // Read on the accept thread and written from the listener's, so the newest rule reaches a sink
    // that connects while nobody is touching anything.
    @Volatile private var current: ByteArray? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        // Bound on the caller's thread for the reason ChunkServer.start states: a stop() landing
        // before an async bind completed would find the socket null and miss the close.
        val bound = ServerSocket(port)
        server = bound
        running = true
        Thread {
            runCatching {
                bound.use {
                    while (running) {
                        val socket = bound.accept()
                        socket.tcpNoDelay = true
                        val client = Client(socket, socket.getOutputStream().buffered())
                        current?.let { client.offerLatest(it) }
                        clients.add(client)
                        Thread({ serve(client) }, "SoundMeshSpatialSend").start()
                    }
                }
            }
        }.start()
    }

    /** Makes [field] the rule the room plays under, from now until the next one. */
    fun publish(field: SpatialField) {
        val frame = SpatialFrame.encode(SpatialFieldCodec.encode(field))
        current = frame
        synchronized(clients) {
            for (client in clients) client.offerLatest(frame)
        }
    }

    private fun serve(client: Client) {
        runCatching {
            client.socket.use {
                while (running) {
                    // Polled with a timeout rather than taken, so a session that stops while every
                    // queue is empty still ends these threads.
                    val frame = client.outbox.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                    client.stream.write(frame)
                    client.stream.flush()
                }
            }
        }
        clients.remove(client)
    }

    fun clientCount(): Int = clients.size

    fun stop() {
        running = false
        runCatching { server?.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.socket.close() } }
            clients.clear()
        }
    }

    private companion object {
        /** How often a sender wakes to notice the session ended. Short next to a person's patience. */
        const val POLL_MILLIS = 200L
    }
}

/**
 * Receives the rule the host publishes, and keeps holding it if the host goes quiet.
 *
 * [onField] is called on this class's own thread, so whatever it updates has to be safe to read
 * from the renderer. Nothing here decides when a rule takes effect: a rule is a function of the
 * host instant, and the instants are already in the chunks.
 *
 * A rule that cannot be read is counted and skipped rather than ending the stream. One garbled
 * message must not leave a handset deaf to the next one; a build speaking another version makes
 * every message unreadable, and [unreadableRules] is how that becomes something a report can say
 * instead of something a listener has to notice by ear.
 */
class SpatialFieldClient(
    private val hostAddress: String,
    private val port: Int,
    private val onField: (SpatialField) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    @Volatile private var unreadable = 0

    fun start() {
        // Connected on the caller's thread for the reason ChunkClient.start states.
        val connected = Socket(hostAddress, port)
        connected.tcpNoDelay = true
        socket = connected
        running = true
        Thread {
            runCatching {
                connected.use {
                    val stream = connected.getInputStream().buffered()
                    while (running) {
                        val text = SpatialFrame.read(stream) ?: break
                        val field = runCatching { SpatialFieldCodec.decode(text) }.getOrNull()
                        if (field == null) unreadable++ else onField(field)
                    }
                }
            }
        }.start()
    }

    /** Rules that arrived and could not be read. Zero against a host of the same build. */
    fun unreadableRules(): Int = unreadable

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }
}
