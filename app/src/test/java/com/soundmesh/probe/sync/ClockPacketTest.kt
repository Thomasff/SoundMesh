package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClockPacketTest {
    @Test
    fun carriesAllFourTimestampsBackToTheAsker() {
        val request = ClockPacket.encodeRequest(seq = 9, t1 = 1_000)
        val reply = ClockPacket.encodeReply(request, t2 = 5_000, t3 = 5_100)

        val exchange = ClockPacket.decodeReply(reply, t4 = 1_400)

        assertEquals(1_000L, exchange.t1)
        assertEquals(5_000L, exchange.t2)
        assertEquals(5_100L, exchange.t3)
        assertEquals(1_400L, exchange.t4)
        assertEquals(300L, exchange.roundTripNanos)
    }

    @Test
    fun keepsThePacketSmallEnoughForASingleDatagram() {
        assertEquals(32, ClockPacket.BYTES)
        assertEquals(32, ClockPacket.encodeRequest(1, 1).size)
    }

    @Test
    fun rejectsADatagramOfTheWrongSize() {
        assertThrows(IllegalArgumentException::class.java) {
            ClockPacket.decodeReply(ByteArray(20), t4 = 0)
        }
    }

    @Test
    fun carriesTheSequenceNumberThroughToTheReply() {
        val request = ClockPacket.encodeRequest(seq = 7, t1 = 1_000)
        val reply = ClockPacket.encodeReply(request, t2 = 2_000, t3 = 2_100)

        assertEquals(7, ClockPacket.sequenceOf(reply))
    }
}
