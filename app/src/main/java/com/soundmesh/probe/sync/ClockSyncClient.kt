package com.soundmesh.probe.sync

import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockOffsetEstimator
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Asks the host for its clock on a schedule and feeds the answers to the estimator.
 * UDP rather than the audio connection: TCP retransmission and queueing would measure how long
 * a packet waited in a kernel buffer rather than how long the network took.
 */
class ClockSyncClient(
    private val hostAddress: String,
    private val port: Int,
    private val estimator: ClockOffsetEstimator
) {
    fun runFor(seconds: Int, intervalMillis: Long = 2000): List<ClockEstimate> {
        val history = ArrayList<ClockEstimate>()
        val address = InetAddress.getByName(hostAddress)
        DatagramSocket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            var seq = 0
            while (System.nanoTime() < deadline) {
                val t1 = System.nanoTime()
                val request = ClockPacket.encodeRequest(seq++, t1)
                runCatching {
                    socket.send(DatagramPacket(request, request.size, address, port))
                    val buffer = ByteArray(ClockPacket.BYTES)
                    val incoming = DatagramPacket(buffer, buffer.size)
                    socket.receive(incoming)
                    estimator.record(ClockPacket.decodeReply(buffer, System.nanoTime()))
                }
                estimator.estimate(System.nanoTime())?.let { history.add(it) }
                Thread.sleep(intervalMillis)
            }
        }
        return history
    }

    /** Estimate valid right now, or null while the window is still filling. */
    fun currentEstimate(): ClockEstimate? = estimator.estimate(System.nanoTime())

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 1000
    }
}
