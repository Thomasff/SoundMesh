package com.soundmesh.probe.sync

import com.soundmesh.core.ClockExchange
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.Collections

/** Wire format of one clock exchange. Request and reply share a layout; a request leaves t2 and t3 zero. */
object ClockPacket {
    const val BYTES = 32

    /**
     * The UDP port the exchange runs on.
     *
     * Here rather than on the screen that first used it, because it is part of what the two ends
     * have to agree on and the two ends are now two platforms. A second copy of this number would
     * fail in the only way that costs a session to diagnose: both ends working perfectly and
     * neither hearing the other.
     */
    const val DEFAULT_PORT = 45123

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

    /** The seq a request was sent with, echoed back unchanged in its reply. Lets a caller match a reply to its request. */
    fun sequenceOf(bytes: ByteArray): Int {
        require(bytes.size == BYTES) { "clock packet must be $BYTES bytes" }
        return readInt(bytes, 0)
    }

    private fun writeInt(target: ByteArray, at: Int, value: Int) {
        for (index in 0 until 4) target[at + index] = (value ushr (24 - index * 8)).toByte()
    }

    private fun writeLong(target: ByteArray, at: Int, value: Long) {
        for (index in 0 until 8) target[at + index] = (value ushr (56 - index * 8)).toByte()
    }

    private fun readInt(source: ByteArray, at: Int): Int {
        var value = 0
        for (index in 0 until 4) value = (value shl 8) or (source[at + index].toInt() and 0xFF)
        return value
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

    /**
     * When a clock request last arrived from each address.
     *
     * Nothing in the time service needs this - it answers off the packet in its hand. It is here
     * because this is the only channel a handset leaving the network actually stops using: the
     * TCP channels go on looking established for minutes while the kernel retransmits into an
     * empty room. See PeerSilence, which is what reads it.
     */
    private val heard = Collections.synchronizedMap(HashMap<String, Long>())

    fun start() {
        // Bind on the caller's thread before handing off to the worker: stop() reads `socket`,
        // and a stop() landing before an async bind completed used to find it null and miss the
        // close, leaking the thread. Binding here means start() never returns without it set.
        val bound = DatagramSocket(port)
        socket = bound
        running = true
        Thread {
            bound.use {
                val buffer = ByteArray(ClockPacket.BYTES)
                while (running) {
                    val incoming = DatagramPacket(buffer, buffer.size)
                    try {
                        bound.receive(incoming)
                    } catch (_: Exception) {
                        continue
                    }
                    if (incoming.length != ClockPacket.BYTES) continue
                    // After the length check, so that a stray packet on this port cannot make a
                    // handset that has gone look like one that is still asking.
                    //
                    // Tried after the reply instead, on the theory that a string and a
                    // synchronised put sitting between arrival and t2 were charged to the outbound
                    // leg and became half of themselves as offset. Measured against a second
                    // process on this machine, where the answer is zero by construction: +54, +58,
                    // +41 us before the move and +50, +45, +47 us after it. Not the cause. Left
                    // where it was.
                    incoming.address?.hostAddress?.let { heard[it] = System.currentTimeMillis() }
                    val t2 = System.nanoTime()
                    val reply = ClockPacket.encodeReply(buffer.copyOf(ClockPacket.BYTES), t2, System.nanoTime())
                    runCatching { bound.send(DatagramPacket(reply, reply.size, incoming.address, incoming.port)) }
                }
            }
        }.start()
    }

    /** A copy, because whoever reads it is on another thread and iterates what it hands back. */
    fun heardFrom(): Map<String, Long> = synchronized(heard) { HashMap(heard) }

    fun stop() {
        running = false
        socket?.close()
    }
}
