package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One handset telling another to do something, which is the first message in this project that is
 * an instruction rather than a description.
 */
class RoomCommandCodecTest {
    @Test
    fun `every command survives the wire`() {
        for (command in RoomCommand.values()) {
            val order = RoomOrder(command)
            assertEquals(order, RoomCommandCodec.decode(RoomCommandCodec.encode(order)))
        }
    }

    /**
     * A version this build does not speak is refused whole.
     *
     * The same rule [SpatialFieldCodec] keeps, and here it matters more: a reader that acted on
     * the part of a newer message it recognised would be a handset doing something nobody in the
     * room asked it to do.
     */
    @Test
    fun `a command from a build this one does not speak is refused`() {
        val newer = RoomCommandCodec.encode(RoomOrder(RoomCommand.PLAY))
            .replace(" ${RoomCommandCodec.VERSION} ", " ${RoomCommandCodec.VERSION + 1} ")

        val thrown = runCatching { RoomCommandCodec.decode(newer) }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalArgumentException)
    }

    /** A command this build has no name for is refused rather than guessed at. */
    @Test
    fun `a command with no name here is refused`() {
        val thrown = runCatching {
            RoomCommandCodec.decode("${RoomCommandCodec.MAGIC} ${RoomCommandCodec.VERSION} DANCE")
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalArgumentException)
    }

    /**
     * The number some commands carry, which is a percentage and not an index.
     *
     * Handsets do not agree on how many steps a stream has - fifteen on one, sixteen on the next -
     * so an index set across a room is a different loudness on every handset in it.
     */
    @Test
    fun `a command can carry one number`() {
        val order = RoomOrder(RoomCommand.SET_VOLUME, 60)

        assertEquals(order, RoomCommandCodec.decode(RoomCommandCodec.encode(order)))
        // And the plain form still has none, which is what every command before this one is.
        assertEquals(null, RoomCommandCodec.decode(RoomCommandCodec.encode(RoomOrder(RoomCommand.PLAY))).value)
    }

    /**
     * A number that is not one is refused rather than dropped.
     *
     * Dropping it would leave a handset doing SET_VOLUME with no volume, which is the shape of
     * fault this codec exists to refuse: acting on the part of a message that parsed.
     */
    @Test
    fun `a command whose number is not a number is refused`() {
        val thrown = runCatching {
            RoomCommandCodec.decode("${RoomCommandCodec.MAGIC} ${RoomCommandCodec.VERSION} SET_VOLUME loud")
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalArgumentException)
    }

    /** Another message on another channel is not one of these, whatever it starts with. */
    @Test
    fun `something else entirely is refused`() {
        for (text in listOf("", "   ", "soundmesh-playing 1 夜曲", "${RoomCommandCodec.MAGIC} 1")) {
            val thrown = runCatching { RoomCommandCodec.decode(text) }.exceptionOrNull()
            assertTrue("$text gave $thrown", thrown is IllegalArgumentException)
        }
    }
}
