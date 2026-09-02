package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** Unicasts every chunk to every connected sink. WiFi multicast would be sent unacknowledged at the lowest rate. */
class ChunkServer(private val port: Int) {
    private val clients = Collections.synchronizedList(ArrayList<Pair<Socket, OutputStream>>())
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

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
                        clients.add(socket to socket.getOutputStream().buffered())
                    }
                }
            }
        }.start()
    }

    fun broadcast(chunk: AudioChunk) {
        val frame = ChunkCodec.encode(chunk)
        synchronized(clients) {
            val dead = ArrayList<Pair<Socket, OutputStream>>()
            for (client in clients) {
                runCatching {
                    client.second.write(frame)
                    client.second.flush()
                }.onFailure { dead.add(client) }
            }
            dead.forEach { clients.remove(it); runCatching { it.first.close() } }
        }
    }

    fun clientCount(): Int = clients.size

    fun stop() {
        running = false
        runCatching { server?.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.first.close() } }
            clients.clear()
        }
    }
}
