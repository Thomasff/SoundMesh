package com.soundmesh.probe.sync

import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
import com.soundmesh.core.ClockOffsetEstimator
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The estimate a run has in force, which a rejected cycle does not take away.
 *
 * [ClockOffsetEstimator.estimate] answers null for a window it will not stand behind - too few
 * samples, or a slope past the plausibility guard, which is how a rogue exchange with the shortest
 * round trip of all and an offset a hundred milliseconds out gets caught, since the best-of cut is
 * bound to keep it. That is a reason to leave the previous answer standing, not to have no answer:
 * writing the rejection through as a null sent the caller to its own fallback, which on
 * 2026-09-08 was the estimate taken before the chirps began - fifty-four exchanges and twenty
 * seconds earlier. The run's worst single point was 9.167 ms against a 3.0 gate; held instead, the
 * same recording gives 2.156.
 *
 * tools/src/clock-replay.mjs has always modelled the run this way and says so in its own test.
 * This is the half that was not true.
 */
internal class HeldEstimate {
    @Volatile private var held: ClockEstimate? = null

    fun offer(fresh: ClockEstimate?) {
        if (fresh != null) held = fresh
    }

    fun current(): ClockEstimate? = held
}

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
    private val cached = HeldEstimate()

    // Kept whole, not merely fed to the estimator and forgotten. The estimator holds a sliding
    // window and drops whatever falls out of it, so without this the exchanges a run was actually
    // built on are gone the moment the run ends - and a candidate estimator design can then only be
    // compared by running the phones again, one run per arm, against all the between-run scatter.
    // Held here, one recorded run replays through any number of designs offline, on identical input.
    private val exchanges = ArrayList<ClockExchange>()

    fun runFor(seconds: Int, intervalMillis: Long = 2000): List<ClockEstimate> {
        val history = ArrayList<ClockEstimate>()
        val address = InetAddress.getByName(hostAddress)
        DatagramSocket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            var seq = 0
            try {
                while (System.nanoTime() < deadline) {
                    val t1 = System.nanoTime()
                    val sent = seq++
                    val request = ClockPacket.encodeRequest(sent, t1)
                    runCatching {
                        socket.send(DatagramPacket(request, request.size, address, port))
                        receiveMatchingReply(socket, sent)
                    }.getOrNull()?.let(::keep)
                        val estimate = estimator.estimate(System.nanoTime())
                    cached.offer(estimate)
                    estimate?.let { history.add(it) }
                    Thread.sleep(intervalMillis)
                }
            } catch (stopped: InterruptedException) {
                // The interrupt is this loop's stop, and every caller uses it as one: it has no
                // other. Left to propagate it leaves the runnable of whatever thread runs this by
                // throwing, and an uncaught throw on any thread takes the process. The pair
                // calibration screen interrupts in the finally that ends a run, so the process
                // died there on every run - after the result on a good one, and before the screen
                // could say why on a failed one, which is the one that cost a reason.
                //
                // The flag is put back rather than swallowed: a caller that loops around runFor is
                // entitled to see that it was stopped. SinkSession.exchangeClock is such a caller
                // and already clears it deliberately before rebuilding, for its own stated reason.
                Thread.currentThread().interrupt()
            }
        }
        return history
    }

    /** The one place an exchange enters the run, so what is kept cannot drift from what was fitted. */
    private fun keep(exchange: ClockExchange) {
        estimator.record(exchange)
        exchanges.add(exchange)
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
     * Cached rather than refit here - see the comment on [cached]. A rejected cycle leaves the
     * previous answer standing rather than none; [HeldEstimate] says why.
     */
    fun currentEstimate(): ClockEstimate? = cached.current()

    /** Every exchange this run fed the estimator, in the order it fed them. Read once [runFor] has returned. */
    fun recordedExchanges(): List<ClockExchange> = exchanges.toList()

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 1000
    }
}
