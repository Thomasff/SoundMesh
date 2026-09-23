package com.soundmesh.probe.sync

import com.soundmesh.core.ChirpCorrelator
import com.soundmesh.core.ChirpGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * One window holding a chirp from every handset, read on the handset that recorded it.
 *
 * The pair path reads two chirps out of a window and answers with the arithmetic between them.
 * A room's window holds one per handset and the arithmetic happens somewhere else entirely - what
 * this side owes the room is where each slot landed, and which slot is its own.
 */
class OnDeviceRoomTest {
    private val second = 1_000_000_000L
    private val rate = ChirpGenerator.SAMPLE_RATE
    private val staggerNanos = second / 2
    private val intervalNanos = 2 * second
    private val staggerFrames = (staggerNanos * rate / second).toInt()

    /**
     * A recording of a whole room window: one chirp per slot, [ownSlot]'s far louder than the rest
     * because its speaker is centimetres from this microphone and everybody else's is metres away.
     *
     * [lateFramesBySlot] moves a slot's chirp off where the schedule put it, which is the only
     * thing a room measures.
     */
    private fun roomRecording(
        repeats: Int,
        leadNanos: Long,
        slotCount: Int,
        ownSlot: Int,
        lateFramesBySlot: Map<Int, Int> = emptyMap(),
        silentSlots: Set<Int> = emptySet()
    ): ShortArray {
        val chirp = ChirpGenerator.generateMono()
        val span = leadNanos + repeats * intervalNanos + slotCount * staggerNanos + 2 * second
        val out = ShortArray((span * rate / second).toInt())
        val noise = Random(11)
        for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
        val leadFrames = (leadNanos * rate / second).toInt()
        val gapFrames = (intervalNanos * rate / second).toInt()
        for (repeat in 0 until repeats) {
            for (slot in 0 until slotCount) {
                if (slot in silentSlots) continue
                val at = leadFrames + repeat * gapFrames + slot * staggerFrames + (lateFramesBySlot[slot] ?: 0)
                val gain = if (slot == ownSlot) 0.8 else 0.2
                for (index in chirp.indices) {
                    out[at + index] = (out[at + index] + chirp[index] * gain).toInt().toShort()
                }
            }
        }
        return out
    }

    private fun read(
        recorded: ShortArray,
        repeats: Int,
        leadNanos: Long,
        slotCount: Int,
        ownSlot: Int
    ) = OnDeviceAlignment.readRoom(
        recorded = recorded,
        reference = ChirpGenerator.generateMono(),
        recordingStartedAtHostNanos = 0,
        firstChirpAtHostNanos = leadNanos,
        staggerNanos = staggerNanos,
        slotCount = slotCount,
        ownSlot = ownSlot,
        chirpRepeats = repeats,
        chirpIntervalNanos = intervalNanos,
        uncertaintyFrames = 4800
    )

    @Test
    fun readsEverySlotOfEveryRepeat() {
        val room = read(roomRecording(2, second, slotCount = 3, ownSlot = 1), 2, second, 3, 1)

        assertEquals(2, room.size)
        assertTrue(room.toString(), room.all { it.size == 3 })
        assertTrue(room.toString(), room.all { repeat -> repeat.all { it != null } })
    }

    /** The slots come back in schedule order, one stagger apart, whichever one is this handset's. */
    @Test
    fun putsEachSlotWhereTheScheduleSaidItWouldBe() {
        val room = read(roomRecording(1, second, slotCount = 4, ownSlot = 2), 1, second, 4, 2)

        val indices = room[0].map { it!!.index }
        for (slot in 1 until indices.size) {
            assertEquals(staggerFrames.toLong(), (indices[slot] - indices[slot - 1]).toLong())
        }
    }

    /** What a room measures: one handset out of step, and by how much. */
    @Test
    fun recoversTheErrorOneHandsetWasGiven() {
        // 96 frames is 2 ms at 48 kHz.
        val recording = roomRecording(1, second, slotCount = 3, ownSlot = 0, lateFramesBySlot = mapOf(2 to 96))

        val room = read(recording, 1, second, 3, 0)

        val indices = room[0].map { it!!.index }
        assertEquals(staggerFrames.toLong(), (indices[1] - indices[0]).toLong())
        assertEquals((2 * staggerFrames + 96).toLong(), (indices[2] - indices[0]).toLong())
    }

    /**
     * The anchor is told rather than worked out, and this is what that buys. The loudest arrival is
     * this handset's own chirp, not the room's first one, so a reader that assumed the loudest must
     * be slot zero would put every handset but one a whole slot out - silently, and plausibly,
     * because the spacing would still come out exactly right.
     */
    @Test
    fun readsTheRoomAgainstTheSlotItWasTold() {
        val recording = roomRecording(1, second, slotCount = 3, ownSlot = 2)

        val told = read(recording, 1, second, 3, 2)
        val guessed = read(recording, 1, second, 3, 0)

        assertEquals(2 * staggerFrames.toLong(), (told[0][2]!!.index - told[0][0]!!.index).toLong())
        // The same recording read against the wrong anchor puts slot zero where slot two really is.
        assertEquals(told[0][2]!!.index, guessed[0][0]!!.index)
        assertNotEquals(told[0][0]!!.index, guessed[0][0]!!.index)
    }

    /**
     * One handset nobody could hear costs its own pairs and nothing else. It comes back as an
     * arrival too weak to trust rather than as a gap, because a room that quietly answered fewer
     * slots than it has looks like a room with fewer handsets in it.
     */
    @Test
    fun keepsTheRoomsShapeWhenOneHandsetIsNotHeard() {
        val recording = roomRecording(1, second, slotCount = 3, ownSlot = 0, silentSlots = setOf(1))

        val room = read(recording, 1, second, 3, 0)

        assertEquals(3, room[0].size)
        assertTrue("the silent slot is not trusted", (room[0][1]?.ratio ?: 0.0) < ChirpCorrelator.MIN_TRUSTWORTHY_RATIO)
        assertTrue("the heard slots still are", room[0][2]!!.ratio >= ChirpCorrelator.MIN_TRUSTWORTHY_RATIO)
        assertEquals(2 * staggerFrames.toLong(), (room[0][2]!!.index - room[0][0]!!.index).toLong())
    }

    /** A room of one has nothing to align against, and must say so rather than answer. */
    @Test
    fun refusesARoomOfOne() {
        val thrown = runCatching {
            read(roomRecording(1, second, slotCount = 2, ownSlot = 0), 1, second, 1, 0)
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalArgumentException)
    }
}
