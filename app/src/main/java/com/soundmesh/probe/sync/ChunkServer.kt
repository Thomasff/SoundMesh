package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
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
     */
    private class Client(val socket: Socket, val stream: OutputStream) {
        val outbox = ArrayBlockingQueue<ByteArray>(OUTBOX_CAPACITY_CHUNKS)
    }

    private val clients = Collections.synchronizedList(ArrayList<Client>())
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    // Kept on the server rather than on the client, so that what a run dropped survives the client
    // it was dropped for - a sink that left is exactly the case the number is wanted for.
    @Volatile private var dropped = 0

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
                        val client = Client(socket, socket.getOutputStream().buffered())
                        clients.add(client)
                        Thread({ serve(client) }, "SoundMeshChunkSend").start()
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

    /** Drains one sink's queue until the session ends or its socket does. */
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

    /** Chunks that were not sent because a sink stopped keeping up. Zero on a healthy link. */
    fun droppedChunks(): Int = dropped

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

        /** Long enough for a full queue on a healthy link, short enough that a dead one is not waited on. */
        const val DRAIN_TIMEOUT_MILLIS = 2_000L

        const val DRAIN_POLL_MILLIS = 20L
    }
}
