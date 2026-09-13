package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomResultCodecTest {
    private val SENDER = "a1b2c3d4e5f60718"

    private fun heard(index: Int, edges: List<Int> = emptyList()) = ChirpArrival(
        index = index,
        peak = 41_233.5,
        floor = 1_012.25,
        ratio = 40.73,
        atSearchEdge = false,
        edgeIndices = edges
    )

    private fun message(arrivals: List<List<ChirpArrival?>>, ownSlot: Int = 0) =
        RoomResultMessage("C94", SENDER, ownSlot, arrivals)

    @Test
    fun roundTripsAWholeRoomWindowUnchanged() {
        val sent = message(
            listOf(
                listOf(heard(48_000), null, heard(96_120)),
                listOf(heard(288_000), heard(312_050), heard(336_090))
            ),
            ownSlot = 2
        )

        val read = RoomResultCodec.decode(RoomResultCodec.encode(sent))

        assertEquals(sent, read)
    }

    /** The shares the distance arm sweeps ride along, and they are what the answer rests on. */
    @Test
    fun roundTripsTheEdgesEachShareLandedOn() {
        val sent = message(listOf(listOf(heard(48_000, listOf(47_880, 47_901, 47_860)), heard(72_000))))

        assertEquals(sent, RoomResultCodec.decode(RoomResultCodec.encode(sent)))
    }

    /**
     * A correlation floor of zero leaves the ratio infinite, and that is a real reading rather
     * than a broken one.
     */
    @Test
    fun roundTripsAnInfiniteRatio() {
        val sent = message(listOf(listOf(heard(48_000).copy(floor = 0.0, ratio = Double.POSITIVE_INFINITY), null)))

        assertEquals(sent, RoomResultCodec.decode(RoomResultCodec.encode(sent)))
    }

    /**
     * A handset that recorded nothing still delivers. The host gathers the room before it combines,
     * so a sender that simply never connected would hold the whole room open until it timed out.
     */
    @Test
    fun roundTripsAHandsetThatHeardNothing() {
        val sent = message(emptyList())

        val read = RoomResultCodec.decode(RoomResultCodec.encode(sent))

        assertEquals(emptyList<List<ChirpArrival?>>(), read.arrivalsByRepeat)
        assertEquals(SENDER, read.senderId)
    }

    /**
     * The failure this format is shaped around: the sender closes the socket to mark the end, so a
     * connection dropped mid-delivery arrives as a short room rather than as an error.
     */
    @Test
    fun refusesADeliveryThatWasCutShort() {
        val whole = RoomResultCodec.encode(message(listOf(listOf(heard(48_000), heard(72_000)))))

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            RoomResultCodec.decode(whole.substringBeforeLast("\n"))
        }

        assertTrue(thrown.message, thrown.message!!.contains("promised 2"))
    }

    /** A truncated arrival line would otherwise read as an arrival somewhere else entirely. */
    @Test
    fun refusesAnArrivalMissingAField() {
        val whole = RoomResultCodec.encode(message(listOf(listOf(heard(48_000), heard(72_000)))))

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            RoomResultCodec.decode(whole.replace(" false ", " "))
        }

        assertTrue(thrown.message, thrown.message!!.contains("expected 6"))
    }

    /** A room's delivery must not be mistaken for a pair's, or the other way round. */
    @Test
    fun refusesAPairsDelivery() {
        val pair = AlignmentResultCodec.encode("C90", SENDER, 0L, emptyList())

        assertThrows(IllegalArgumentException::class.java) { RoomResultCodec.decode(pair) }
    }

    @Test
    fun refusesAWireVersionItDoesNotKnow() {
        val whole = RoomResultCodec.encode(message(listOf(listOf(heard(48_000), heard(72_000)))))

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            RoomResultCodec.decode(whole.replaceFirst(" 1 ", " 2 "))
        }

        assertTrue(thrown.message, thrown.message!!.contains("version"))
    }

    /**
     * The slot is the anchor the hearing was read against. One outside the room it claims to have
     * heard describes no handset, and combining it would put a real pair on the wrong two phones.
     */
    @Test
    fun refusesASlotThatIsNotOneOfTheOnesHeard() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomResultCodec.encode(message(listOf(listOf(heard(48_000), heard(72_000))), ownSlot = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoomResultCodec.decode(
                RoomResultCodec.encode(message(listOf(listOf(heard(1), heard(2))))).replaceFirst(" 0 1 2", " 5 1 2")
            )
        }
    }

    /**
     * Every repeat hears the same room. Repeats of differing length would make the header's two
     * counts unable to say where one repeat ends, which is what catches a truncated delivery.
     */
    @Test
    fun refusesRepeatsThatHeardDifferentRooms() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            RoomResultCodec.encode(message(listOf(listOf(heard(1), heard(2)), listOf(heard(3)))))
        }

        assertTrue(thrown.message, thrown.message!!.contains("same room"))
    }

    @Test
    fun refusesAHandsetNameWithASpaceInIt() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomResultCodec.encode(RoomResultMessage("C94", "a1b2 c3d4", 0, emptyList()))
        }
    }

    @Test
    fun roundTripsAReplyAndRefusesOneThatIsNotAReply() {
        val reply = RoomReply(handsets = 3, ownPairsReadable = 2)

        assertEquals(reply, RoomReplyCodec.decode(RoomReplyCodec.encode(reply)))
        assertThrows(IllegalArgumentException::class.java) { RoomReplyCodec.decode("soundmesh-room 1 3 2") }
        assertThrows(IllegalArgumentException::class.java) {
            RoomReplyCodec.decode(RoomReplyCodec.encode(reply).replace(" 3 ", " -1 "))
        }
    }

    /**
     * The offer a room makes a handset that has never been measured rides on the same line.
     *
     * Signed, and that is the field worth a test of its own: a correction with the sign inverted
     * does not fail to help, it doubles the error it was sent to remove.
     */
    @Test
    fun roundTripsTheOfferARoomMakesIncludingItsSign() {
        val offered = RoomReply(handsets = 4, ownPairsReadable = 3, approximateOffsetMicros = -35_948L)

        assertEquals(offered, RoomReplyCodec.decode(RoomReplyCodec.encode(offered)))
        assertEquals(
            offered.copy(approximateOffsetMicros = 35_948L),
            RoomReplyCodec.decode(RoomReplyCodec.encode(offered.copy(approximateOffsetMicros = 35_948L)))
        )
        assertThrows(IllegalArgumentException::class.java) {
            RoomReplyCodec.decode("soundmesh-room-reply 2 4 3 nearly")
        }
    }

    /**
     * A reply from a host that predates the offer still reads, and reads as no offer.
     *
     * Handsets in one room are updated one at a time - by somebody sending an install file to
     * each phone - so a version this build has to speak to is not hypothetical. No offer is the
     * right reading of silence here: a build that could not have made one did not make one.
     */
    @Test
    fun aReplyFromBeforeTheOfferStillReadsAsNoOffer() {
        assertEquals(
            RoomReply(handsets = 3, ownPairsReadable = 2, approximateOffsetMicros = null),
            RoomReplyCodec.decode("soundmesh-room-reply 1 3 2")
        )
    }
}
