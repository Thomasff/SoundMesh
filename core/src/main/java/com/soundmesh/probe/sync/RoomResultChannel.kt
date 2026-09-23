package com.soundmesh.probe.sync

import com.soundmesh.core.RoomReply
import com.soundmesh.core.RoomReplyCodec
import com.soundmesh.core.RoomResultCodec
import com.soundmesh.core.RoomResultMessage
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Takes delivery of every handset's hearing of one room window, then answers them all.
 *
 * [AlignmentResultServer] takes one delivery and answers it, because a pair's answer needs only
 * the two halves already in hand. A room's does not: the pair between two sinks is made of two
 * deliveries this host has yet to receive, so nothing can be answered until everything has
 * arrived. Gather, combine once, answer everybody - the same inversion [CalibrationPlanServer]
 * already makes at the other end of the run, and for the same reason.
 *
 * Its own port and its own server rather than a mode on [AlignmentResultServer]: a pair's server
 * holds one socket and a room's holds N-1 of them at once, and the two would have shared nothing
 * but the accept.
 */
class RoomResultServer(private val port: Int) {
    @Volatile private var server: ServerSocket? = null

    /** Why [awaitRoom] came back short, or null if it did not. */
    @Volatile
    var failureCode: String? = null
        private set

    /**
     * Binds the port. Called at the start of a run rather than at the end, so a handset that
     * finishes its own correlation first is held in the accept backlog instead of being refused.
     */
    fun start() {
        server = ServerSocket(port)
    }

    /**
     * Waits for [expected] deliveries, combines them with [combine], and answers each sender with
     * what that answer says about its own pairs.
     *
     * One wait rather than the two [CalibrationPlanServer.awaitRoom] uses. That one is gathering
     * people pressing buttons, which is why it needs a settle gap; this one is gathering handsets
     * that all recorded the same window and finished it at the same instant, so anybody who is
     * coming is already on the way.
     *
     * Bounded by what a sender will wait for, checked rather than commented: every handset holds
     * its socket open across the whole gathering, so a room held open longer than
     * [RoomResultClient.REPLY_TIMEOUT_MILLIS] answers into sockets nobody is listening on - and
     * then the senders report a host that never replied while this side reports a room it served.
     *
     * Returns what actually arrived, which may be short. A room that lost one handset still
     * measured every pair the rest are in, and refusing the lot would throw away N-2 handsets'
     * worth of measurement over one that went quiet. [failureCode] says it was short.
     */
    fun awaitRoom(
        expected: Int,
        timeoutMillis: Int,
        combine: (List<RoomResultMessage>) -> Map<String, RoomReply>
    ): List<RoomResultMessage> {
        require(timeoutMillis < RoomResultClient.REPLY_TIMEOUT_MILLIS) {
            "a room held open for $timeoutMillis ms outlasts what a handset will wait for"
        }
        val bound = server ?: run {
            failureCode = "ROOM_UNBOUND"
            return emptyList()
        }
        val waiting = ArrayList<Pair<Socket, RoomResultMessage>>()
        val closesAt = System.nanoTime() + timeoutMillis * 1_000_000L
        try {
            while (waiting.size < expected) {
                val left = (closesAt - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                bound.soTimeout = left.toInt()
                val socket = try {
                    bound.accept()
                } catch (quiet: SocketTimeoutException) {
                    break
                }
                socket.soTimeout = timeoutMillis
                // The sender half-closes to mark the end of its hearing, which leaves this
                // direction open for the answer.
                val message = runCatching {
                    RoomResultCodec.decode(String(socket.getInputStream().readBytes(), Charsets.UTF_8))
                }.getOrNull()
                if (message == null) {
                    // One handset speaking a wire this build does not know costs that handset, not
                    // the room. The alternative makes an out-of-date phone look like a broken host.
                    unreadableSenders++
                    runCatching { socket.close() }
                    continue
                }
                waiting += socket to message
            }
            if (waiting.size < expected) {
                failureCode = if (waiting.isEmpty()) "ROOM_NOBODY_DELIVERED" else "ROOM_SHORT"
            }
            // Combined once, over everything that arrived: a host that answered each handset as it
            // landed would be answering from a room that was still filling up.
            val replies = runCatching { combine(waiting.map { it.second }) }.getOrElse {
                failureCode = "ROOM_UNCOMBINED"
                emptyMap()
            }
            for ((socket, message) in waiting) {
                val reply = replies[message.senderId] ?: RoomReply(waiting.size + 1, 0)
                runCatching {
                    socket.getOutputStream().apply {
                        write(RoomReplyCodec.encode(reply).toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            }
            return waiting.map { it.second }
        } catch (failed: Exception) {
            failureCode = "ROOM_UNREADABLE"
            return waiting.map { it.second }
        } finally {
            for ((socket, _) in waiting) runCatching { socket.close() }
        }
    }

    /** Handsets whose delivery could not be read, and were let go while the room went on. */
    @Volatile
    var unreadableSenders: Int = 0
        private set

    fun stop() {
        runCatching { server?.close() }
        server = null
    }
}

/**
 * Hands this handset's hearing of the room to the one that combines them all.
 *
 * Sent unconditionally, including from a run that recorded nothing and heard no slot at all: the
 * host is gathering, so a handset that simply never connects holds the whole room open until the
 * gathering times out. An empty delivery costs the room nothing; a missing one costs it the wait.
 */
class RoomResultClient(private val hostAddress: String, private val port: Int) {
    /**
     * Sends one window's hearing and waits for what the room made of it.
     *
     * Half-closes rather than closes: the half-close marks the end of the hearing, and the answer
     * comes back down the same socket once every other handset has delivered too. Throws if any of
     * it fails.
     */
    fun exchange(message: RoomResultMessage): RoomReply =
        Socket(hostAddress, port).use { socket ->
            socket.soTimeout = REPLY_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write(RoomResultCodec.encode(message).toByteArray(Charsets.UTF_8))
                flush()
            }
            socket.shutdownOutput()
            RoomReplyCodec.decode(String(socket.getInputStream().readBytes(), Charsets.UTF_8))
        }

    companion object {
        /**
         * How long a handset waits for the room's answer.
         *
         * Longer than a pair's wait has to be, and for a reason a pair does not have: this wait
         * covers the host gathering everybody else, not just the arithmetic. The handset that
         * delivers first waits out the host's own correlation pass and then every handset that
         * delivers after it - two passes, where a pair's 120 s covers one.
         */
        const val REPLY_TIMEOUT_MILLIS = 180_000
    }
}
