package com.soundmesh.probe.sync

import com.soundmesh.core.AlignmentResultCodec
import com.soundmesh.core.AlignmentResultMessage
import java.net.ServerSocket

/**
 * Takes delivery of the sink's own reading of the run, so the host can combine the two sides
 * itself.
 *
 * Half a measurement is what each handset can produce alone: it hears its own chirp across a few
 * centimetres and its partner's across the room, and only the two together remove the flight time
 * (see [com.soundmesh.core.AlignmentAnalysis.combineFacing]). Until this existed, the combining
 * was done on a PC that had pulled both reports over a cable - which meant a pair of handsets
 * could measure but could not answer.
 *
 * TCP, unlike the clock exchange: this is one message at the very end of a run, and losing it
 * silently would cost the whole measurement. The sender closes the stream to mark the end.
 */
class AlignmentResultServer(private val port: Int) {
    @Volatile private var server: ServerSocket? = null

    /** Why [awaitResult] returned null, or null if it has not. */
    @Volatile
    var failureCode: String? = null
        private set

    /**
     * Binds the port. Called at the start of a run rather than at the end, so a sink that finishes
     * its own correlation first is held in the accept backlog instead of being refused.
     */
    fun start() {
        server = ServerSocket(port)
    }

    /**
     * Waits for one delivery, for at most [timeoutMillis].
     *
     * Returns null on a timeout or an unreadable stream, leaving the reason in [failureCode]. A
     * bounded wait rather than an open one: the sink is the only thing that can deliver, and a
     * sink that has died must cost the combined number rather than the host's whole report.
     */
    fun awaitResult(timeoutMillis: Int): AlignmentResultMessage? {
        val bound = server ?: run {
            failureCode = "RESULT_UNBOUND"
            return null
        }
        return runCatching {
            bound.soTimeout = timeoutMillis
            bound.accept().use { socket ->
                socket.soTimeout = timeoutMillis
                AlignmentResultCodec.decode(String(socket.getInputStream().readBytes(), Charsets.UTF_8))
            }
        }.onFailure {
            failureCode = if (it is java.net.SocketTimeoutException) "RESULT_TIMEOUT" else "RESULT_UNREADABLE"
        }.getOrNull()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }
}
