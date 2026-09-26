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

    /** Set by [callOffRoom], read by the gathering it interrupts. */
    @Volatile private var calledOff = false

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
     *
     * [nobodyComing] is asked every [LOOK_MILLIS] while nobody has, and true ends the wait as
     * [NOBODY_COMING]: the handset this was for said it will not ask, and the rest of the five
     * minutes would be spent on a socket nobody is going to open.
     */
    fun awaitRequest(
        timeoutMillis: Int,
        nobodyComing: () -> Boolean = { false },
        planFor: (CalibrationRequest) -> CalibrationPlan
    ): CalibrationPlan? {
        val bound = server ?: run {
            failureCode = "PLAN_UNBOUND"
            return null
        }
        return runCatching {
            acceptWhileSomebodyMightCome(bound, timeoutMillis, nobodyComing).use { socket ->
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
                is NobodyComing -> NOBODY_COMING
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
     *
     * A handset that asks twice costs the room nothing either: the newer ask replaces the older
     * one, counted in [supersededAsks]. That is not hypothetical tidiness. On 2026-09-13 a
     * handset that had been stuck behind a microphone permission dialog resumed its old round
     * the moment the dialog was answered, while the host was telling the room to measure again -
     * so it asked twice within a second, [planFor] refused the whole room over the duplicate
     * name, every sink read an empty socket, and all four handsets had to be restarted. The
     * refusal was right and its blast radius was not: what is out of date is one ask, not a room.
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
        // Whether the room is complete, asked once each handset arrives. Gathering used to end
        // only on silence, which cost every round a full settle window after the last handset was
        // already in - and that window was sized for somebody walking to the next phone and
        // pressing a button, back when that is how a round was started. A host that tells the
        // room over the standing line knows how many it told, so it can say when they are all
        // here. False every time falls back to the silence, which is what a caller that cannot
        // count its room should do.
        //
        // Asked with 0 as well, every LOOK_MILLIS while nobody has arrived: a room whose every
        // handset said why not is complete with nobody in it, and that ends as NOBODY_COMING
        // rather than five minutes later as a timeout.
        enough: (Int) -> Boolean = { false },
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
        supersededAsks = 0
        calledOff = false
        val waiting = ArrayList<Pair<Socket, CalibrationRequest>>()
        var closesAt = Long.MAX_VALUE
        return runCatching {
            // The first wait runs from the call, not from each time the room empties out again.
            val firstWaitEndsAt = System.nanoTime() + firstWaitMillis * 1_000_000L
            while (true) {
                val socket = try {
                    if (waiting.isEmpty()) {
                        val left = ((firstWaitEndsAt - System.nanoTime()) / 1_000_000).coerceAtLeast(1L).toInt()
                        acceptWhileSomebodyMightCome(bound, left) { runCatching { enough(0) }.getOrDefault(false) }
                    } else {
                        val left = (closesAt - System.nanoTime()) / 1_000_000
                        bound.soTimeout = minOf(settleMillis.toLong(), left).coerceAtLeast(1L).toInt()
                        bound.accept()
                    }
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
                // The newer ask wins, on the same terms and for the same reason RoomCommandServer
                // keeps one socket per name: nothing is ever written to a waiting ask, and a
                // socket nobody writes to is a socket nobody notices go stale.
                val stale = waiting.filter { it.second.sinkId == request.sinkId }
                if (stale.isNotEmpty()) {
                    waiting.removeAll(stale)
                    supersededAsks += stale.size
                    for ((old, _) in stale) runCatching { old.close() }
                }
                // Keyed on whether anybody has ever asked rather than on the list being empty:
                // the list can empty out again above, and a window that restarted there would
                // hold the room open past what a sink will wait for.
                if (closesAt == Long.MAX_VALUE) closesAt = System.nanoTime() + roomWindowMillis * 1_000_000L
                waiting += socket to request
                runCatching { onJoined(waiting.size) }
                // Before the window check rather than after: a complete room has nothing left to
                // wait for, and the two conditions end the same loop.
                if (runCatching { enough(waiting.size) }.getOrDefault(false)) break
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
        }.also { outcome ->
            // Only on the way out without a plan: a room that was answered has already had the
            // schedule written to these same sockets, and a second message behind it would reach
            // the sink glued to the end of the first.
            if (calledOff && outcome.isFailure) {
                val goodbye = CALLED_OFF.toByteArray(Charsets.UTF_8)
                for ((socket, _) in waiting) runCatching {
                    socket.getOutputStream().apply { write(goodbye); flush() }
                }
            }
            for ((socket, _) in waiting) runCatching { socket.close() }
        }.onFailure {
            failureCode = when {
                calledOff -> CALLED_OFF
                it is NobodyComing -> NOBODY_COMING
                it is SocketTimeoutException -> TIMEOUT
                it is IllegalArgumentException -> "PLAN_REFUSED"
                else -> "PLAN_UNREADABLE"
            }
        }.getOrNull()
    }

    /** Handsets whose ask could not be read, and were let go while the room went on. */
    @Volatile
    var refusedSinks: Int = 0
        private set

    /** Asks dropped because the same handset asked again, which is the newer one arriving. */
    @Volatile
    var supersededAsks: Int = 0
        private set

    /**
     * Ends a gathering early and tells everybody already in it, rather than dropping them.
     *
     * Closing the bound socket is the only thing that wakes the thread parked in accept(), so
     * that part is [stop]; the flag is what turns a socket closed under a waiting handset into a
     * sentence it can read. Called off is not a failure of anything - somebody pressed a button -
     * and it is the one ending this exchange has that nobody needs to debug afterwards.
     */
    fun callOffRoom() {
        calledOff = true
        stop()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /**
     * Accepts the next ask within [timeoutMillis], looking every [LOOK_MILLIS] at whether anybody
     * is still coming at all.
     *
     * In slices because accept() cannot be woken by anything but a connection or a closed socket,
     * and closing it is the stop button's - see [callOffRoom]. Throws [SocketTimeoutException] when
     * the time runs out and [NobodyComing] when [nobodyComing] says so first.
     */
    private fun acceptWhileSomebodyMightCome(
        bound: ServerSocket,
        timeoutMillis: Int,
        nobodyComing: () -> Boolean
    ): Socket {
        val endsAt = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            val left = (endsAt - System.nanoTime()) / 1_000_000
            bound.soTimeout = minOf(LOOK_MILLIS.toLong(), left).coerceAtLeast(1L).toInt()
            try {
                return bound.accept()
            } catch (quiet: SocketTimeoutException) {
                if (nobodyComing()) throw NobodyComing()
                if (System.nanoTime() >= endsAt) throw quiet
            }
        }
    }

    /** An ask this server could not read, as opposed to one the host declined to serve. */
    private class GarbledRequest(cause: Throwable) : RuntimeException(cause)

    /** Everybody this wait was for has said they are not coming. */
    private class NobodyComing : RuntimeException()

    companion object {
        /**
         * Everybody told said why they will not ask, so the wait ended with nobody in it.
         *
         * Not a failure of the round: the reasons are the answer, and each was already said
         * against its own device as it arrived.
         */
        const val NOBODY_COMING = "PLAN_NOBODY_COMING"

        /**
         * How often a wait with nobody in it looks at whether anybody is still coming. Short
         * against the five minutes it replaces; long against a loop that would only spin.
         */
        const val LOOK_MILLIS = 250

        /**
         * Nobody asked inside the wait.
         *
         * Named rather than left a literal because a caller serving several handsets off one press
         * has to tell it apart from every other failure: this one means the session is finished,
         * and the rest mean this handset's turn went wrong and the next one is still owed theirs.
         * Two literals compared across two files is exactly the drift that has no symptom.
         */
        const val TIMEOUT = "PLAN_TIMEOUT"

        /**
         * What a called-off room answers with, and the code it reports having done so under.
         *
         * One string for both because they are one fact seen from the two ends, and the pair of
         * them that drifted apart would be a host saying it told the room while the room read
         * something it could not place.
         */
        const val CALLED_OFF = "room-called-off"
    }
}
