package com.soundmesh.product

import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationSchedule
import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The room arm: one window, every handset in it, and one pass answering every pair.
 *
 * It coexists with the pair flow rather than replacing it. A pair measures the constant a session
 * applies; a room measures where the handsets are - including the two sinks facing each other,
 * which a host-centred round of pairs never reaches.
 */
class PeerCalibrateRoomTest {
    private val source =
        File("src/main/java/com/soundmesh/product/PeerCalibrateActivity.kt").readText(Charsets.UTF_8)

    private val one = "a1b2c3d4e5f60718"
    private val two = "0918273645abcdef"
    private val three = "1122334455667788"

    private val slotFrames = (STAGGER_NANOS * ChirpGenerator.SAMPLE_RATE / 1_000_000_000L).toInt()

    private fun plan(slots: List<String>) = CalibrationPlan(
        caseId = CASE_ROOM,
        hostId = slots.last(),
        firstChirpAtHostNanos = 10_000_000_000L,
        staggerNanos = STAGGER_NANOS,
        repeats = 2,
        intervalNanos = roomIntervalNanos(DISTANCE_INTERVAL_NANOS, slots.size, STAGGER_NANOS),
        slotIds = slots
    )

    private fun arrival(index: Int, ratio: Double = 40.0) = ChirpArrival(
        index = index,
        peak = 40_000.0,
        floor = 1_000.0,
        ratio = ratio,
        atSearchEdge = false,
        // Every share on the same lag, which is what a clean onset looks like and what
        // measuredSeparationMetres asks for before it will use a pair.
        edgeIndices = List(AlignmentAnalysis.DISTANCE_EDGE_SHARES.size) { index }
    )

    /**
     * What handset [own] hears of a window in which every other handset's chirp arrives late by
     * the flight time between them. Its own chirp arrives with none, because its speaker is
     * centimetres from its microphone.
     */
    private fun hearing(own: Int, slots: Int, flightFrames: (Int, Int) -> Int, quiet: Set<Int> = emptySet()) =
        (0 until slots).map { slot ->
            arrival(
                index = 48_000 + slot * slotFrames + flightFrames(own, slot),
                ratio = if (slot in quiet) 3.0 else 40.0
            )
        }

    /** 2.00 m in frames of flight at 343 m/s. */
    private val twoMetres = (2.0 / AlignmentAnalysis.SPEED_OF_SOUND_M_S * ChirpGenerator.SAMPLE_RATE).toInt()

    // -- the arm ------------------------------------------------------------------------------

    /**
     * A room paired with any other arm describes no run, exactly as the other three do not pair
     * with each other. Picking one silently is the shape of mistake that has already cost this
     * project a day: a flag went missing, the arm swapped, and nothing in the result said so.
     */
    @Test
    fun aRoomIsItsOwnArmAndPairsWithNoOther() {
        assertEquals(CASE_ROOM, calibrationCase(verifying = false, allowSlowLink = false, room = true))
        assertNull(calibrationCase(verifying = true, allowSlowLink = false, room = true))
        assertNull(calibrationCase(verifying = false, allowSlowLink = true, room = true))
        assertNull(calibrationCase(false, allowSlowLink = false, distanceOnly = true, room = true))
        // And nothing about the room reaches the three that were here before it.
        assertEquals(CASE_MEASURE, calibrationCase(verifying = false, allowSlowLink = false))
    }

    /** Five arms, five directories. A case id names a place on disk that nothing ever clears. */
    @Test
    fun theRoomIsItsOwnPlaceOnDisk() {
        assertEquals(5, setOf(CASE_MEASURE, CASE_VERIFY, CASE_SLOW_LINK, CASE_DISTANCE, CASE_ROOM).size)
    }

