package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
import java.io.InputStream
import java.net.Socket

/** Reads length prefixed chunk frames off a stream, returning null once the stream cannot yield one. */
class FrameReader(private val stream: InputStream) {
    fun readChunk(): AudioChunk? {
        val prefix = readFully(ChunkCodec.LENGTH_PREFIX_BYTES) ?: return null
        val payload = runCatching { ChunkCodec.payloadLength(prefix) }.getOrNull() ?: return null
        val rest = readFully(payload) ?: return null
        return runCatching { ChunkCodec.decode(prefix + rest) }.getOrNull()
    }

    private fun readFully(count: Int): ByteArray? {
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

class ChunkClient(
    private val hostAddress: String,
    private val port: Int,
    /**
     * This handset's own name, said once before anything is expected back.
     *
     * It is the only thing that ever travels sink to host on this socket, and it exists so that a
     * host can tell the connection this handset is making now from the one it left behind: without
     * it the abandoned socket stays in the host's roster until a write to it fails, which a half
     * open TCP puts off for a long time, and the handset stands in the room twice.
     *
     * Null - the default - says nothing, which is what the probe path does. A host of any build
     * still serves the connection; it just cannot recognise it later.
     */
    private val peerId: String? = null,
    private val onChunk: (AudioChunk) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var running = false

    fun start() {
        // Connect on the caller's thread before handing off to the worker: stop() reads
        // `socket`, and a stop() landing before an async connect completed used to find it null
        // and miss the close, leaking the thread. Connecting here means start() never returns
        // without it set.
        val connected = Socket(hostAddress, port)
        connected.tcpNoDelay = true
        // Before the reader starts, because a host holds this connection unnamed until it arrives
        // and every chunk sent in the meantime is one the returning handset is counted twice for.
        peerId?.let { connected.getOutputStream().apply { write(it.toByteArray(Charsets.US_ASCII)); flush() } }
        socket = connected
        running = true
        Thread {
            runCatching {
                connected.use {
                    val reader = FrameReader(connected.getInputStream().buffered())
                    while (running) onChunk(reader.readChunk() ?: break)
                }
            }
        }.start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }
}
