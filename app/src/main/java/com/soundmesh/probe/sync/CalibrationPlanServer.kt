package com.soundmesh.probe.sync

import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationPlanCodec
import com.soundmesh.core.CalibrationRequest
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
     *
     * It is handed the whole ask: the case, and the name of the handset asking. Both reach a file
     * name on this side - the case a run store which will create whatever directory it is given,
     * the sink name the file this host's own half of the run is written to - so [planFor] is
     * expected to refuse either by throwing IllegalArgumentException. That is reported apart from
     * an ask this server could not read at all, because the two mean different things to whoever
     * reads the failure: one is a handset this host declines to serve, the other is a build that
     * does not speak this version.
     */
    fun awaitRequest(
        timeoutMillis: Int,
        planFor: (CalibrationRequest) -> CalibrationPlan
    ): CalibrationPlan? {
        val bound = server ?: run {
            failureCode = "PLAN_UNBOUND"
            return null
        }
        return runCatching {
            bound.soTimeout = timeoutMillis
            bound.accept().use { socket ->
                socket.soTimeout = timeoutMillis
                // The sink writes what it wants and half-closes to mark the request complete.
                val asked = String(socket.getInputStream().readBytes(), Charsets.UTF_8).trim()
                val request = try {
                    CalibrationPlanCodec.decodeRequest(asked)
                } catch (unreadable: IllegalArgumentException) {
                    // Rewrapped so it cannot be mistaken for [planFor] declining to serve: both
                    // arrive here as IllegalArgumentException and they are different answers.
                    throw GarbledRequest(unreadable)
                }
                val plan = planFor(request)
                socket.getOutputStream().apply {
                    write(CalibrationPlanCodec.encode(plan).toByteArray(Charsets.UTF_8))
                    flush()
                }
                plan
            }
        }.onFailure {
            failureCode = when (it) {
                is SocketTimeoutException -> "PLAN_TIMEOUT"
                is GarbledRequest -> "PLAN_GARBLED"
                is IllegalArgumentException -> "PLAN_REFUSED"
                else -> "PLAN_UNREADABLE"
            }
        }.getOrNull()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** An ask this server could not read, as opposed to one the host declined to serve. */
    private class GarbledRequest(cause: Throwable) : RuntimeException(cause)
}