    /**
     * A room runs the distance arm's schedule, and never goes near the stored constant.
     *
     * What a room uniquely answers is where the handsets are, and a separation is the half
     * difference of two recordings - the clock enters both sides with the same sign and cancels.
     * The alignment numbers it also produces are read with the same suspicion a C93's are: its
     * lead runs no warm-up, so the renderer is still acquiring when the first chirp goes out.
     */
    @Test
    fun aRoomMeasuresDistanceAndKeepsNoCorrection() {
        assertEquals(defaultTimingFor(CASE_DISTANCE), defaultTimingFor(CASE_ROOM))
        assertFalse(keepsCorrection(CASE_ROOM))
    }

    /**
     * The two flows refuse each other's cases rather than tolerating them.
     *
     * A room ask answered by the pair host would get a plan naming nobody, and the handset would
     * look for its slot in an empty list. A pair ask joining a room would be given a slot it never
     * agreed to chirp in. Both are plausible schedules for a run nobody is running.
     */
    @Test
    fun theRoomAndThePairRefuseEachOthersCases() {
        assertTrue(
            "the pair host would serve a room ask",
            source.contains("request.caseId !in setOf(CASE_MEASURE, CASE_VERIFY, CASE_SLOW_LINK, CASE_DISTANCE)")
        )
        assertTrue(
            "the room would gather a handset running another arm",
            source.contains("asks.firstOrNull { it.caseId != CASE_ROOM }")
        )
    }

    /** A handset reads its recording against the slot the plan named it in, never against a role. */
    @Test
    fun eachSideTakesTheSlotThePlanNamedIt() {
        assertTrue("the host does not take its own slot from the plan", source.contains("plan.slotIds.indexOf(hostId)"))
        assertTrue("the sink does not take its own slot from the plan", source.contains("plan.slotIds.indexOf(sinkId)"))
        assertTrue(
            "a handset the room left out would read against no anchor at all",
            source.contains("ROOM_WITHOUT_THIS_HANDSET")
        )
    }

    /**
     * A delivery that read its window against a slot other than the one the plan gave it is a
     * hearing of a different room. Averaging it in would put a real pair on the wrong two phones,
     * and every number about it would look exactly like a number about the right one.
     */
    @Test
    fun aHearingReadAgainstAnotherSlotIsNotCombined() {
        assertTrue(source.contains("if (slot < 0 || slot != message.ownSlot) continue"))
    }

    /**
     * The whole field is kept where the room screen can read it, not just the pairs this handset
     * is an end of.
     *
     * The per-peer files hold those, and they held them before a room could be measured. What had
     * nowhere to live is the distance between two other handsets - which is the one a swap between
     * two phones equally far from here shows up in, and nothing else can produce it.
     */
    @Test
    fun theWholeFieldIsKeptAndNotOnlyTheHostsOwnPairs() {
        assertTrue(source.contains("StoredRoomField(filesDir).write(field.separationMetres)"))
        assertTrue(source.contains("StoredSeparation(filesDir, peer).write(metres)"))
    }

    // -- the window widening with the room ----------------------------------------------------

    /**
     * The window grows with the room and the interval has to grow under it. Two repeats whose
     * search windows touch put one handset's chirp inside two searches at once, where the
     * correlation tells them apart by which is louder - a property of the room, not the schedule.
     */
    @Test
    fun widensTheIntervalForTheRoomItTurnedOutToBe() {
        // A small room costs nothing over the floor.
        assertEquals(DISTANCE_INTERVAL_NANOS, roomIntervalNanos(DISTANCE_INTERVAL_NANOS, 2, STAGGER_NANOS))
        // A big one pays for itself.
        assertEquals(
            10 * STAGGER_NANOS + ROOM_REPEAT_GAP_NANOS,
            roomIntervalNanos(DISTANCE_INTERVAL_NANOS, 11, STAGGER_NANOS)
        )
    }

    /**
     * And what it widens to is a plan the schedule accepts, all the way up.
     *
     * The ceiling lives in [CalibrationSchedule], which refuses a room that will not fit between
     * two repeats. This is the other end of the same arithmetic, and the two are written in
     * different files - so what pins them together is a test that runs one against the other.
     */
    @Test
    fun everyRoomSizeItWidensForIsOneTheScheduleWillRun() {
        val chirpNanos = 120_000_000L
        for (slots in 2..12) {
            val names = (0 until slots).map { "%016x".format(it) }
            val room = plan(names)
            for (slot in 0 until slots) CalibrationSchedule.of(room, slot, chirpNanos)
        }
    }

