package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.HostId
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Unicasts every chunk to every connected sink. WiFi multicast would be sent unacknowledged at the lowest rate. */
class ChunkServer(private val port: Int) {
    /**
     * One sink, with a queue of its own and a thread to drain it.
     *
     * The queue is the whole point. A socket whose peer vanished without closing - a handset that
     * left the network rather than left the session - does not fail a write: the send buffer fills
     * and the write blocks, for as long as the kernel keeps retransmitting. Measured on hardware at
     * 26.9 seconds. Written to from the caller's thread, that stalls whoever is producing the
     * chunks, and on the host that thread also feeds its own output - so one sink walking out of
     * the room silenced the room.
     *
     * Per client rather than one queue for all of them, because a shared queue would only move the
     * stall: the reconnecting handset's fresh socket would wait behind the dead one it replaced.
     *
     * [peerId] is how a handset that comes back is recognised as the handset that left. Null for a
     * sink that named nothing, which is the older builds and the probe path, and costs only the
     * ability to be recognised later - see [serve] for why that is not a refusal.
     */
    private class Client(val socket: Socket, val stream: OutputStream, val peerId: String?) {
        val outbox = ArrayBlockingQueue<ByteArray>(OUTBOX_CAPACITY_CHUNKS)
    }

    private val clients = Collections.synchronizedList(ArrayList<Client>())
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    // Kept on the server rather than on the client, so that what a run dropped survives the client
    // it was dropped for - a sink that left is exactly the case the number is wanted for.
    @Volatile private var dropped = 0

    // Kept here for the same reason, and kept at all because the fix would otherwise be silent: a
    // handset that comes back leaves no trace of the connection it left behind, and a roster that
    // quietly repairs itself is indistinguishable from one that never needed to.
    @Volatile private var replacedSinks = 0

    fun start() {
        // Bind on the caller's thread before handing off to the worker: stop() reads `server`,
        // and a stop() landing before an async bind completed used to find it null and miss the
        // close, leaking the thread. Binding here means start() never returns without it set.
        val bound = ServerSocket(port)
        server = bound
        running = true
        Thread {
            runCatching {
                bound.use {
                    while (running) {
                        val socket = bound.accept()
                        socket.tcpNoDelay = true
                        Thread({ serve(socket) }, "SoundMeshChunkSend").start()
                    }
                }
            }
        }.start()
    }

    /**
     * Hands one chunk to every sink and returns. Never waits on a socket.
     *
     * A queue that is full is a sink that has not kept up for [OUTBOX_CAPACITY_CHUNKS] chunks, and
     * dropping is the only honest thing left: the oldest chunk waiting there is already older than
     * the lead time it was given, so it would arrive after the instant it was to be played at and
     * be discarded at the far end anyway.
     */
    fun broadcast(chunk: AudioChunk) {
        val frame = ChunkCodec.encode(chunk)
        synchronized(clients) {
            for (client in clients) if (!client.outbox.offer(frame)) dropped++
        }
    }

