package com.soundmesh.probe.sync

import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationPlanCodec
import java.net.Socket

/**
 * Asks the host for the schedule this handset is about to run.
 *
 * Throws rather than returning null: without a plan there is nothing to run, and a calibration
 * that quietly invented its own instants would play chirps nobody was listening for.
 */
class CalibrationPlanClient(private val hostAddress: String, private val port: Int) {
    /**
     * Asks for [caseId], the case both handsets will file this run under.
     *
     * It travels from here because only this side knows what kind of run it is: the host serves
     * whoever asks and cannot tell a measurement from a check. A host that assumed put both kinds
     * in one directory, where the later silently overwrote the earlier.
     */
    fun request(caseId: String): CalibrationPlan =
        Socket(hostAddress, port).use { socket ->
            socket.soTimeout = REPLY_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write(caseId.toByteArray(Charsets.UTF_8))
                flush()
            }
            // The half-close is what tells the host the ask is complete; without it both ends wait.
            socket.shutdownOutput()
            CalibrationPlanCodec.decode(String(socket.getInputStream().readBytes(), Charsets.UTF_8))
        }

    private companion object {
        /**
         * The host answers with arithmetic on numbers it already holds. This bounds a host that
         * died between accepting and answering.
         */
        const val REPLY_TIMEOUT_MILLIS = 30_000
    }
}
