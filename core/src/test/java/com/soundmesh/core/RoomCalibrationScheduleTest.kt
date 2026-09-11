package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule for a room rather than for a pair: one window in which every handset chirps once,
 * in the slot the plan gave it.
 *
 * The pair schedule is the same thing with two slots, and the test that says so is the one that
 * matters here - a plan naming nobody has to keep meaning exactly what it meant before slots
 * existed, or every archived run stops being comparable with the next one.
 */
class RoomCalibrationScheduleTest {
    private val chirpNanos = 120_000_000L
    private val stagger = 500_000_000L
    private val first = 10_000_000_000L

    private val one = "a1b2c3d4e5f60718"
    private val two = "0918273645abcdef"
    private val three = "1122334455667788"

    private fun plan(slots: List<String>) = CalibrationPlan(
        caseId = "C94",
        hostId = one,
        firstChirpAtHostNanos = first,
        staggerNanos = stagger,
        repeats = 2,
        intervalNanos = 5_000_000_000L,
        slotIds = slots
    )

    @Test
    fun givesEachHandsetTheSlotThePlanNamedItIn() {
        val room = plan(listOf(one, two, three))

        for (slot in 0 until 3) {
            val timing = CalibrationSchedule.of(room, slot, chirpNanos)
            assertEquals(
                "slot $slot",
                listOf(first + slot * stagger, first + 5_000_000_000L + slot * stagger),
                timing.ownChirpAtHostNanos
            )
        }
    }

    /**
     * The recording closes after the last handset in the room has chirped, not after this one has.
     * Every handset hears every chirp and the measurement is made of all of them, so a window that
     * ended at this handset's own last chirp would drop everybody scheduled after it - which reads
     * afterwards as a room that went quiet rather than as a window that closed early.
     */
    @Test
    fun keepsRecordingUntilTheLastHandsetInTheRoomHasChirped() {
        val room = plan(listOf(one, two, three))
        val lastSound = first + 5_000_000_000L + 2 * stagger + chirpNanos

        for (slot in 0 until 3) {
            assertEquals(
                lastSound + CalibrationSchedule.RECORD_TAIL_NANOS,
                CalibrationSchedule.of(room, slot, chirpNanos).recordUntilHostNanos
            )
        }
    }

    /**
     * The pin. A plan that names no slots is the pair it always was: the sink chirps first, the
     * host one stagger later, and the window closes after the host. Every archived run was made by
     * that path and a schedule that moved by a stagger would end their comparability in silence.
     */
    @Test
    fun treatsAPlanNamingNobodyAsThePairItAlwaysWas() {
        val pair = plan(emptyList())

        val sink = CalibrationSchedule.of(pair, CalibrationRole.SINK, chirpNanos)
        val host = CalibrationSchedule.of(pair, CalibrationRole.HOST, chirpNanos)

        assertEquals(CalibrationSchedule.of(pair, 0, chirpNanos), sink)
        assertEquals(CalibrationSchedule.of(pair, 1, chirpNanos), host)
        assertEquals(first, sink.ownChirpAtHostNanos.first())
        assertEquals(first + stagger, host.ownChirpAtHostNanos.first())
    }

    /** And a room of two named handsets schedules exactly as the unnamed pair does. */
    @Test
    fun schedulesTwoNamedHandsetsExactlyAsTheUnnamedPair() {
        val named = plan(listOf(two, one))
        val unnamed = plan(emptyList())

        assertEquals(
            CalibrationSchedule.of(unnamed, CalibrationRole.SINK, chirpNanos),
            CalibrationSchedule.of(named, 0, chirpNanos)
        )
        assertEquals(
            CalibrationSchedule.of(unnamed, CalibrationRole.HOST, chirpNanos),
            CalibrationSchedule.of(named, 1, chirpNanos)
        )
    }

    /**
     * The host takes the last slot rather than the first, which is the convention the pair has
     * always used and the one [AlignmentAnalysis.combineFacing] reads its signs from.
     */
    @Test
    fun putsTheHostInTheLastSlotOfWhateverRoomItIsIn() {
        val room = plan(listOf(two, three, one))

        assertEquals(
            CalibrationSchedule.of(room, 2, chirpNanos),
            CalibrationSchedule.of(room, CalibrationRole.HOST, chirpNanos)
        )
    }

    @Test
    fun refusesASlotThePlanDoesNotHave() {
        try {
            CalibrationSchedule.of(plan(listOf(one, two)), 2, chirpNanos)
            throw AssertionError("expected to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("slot"))
        }
    }

    /**
     * Two handsets sharing a slot would chirp together and be indistinguishable, and a handset
     * named twice would be two icons for one phone in whatever the answer is drawn as.
     */
    @Test
    fun refusesARoomThatNamesOneHandsetTwice() {
        try {
            CalibrationSchedule.of(plan(listOf(one, two, one)), 0, chirpNanos)
            throw AssertionError("expected to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("names each handset once"))
        }
    }

    /** A room the handsets are named in is a room the answer can name them back in. */
    @Test
    fun carriesTheRoomOverTheWire() {
        val room = plan(listOf(one, two, three))

        val back = CalibrationPlanCodec.decode(CalibrationPlanCodec.encode(room))

        assertEquals(room, back)
        assertEquals(listOf(one, two, three), back.slotIds)
    }

    @Test
    fun carriesAPairThatNamesNobodyOverTheWireToo() {
        val pair = plan(emptyList())

        assertEquals(pair, CalibrationPlanCodec.decode(CalibrationPlanCodec.encode(pair)))
    }

    /**
     * The ceiling on how many handsets one schedule holds, which is arithmetic rather than an
     * opinion: the window grows with the room and the interval does not, so past a certain size
     * the last handset of one repeat lands on the first handset of the next - where the two are
     * told apart only by which correlates louder, which is a property of the room.
     */
    @Test
    fun refusesARoomTooBigToFitBetweenTwoRepeats() {
        val names = (0 until 11).map { "%016x".format(it) }
        val tooMany = plan(names).copy(intervalNanos = 5_000_000_000L)

        val thrown = runCatching { CalibrationSchedule.of(tooMany, 0, chirpNanos) }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalArgumentException)
        assertTrue(thrown!!.message, thrown.message!!.contains("does not fit"))
        // Widen the interval and the same room is fine: it is the pair of numbers that is refused,
        // never the number of handsets on its own.
        CalibrationSchedule.of(tooMany.copy(intervalNanos = 7_000_000_000L), 0, chirpNanos)
    }

    /** One repeat has no next one to land on, so the ceiling does not apply to it. */
    @Test
    fun letsASingleRepeatHoldARoomWiderThanItsInterval() {
        val names = (0 until 11).map { "%016x".format(it) }

        val timing = CalibrationSchedule.of(plan(names).copy(repeats = 1), 0, chirpNanos)

        assertEquals(1, timing.ownChirpAtHostNanos.size)
    }
}
