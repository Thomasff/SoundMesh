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
    private val onChunk: (AudioChunk) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        Thread {
            runCatching {
                Socket(hostAddress, port).use { connected ->
                    socket = connected
                    connected.tcpNoDelay = true
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
