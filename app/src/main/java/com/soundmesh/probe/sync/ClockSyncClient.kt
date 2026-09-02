package com.soundmesh.probe.sync

import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
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
    // Refreshed once per exchange cycle in runFor(), below - not on every currentEstimate() call.
    // Re-running the estimator's least-squares fit (sorting and filtering the whole window) on
    // every call was wasteful: the chirp wait busy-spins on hostNanosNow(), which reads this, and
    // the renderer calls it several times per loop iteration. The window itself only changes at
    // this same cadence anyway - record() only runs once per cycle - so refitting in between adds
    // no new data, only a slightly different `atLocalNanos`. Going stale for up to one cycle
    // (2s by default) leaves the reported offset off by at most driftPpm * cycle length: about
    // 1ms even at the estimator's 500ppm rejection ceiling, tens of microseconds at the drift a
    // real crystal shows - either way small next to the 5ms alignment budget this offset feeds.
    @Volatile private var cachedEstimate: ClockEstimate? = null

    fun runFor(seconds: Int, intervalMillis: Long = 2000): List<ClockEstimate> {
        val history = ArrayList<ClockEstimate>()
        val address = InetAddress.getByName(hostAddress)
        DatagramSocket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            var seq = 0
            while (System.nanoTime() < deadline) {
                val t1 = System.nanoTime()
                val sent = seq++
                val request = ClockPacket.encodeRequest(sent, t1)
                runCatching {
                    socket.send(DatagramPacket(request, request.size, address, port))
                    receiveMatchingReply(socket, sent)
                }.getOrNull()?.let { estimator.record(it) }
                val estimate = estimator.estimate(System.nanoTime())
                cachedEstimate = estimate
                estimate?.let { history.add(it) }
                Thread.sleep(intervalMillis)
            }
        }
        return history
    }

    /**
     * Reads replies until one answers [sent]. The socket is not connected to the host address
     * and carries no other correlation, so a stale reply to an earlier, timed-out request can
     * otherwise be accepted as the answer to this one - stale t1/t2/t3 against a fresh t4, which
     * is a wrong offset and an inflated round trip that only the estimator's best-of-window
     * filtering happens to hide.
     */
    private fun receiveMatchingReply(socket: DatagramSocket, sent: Int): ClockExchange {
        val buffer = ByteArray(ClockPacket.BYTES)
        while (true) {
            val incoming = DatagramPacket(buffer, buffer.size)
            socket.receive(incoming)
            if (incoming.length != ClockPacket.BYTES || ClockPacket.sequenceOf(buffer) != sent) continue
            return ClockPacket.decodeReply(buffer, System.nanoTime())
        }
    }

    /**
     * The estimate as of the last exchange cycle, or null while the window is still filling.
     * Cached rather than refit here - see [cachedEstimate].
     */
    fun currentEstimate(): ClockEstimate? = cachedEstimate

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 1000
    }
}