    // -- the field ----------------------------------------------------------------------------

    /**
     * Every pair in the room out of one window, named by the handsets rather than by the slots.
     *
     * Two sinks facing each other is the pair a host-centred round never reaches, and it is
     * answered here by the same arithmetic every other pair is.
     */
    @Test
    fun answersEveryPairIncludingTheOneBetweenTwoSinks() {
        val names = listOf(one, two, three)
        val flight = { a: Int, b: Int -> if (a == b) 0 else twoMetres }
        val heard = (0..2).associateWith { own -> listOf(hearing(own, 3, flight)) }

        val field = roomField(plan(names), heard, AlignmentAnalysis.DISTANCE_EDGE_SHARES)

        assertEquals(3, field.separationMetres.size)
        assertEquals(1, field.repeats)
        assertEquals(setOf(one to two, one to three, two to three), field.separationMetres.keys)
        for (entry in field.separationMetres) {
            assertNotNull("${entry.key} was not answered", entry.value)
            assertEquals(entry.key.toString(), 2.0, entry.value!!, 0.05)
        }
    }

    /**
     * A pair one end of which was not heard comes back present and null, not missing. A room that
     * quietly answered fewer pairs than it has looks like a room with fewer handsets in it.
     */
    @Test
    fun keepsAPairNobodyCouldReadRatherThanDroppingIt() {
        val names = listOf(one, two, three)
        val flight = { a: Int, b: Int -> if (a == b) 0 else twoMetres }
        // Nobody could hear slot 1, so both of its pairs go unanswered and the third stands.
        val heard = (0..2).associateWith { own -> listOf(hearing(own, 3, flight, quiet = setOf(1))) }

        val field = roomField(plan(names), heard, AlignmentAnalysis.DISTANCE_EDGE_SHARES)

        assertEquals(3, field.separationMetres.size)
        assertNull(field.separationMetres[one to two])
        assertNull(field.separationMetres[two to three])
        assertNotNull(field.separationMetres[one to three])
        assertEquals(1, pairsReadableFor(field, one))
        assertEquals(0, pairsReadableFor(field, two))
    }

    /** A pair is only as repeated as the less-heard of its two ends. */
    @Test
    fun foldsOnlyTheRepeatsEveryHandsetDelivered() {
        val names = listOf(one, two, three)
        val flight = { a: Int, b: Int -> if (a == b) 0 else twoMetres }
        val heard = mapOf(
            0 to List(3) { hearing(0, 3, flight) },
            1 to List(2) { hearing(1, 3, flight) },
            2 to List(3) { hearing(2, 3, flight) }
        )

        assertEquals(2, roomField(plan(names), heard, AlignmentAnalysis.DISTANCE_EDGE_SHARES).repeats)
    }

    /**
     * The report names who was scheduled and who actually delivered, because those are different
     * facts and a room that lost a handset has to say which one.
     */
    @Test
    fun theReportSaysWhoWasScheduledAndWhoWasHeard() {
        val names = listOf(one, two, three)
        val flight = { a: Int, b: Int -> if (a == b) 0 else twoMetres }
        val heard = (0..2).associateWith { own -> listOf(hearing(own, 3, flight)) }
        val room = plan(names)

        val json = roomReportJson(room, roomField(room, heard, AlignmentAnalysis.DISTANCE_EDGE_SHARES), listOf(one), 0L)

        assertTrue(json, json.contains("\"slotIds\":[\"$one\",\"$two\",\"$three\"]"))
        assertTrue(json, json.contains("\"heardFrom\":[\"$one\"]"))
        assertTrue(json, json.contains("\"a\":\"$two\",\"b\":\"$three\""))
        assertTrue(json, json.contains("\"repeats\":1"))
    }
}
