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
    fun request(): CalibrationPlan =
        Socket(hostAddress, port).use { socket ->
            socket.soTimeout = REPLY_TIMEOUT_MILLIS
            // Half-closed rather than written to: the request carries nothing, and the half-close
            // is what tells the host the ask is complete.
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
