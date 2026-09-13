package com.soundmesh.probe.sync

import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationPlanCodec
import com.soundmesh.core.CalibrationRequest
import java.net.ServerSocket
import java.net.Socket
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
                is SocketTimeoutException -> TIMEOUT
                is GarbledRequest -> "PLAN_GARBLED"
                is IllegalArgumentException -> "PLAN_REFUSED"
                else -> "PLAN_UNREADABLE"
            }
        }.getOrNull()
    }

    /**
     * Gathers a room, then answers all of it with one plan.
     *
     * [awaitRequest] mints a plan on the accept because a pair's plan names only the two handsets
     * already in the conversation. A room's names every slot, so it does not exist until everybody
     * has asked - which inverts the order without changing when the plan is minted: still at the
     * end, because it names instants a few seconds out in this handset's clock and one minted
     * while waiting for somebody to pick up a second phone would already be in the past.
     *
     * [firstWaitMillis] is for somebody to pick up the first phone. [settleMillis] is the gap
     * after which nobody else is coming, which is the gap between two people pressing two buttons
     * rather than anything about the link. [roomWindowMillis] caps the whole gathering from the
     * first ask, and is checked against how long a sink will wait: the handset that asked first
     * waits out the entire gathering, so a room held open longer than that answers into a socket
     * nobody is listening on - and the sink reports a host that never replied while this side
     * reports a room it served.
     *
     * An ask that cannot be read costs that handset and not the room: it is counted in
     * [refusedSinks] and its socket closed. The alternative makes one out-of-date phone
     * indistinguishable from a broken host.
     */
    fun awaitRoom(
        firstWaitMillis: Int,
        settleMillis: Int,
        roomWindowMillis: Int,
        // Called as each handset arrives, with how many have. Gathering is the longest thing
        // this screen does and the only one a person can act on while it happens: a handset
        // that has not arrived is usually one that is not on its home screen, and nobody can
        // know that from a line that says only that we are waiting.
        onJoined: (Int) -> Unit = {},
        planFor: (List<CalibrationRequest>) -> CalibrationPlan
    ): CalibrationPlan? {
        require(roomWindowMillis < CalibrationPlanClient.REPLY_TIMEOUT_MILLIS) {
            "a room held open for $roomWindowMillis ms outlasts what a sink will wait for"
        }
        val bound = server ?: run {
            failureCode = "PLAN_UNBOUND"
            return null
        }
        refusedSinks = 0
        val waiting = ArrayList<Pair<Socket, CalibrationRequest>>()
        var closesAt = Long.MAX_VALUE
        return runCatching {
            while (true) {
                val budget = if (waiting.isEmpty()) firstWaitMillis else {
                    val left = (closesAt - System.nanoTime()) / 1_000_000
                    minOf(settleMillis.toLong(), left).coerceAtLeast(1L).toInt()
                }
                bound.soTimeout = budget
                val socket = try {
                    bound.accept()
                } catch (quiet: SocketTimeoutException) {
                    // Nobody else is coming. With an empty room that is the failure the pair path
                    // reports; with a room behind us it is how gathering ends.
                    if (waiting.isEmpty()) throw quiet else break
                }
                socket.soTimeout = settleMillis
                val request = runCatching {
                    val asked = String(socket.getInputStream().readBytes(), Charsets.UTF_8).trim()
                    CalibrationPlanCodec.decodeRequest(asked)
                }.getOrNull()
                if (request == null) {
                    refusedSinks++
                    runCatching { socket.close() }
                    continue
                }
                if (waiting.isEmpty()) closesAt = System.nanoTime() + roomWindowMillis * 1_000_000L
                waiting += socket to request
                runCatching { onJoined(waiting.size) }
                if (System.nanoTime() >= closesAt) break
            }
            // Minted once, here, and written to everybody: a room whose handsets hold schedules
            // minted a moment apart is two rooms that will not add up.
            val plan = planFor(waiting.map { it.second })
            val encoded = CalibrationPlanCodec.encode(plan).toByteArray(Charsets.UTF_8)
            for ((socket, _) in waiting) {
                runCatching {
                    socket.getOutputStream().apply { write(encoded); flush() }
                }
            }
            plan
        }.also {
            for ((socket, _) in waiting) runCatching { socket.close() }
        }.onFailure {
            failureCode = when (it) {
                is SocketTimeoutException -> TIMEOUT
                is IllegalArgumentException -> "PLAN_REFUSED"
                else -> "PLAN_UNREADABLE"
            }
        }.getOrNull()
    }

    /** Handsets whose ask could not be read, and were let go while the room went on. */
    @Volatile
    var refusedSinks: Int = 0
        private set

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** An ask this server could not read, as opposed to one the host declined to serve. */
    private class GarbledRequest(cause: Throwable) : RuntimeException(cause)

    companion object {
        /**
         * Nobody asked inside the wait.
         *
         * Named rather than left a literal because a caller serving several handsets off one press
         * has to tell it apart from every other failure: this one means the session is finished,
         * and the rest mean this handset's turn went wrong and the next one is still owed theirs.
         * Two literals compared across two files is exactly the drift that has no symptom.
         */
        const val TIMEOUT = "PLAN_TIMEOUT"
    }
}
