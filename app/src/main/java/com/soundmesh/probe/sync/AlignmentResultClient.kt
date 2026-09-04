package com.soundmesh.probe.sync

import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.AlignmentResultCodec
import com.soundmesh.core.CalibrationReply
import com.soundmesh.core.CalibrationReplyCodec
import java.net.Socket

/**
 * Hands this handset's reading of the run to the one that combines the two.
 *
 * Sent unconditionally, including from a run that recorded nothing and has no readings at all: the
 * host waits on this message, so an empty delivery is what tells it there is nothing coming rather
 * than that the sink has died. The two are not the same and must not be reported as the same.
 */
class AlignmentResultClient(private val hostAddress: String, private val port: Int) {
    /**
     * Sends one run's readings and waits for what the other side made of them.
     *
     * Half-closes rather than closes: the half-close is what marks the end of the readings, and
     * the answer comes back down the same socket. Throws if any of it fails.
     */
    fun exchange(caseId: String, appliedOffsetMicros: Long, readings: List<AlignmentReading>): CalibrationReply =
        Socket(hostAddress, port).use { socket ->
            socket.soTimeout = REPLY_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write(AlignmentResultCodec.encode(caseId, appliedOffsetMicros, readings).toByteArray(Charsets.UTF_8))
                flush()
            }
            socket.shutdownOutput()
            CalibrationReplyCodec.decode(String(socket.getInputStream().readBytes(), Charsets.UTF_8))
        }

    private companion object {
        /**
         * The other side answers as soon as it has combined, which is arithmetic on numbers it
         * already holds. This bounds a host that died between accepting and answering.
         */
        const val REPLY_TIMEOUT_MILLIS = 30_000
    }
}
