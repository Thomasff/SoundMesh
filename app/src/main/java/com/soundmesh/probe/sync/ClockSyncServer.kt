package com.soundmesh.probe.sync

import com.soundmesh.core.ClockExchange
import java.net.DatagramPacket
import java.net.DatagramSocket

/** Wire format of one clock exchange. Request and reply share a layout; a request leaves t2 and t3 zero. */
object ClockPacket {
    const val BYTES = 32

    fun encodeRequest(seq: Int, t1: Long): ByteArray {
        val packet = ByteArray(BYTES)
        writeInt(packet, 0, seq)
        writeLong(packet, 4, t1)
        return packet
    }

    fun encodeReply(request: ByteArray, t2: Long, t3: Long): ByteArray {
        require(request.size == BYTES) { "clock packet must be $BYTES bytes" }
        val packet = request.copyOf()
        writeLong(packet, 12, t2)
        writeLong(packet, 20, t3)
        return packet
    }

    fun decodeReply(bytes: ByteArray, t4: Long): ClockExchange {
        require(bytes.size == BYTES) { "clock packet must be $BYTES bytes" }
        return ClockExchange(t1 = readLong(bytes, 4), t2 = readLong(bytes, 12), t3 = readLong(bytes, 20), t4 = t4)
    }

    private fun writeInt(target: ByteArray, at: Int, value: Int) {
        for (index in 0 until 4) target[at + index] = (value ushr (24 - index * 8)).toByte()
    }

    private fun writeLong(target: ByteArray, at: Int, value: Long) {
        for (index in 0 until 8) target[at + index] = (value ushr (56 - index * 8)).toByte()
    }

    private fun readLong(source: ByteArray, at: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (source[at + index].toLong() and 0xFF)
        return value
    }
}

/** Answers clock requests. Timestamps are taken as close to the wire as the runtime allows. */
class ClockSyncServer(private val port: Int) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        Thread {
            DatagramSocket(port).use { bound ->
                socket = bound
                val buffer = ByteArray(ClockPacket.BYTES)
                while (running) {
                    val incoming = DatagramPacket(buffer, buffer.size)
                    try {
                        bound.receive(incoming)
                    } catch (_: Exception) {
                        continue
                    }
                    if (incoming.length != ClockPacket.BYTES) continue
                    val t2 = System.nanoTime()
                    val reply = ClockPacket.encodeReply(buffer.copyOf(ClockPacket.BYTES), t2, System.nanoTime())
                    runCatching { bound.send(DatagramPacket(reply, reply.size, incoming.address, incoming.port)) }
                }
            }
        }.start()
    }

    fun stop() {
        running = false
        socket?.close()
    }
}
