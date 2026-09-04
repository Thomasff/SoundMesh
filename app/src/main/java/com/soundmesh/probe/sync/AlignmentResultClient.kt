package com.soundmesh.probe.sync

import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.AlignmentResultCodec
import java.net.Socket

/**
 * Hands this handset's reading of the run to the one that combines the two.
 *
 * Sent unconditionally, including from a run that recorded nothing and has no readings at all: the
 * host waits on this message, so an empty delivery is what tells it there is nothing coming rather
 * than that the sink has died. The two are not the same and must not be reported as the same.
 */
class AlignmentResultClient(private val hostAddress: String, private val port: Int) {
    /** Sends one run's readings, closing the stream to mark the end. Throws if it cannot. */
    fun send(caseId: String, readings: List<AlignmentReading>) {
        Socket(hostAddress, port).use { socket ->
            socket.getOutputStream().apply {
                write(AlignmentResultCodec.encode(caseId, readings).toByteArray(Charsets.UTF_8))
                flush()
            }
            socket.shutdownOutput()
        }
    }
}
