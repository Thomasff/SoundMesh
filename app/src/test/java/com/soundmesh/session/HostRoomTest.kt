package com.soundmesh.session

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
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
}
