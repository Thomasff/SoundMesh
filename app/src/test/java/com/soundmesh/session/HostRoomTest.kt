package com.soundmesh.session

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import com.soundmesh.probe.R
import com.soundmesh.product.SessionReadout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a HostSession can be asked before it is started, which is where the drawing comes from.
 *
 * A started one cannot be built off a device - it binds three sockets and its renderer opens an
 * AudioTrack - but the roster and the publish path are both readable without starting anything,
 * and both are things a screen calls directly.
 */
class HostRoomTest {
    private val self = "a1b2c3d4e5f60718"

    private fun session(spatialId: String?) =
        HostSession({ ByteArray(0) }, spatialId = spatialId)

    /** The phone in the listener's hand is in its own room, and is the one they can recognise. */
    @Test
    fun theHostIsTheFirstNameInItsOwnRoom() {
        assertEquals(listOf(self), session(self).roomPeerIds())
    }

    /**
     * A session with no name of its own binds no control channel and draws no room. That is every
     * session that ran before spatial audio existed, and it is what a caller which has not been
     * taught to pass a name still gets.
     */
    @Test
    fun aNamelessSessionHasNoRoomAtAll() {
        assertEquals(emptyList<String>(), session(null).roomPeerIds())
    }

    /**
     * Publishing into a session that has no control channel does nothing rather than throwing. A
     * screen that could crash the app by moving a slider on the wrong build is worse than a slider
     * that does not appear to work.
     */
    @Test
    fun publishingIntoANamelessSessionIsHarmless() {
        val field = SpatialField(
            SpatialMode.PAN,
            SpatialLayout(listOf(SpatialPosition(self, 1.0, 0.0))),
            pan = 0.5
        )

        session(null).publishSpatialField(field)
    }

    /**
     * The wiring, which testing hostReportFields alone cannot reach: that report() hands it this
     * session's own roster, rather than some other list that happens to be a List.
     *
     * Marked started rather than started - start() binds three sockets and opens an AudioTrack.
     *
     * The connection count is asserted too and defends nothing, which is worth saying rather than
     * leaving for a reader to assume otherwise: no socket can be opened here, so the count reads
     * zero whether report() asks the chunk server for it or hands over a constant. Replacing that
     * call with 0 turns no test in this project red. Only a device covers that half.
     */
    @Test
    fun aHostReportsTheRoomItIsIn() {
        val flags = SessionFlags().apply { markStarted() }
        val report = HostSession({ ByteArray(0) }, spatialId = self, flags = flags).report()

        assertEquals(
            "a1b2",
            SessionReadout.counters(report).first { it.label == R.string.counter_room }.value
        )
        assertEquals(
            "0/0",
            SessionReadout.counters(report).first { it.label == R.string.counter_sinks }.value
        )
        // The audio roster is a different list from the one above and is wired from a different
        // object, and the mistake worth catching is that they are both Lists of the same thing:
        // handing the room's roster to both would read as the host being sent its own audio.
        assertEquals("\"audioPeerIds\":\"\"", Regex("\"audioPeerIds\":\"[^\"]*\"").find(report!!)?.value)
    }
}