    /**
     * Names one sink, puts it in the roster, and drains its queue until the session or the socket ends.
     *
     * The name is what makes the roster able to let go. Until it existed, a connection left this
     * list only when a write to it failed, and a half open TCP swallows a great many writes before
     * one does - so the socket a handset abandoned outlived it, holding a queue and a thread and
     * counting as a second sink. Measured across three devices: four sinks reported against two
     * handsets, while the control channel, which had already been taught this, had its roster right.
     *
     * A sink that names nothing is served anyway, which is where this parts company with the
     * control channel. Refusing one there costs an icon on a drawing; refusing one here costs a
     * handset all of its audio, and a build that predates the name is exactly the case.
     *
     * Read before the roster rather than after, so that a sink which connects and then says
     * nothing cannot park this thread for the life of the process: it is in no roster, so stop()
     * would not close it either. The bound on the read is what ends it.
     */
    private fun serve(socket: Socket) {
        val client = Client(socket, socket.getOutputStream().buffered(), announcedPeerId(socket))
        // The returning connection wins rather than being turned away: the old one is only still
        // here because nothing has failed on it yet, which is the same reason nobody noticed it die.
        val replaced = synchronized(clients) {
            val stale = client.peerId?.let { name -> clients.filter { it.peerId == name } }.orEmpty()
            clients.removeAll(stale)
            clients.add(client)
            // Counted under the lock for the reason dropped is: two handsets can come back at once.
            replacedSinks += stale.size
            stale
        }
        // Closed outside the lock, and closed rather than dropped: the thread parked on that socket
        // ends when the socket does, and a sender thread per departed handset is a leak with a name.
        for (old in replaced) runCatching { old.socket.close() }
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

    /**
     * The name a sink writes first, or null if it wrote something else, or nothing at all.
     *
     * Fixed width ASCII rather than a framed message, because a [HostId] is fixed width by
     * construction and its shape is checked wherever one is read - a length prefix would only add
     * a second way for this to be wrong. Nothing has ever travelled sink to host on this socket,
     * so a sink of an older build says nothing and waits out the bound.
     */
    private fun announcedPeerId(socket: Socket): String? = runCatching {
        socket.soTimeout = ANNOUNCE_TIMEOUT_MILLIS
        val name = ByteArray(HostId.LENGTH)
        var filled = 0
        while (filled < name.size) {
            val read = socket.getInputStream().read(name, filled, name.size - filled)
            if (read < 0) return@runCatching null
            filled += read
        }
        String(name, Charsets.US_ASCII).takeIf { HostId.isValid(it) }
    }.getOrNull()

    fun clientCount(): Int = clients.size

    /**
     * The sinks currently being sent audio, as they named themselves.
     *
     * Only the ones that said a name. A sink of an older build is served and is in [clientCount],
     * and is in no list anywhere - so the two read together say how many are being sent audio
     * without being able to be told apart, which is a different thing from a handset that left.
     *
     * What this is for is the comparison, not the list: a name in the control channel's roster and
     * not in here is the handset that stopped getting audio, and until this existed the host could
     * only say that one of them had.
     */
    fun peerIds(): List<String> = synchronized(clients) { clients.mapNotNull { it.peerId } }

    /** Chunks that were not sent because a sink stopped keeping up. Zero on a healthy link. */
    fun droppedChunks(): Int = dropped

    /** Connections a returning handset took the place of. Zero on a run with no reconnects. */
    fun replacedSinks(): Int = replacedSinks

    fun stop() {
        // Drained before anything is closed, so that a run which broadcast its last chunk and
        // stopped in the same breath still puts that chunk on the wire. Writes used to happen on
        // the caller's thread, which made that automatic; with a queue in between it has to be
        // said. Bounded, because a sink that is not reading is the reason the queue exists.
        drain()
        running = false
        runCatching { server?.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.socket.close() } }
            clients.clear()
        }
    }

    private fun drain() {
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (synchronized(clients) { clients.all { it.outbox.isEmpty() } }) return
            Thread.sleep(DRAIN_POLL_MILLIS)
        }
    }

    private companion object {
        /**
         * The buffer lead, in chunks: 1.5 seconds at 20 ms each.
         *
         * Not a tuning knob. A chunk older than the lead it was handed with is past its own play
         * instant, so a queue deeper than this holds only chunks the far end would discard.
         */
        const val OUTBOX_CAPACITY_CHUNKS = 75

        /** How often a sender wakes to notice the session ended. Short next to a person's patience. */
        const val POLL_MILLIS = 200L

        /**
         * How long a sink has to say its name before it is served as an unnamed one.
         *
         * Shorter than the control channel's two seconds, and for a different cost: there the wait
         * delays a drawing, here it delays audio. Sixteen bytes written the instant a handshake
         * completed on a LAN either arrive well inside this or are not coming.
         */
        const val ANNOUNCE_TIMEOUT_MILLIS = 500

        /** Long enough for a full queue on a healthy link, short enough that a dead one is not waited on. */
        const val DRAIN_TIMEOUT_MILLIS = 2_000L

        const val DRAIN_POLL_MILLIS = 20L
    }
}
