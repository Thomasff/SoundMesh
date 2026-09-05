package com.soundmesh.probe.sync

import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationPlanCodec
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Hands the sink the schedule both handsets are about to run.
 *
 * The host decides it because the instants in it are in the host's own clock, and it is told
 * rather than agreed: a negotiation's failure mode is the two ends standing on different answers,
 * which nothing in a calibration can detect and no calibration survives.
 *
 * TCP and one exchange, on the same terms as [AlignmentResultServer]: this is one message at the
 * start of a run, and losing it silently would cost the whole run.
 */
class CalibrationPlanServer(private val port: Int) {
    @Volatile private var server: ServerSocket? = null

    /** Why [awaitRequest] returned null, or null if it has not. */
    @Volatile
    var failureCode: String? = null
        private set

    fun start() {
        server = ServerSocket(port)
    }

    /**
     * Waits for the sink to ask, for at most [timeoutMillis], and answers with what [planFor]
     * makes at that moment.
     *
     * [planFor] is called on the accept rather than before the wait, and that is the whole reason
     * it is a lambda: a plan names instants a few seconds out in the host's clock, so one minted
     * while waiting for somebody to pick up the other phone would already be in the past.
     */
    fun awaitRequest(timeoutMillis: Int, planFor: () -> CalibrationPlan): CalibrationPlan? {
        val bound = server ?: run {
            failureCode = "PLAN_UNBOUND"
            return null
        }
        return runCatching {
            bound.soTimeout = timeoutMillis
            bound.accept().use { socket ->
                socket.soTimeout = timeoutMillis
                // The sink half-closes to mark its request; there is nothing in it to read.
                socket.getInputStream().readBytes()
                val plan = planFor()
                socket.getOutputStream().apply {
                    write(CalibrationPlanCodec.encode(plan).toByteArray(Charsets.UTF_8))
                    flush()
                }
                plan
            }
        }.onFailure {
            failureCode = if (it is SocketTimeoutException) "PLAN_TIMEOUT" else "PLAN_UNREADABLE"
        }.getOrNull()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }
}
