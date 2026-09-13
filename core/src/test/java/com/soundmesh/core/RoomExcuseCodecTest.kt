package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one message that travels from a handset that is refusing to work.
 *
 * Which makes it the least trusted sender in the project, so the shape is checked rather than
 * taken: a name that is not a name reaches a screen, and on the host side a name is also a file
 * name everywhere else.
 */
class RoomExcuseCodecTest {
    private val peer = "a1b2c3d4e5f60718"

    @Test
    fun roundTripsEveryReasonThereIs() {
        for (excuse in RoomExcuse.entries) {
            assertEquals(peer to excuse, RoomExcuseCodec.decode(RoomExcuseCodec.encode(peer, excuse)))
        }
    }

    /**
     * Not an excuse is an answer, not a fault: the only caller is deciding which kind of frame it
     * is holding, and the other kind is a handset announcing its name.
     */
    @Test
    fun answersNothingForAFrameThatIsNotAnExcuse() {
        assertNull(RoomExcuseCodec.decode(peer))
        assertNull(RoomExcuseCodec.decode("carrying none"))
        assertNull(RoomExcuseCodec.decode(""))
        assertNull(RoomExcuseCodec.decode("excuse $peer"))
    }

    /** A reason this build does not know is not one it may act on, and a name must be a name. */
    @Test
    fun refusesAReasonOrANameItCannotRead() {
        assertNull(RoomExcuseCodec.decode("excuse $peer WANDERED_OFF"))
        assertNull(RoomExcuseCodec.decode("excuse ../../etc NO_MICROPHONE"))
        assertNull(RoomExcuseCodec.decode("excuse  NO_MICROPHONE"))
    }
}
