package com.soundmesh.probe.sync

import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationPlanCodec
import com.soundmesh.core.CalibrationRequest
import java.net.Socket

/**
 * Asks the host for the schedule this handset is about to run.
 *
 * Throws rather than returning null: without a plan there is nothing to run, and a calibration
 * that quietly invented its own instants would play chirps nobody was listening for.
 */
class CalibrationPlanClient(private val hostAddress: String, private val port: Int) {
    /**
     * Asks for [caseId], the case both handsets will file this run under, as [sinkId].
     *
     * The case travels from here because only this side knows what kind of run it is: the host
     * serves whoever asks and cannot tell a measurement from a check. A host that assumed put both
     * kinds in one directory, where the later silently overwrote the earlier.
     *
     * The name travels for the same reason one dimension over. The host writes its own half of the
     * run under the peer it ran with, and it has no other way to learn which peer that was: a
     * second sink measured on the same host would land on the first one's file.
     */
    fun request(caseId: String, sinkId: String): CalibrationPlan =
        Socket(hostAddress, port).use { socket ->
            socket.soTimeout = REPLY_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                val ask = CalibrationPlanCodec.encodeRequest(CalibrationRequest(caseId, sinkId))
                write(ask.toByteArray(Charsets.UTF_8))
                flush()
            }
            // The half-close is what tells the host the ask is complete; without it both ends wait.
            socket.shutdownOutput()
            val answered = String(socket.getInputStream().readBytes(), Charsets.UTF_8)
            // Told apart before it is parsed, because everything a plan cannot be parsed from
            // reads the same afterwards: a host that died, a build that speaks another version
            // and a person who changed their mind all arrive as "not a calibration plan:".
            if (answered.trim() == CalibrationPlanServer.CALLED_OFF) throw RoomCalledOff()
            CalibrationPlanCodec.decode(answered)
        }

    /** The host called the round off while this handset was waiting to be given a schedule. */
    class RoomCalledOff : RuntimeException("the host called this round off")

    internal companion object {
        /**
         * The host answers with arithmetic on numbers it already holds. This bounds a host that
         * died between accepting and answering.
         */
        internal const val REPLY_TIMEOUT_MILLIS = 30_000
    }
}
