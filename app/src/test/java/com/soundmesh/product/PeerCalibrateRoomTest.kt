package com.soundmesh.product

import com.soundmesh.core.AlignmentAnalysis
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.CalibrationSchedule
import com.soundmesh.core.ChirpArrival
import com.soundmesh.core.ChirpGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import java.io.File

/**
 * The room arm: one window, every handset in it, and one pass answering every pair.
 *
 * It coexists with the pair flow rather than replacing it. A pair measures the constant a session
 * applies; a room measures where the handsets are - including the two sinks facing each other,
 * which a host-centred round of pairs never reaches.
 */
class PeerCalibrateRoomTest {
    /**
     * One run, read as source, across the two files it is now spread over.
     *
     * The sink half moved to SinkRound.kt so a handset with its screen off could join a room
     * round - Android will not let a background app start an activity, and that half never needed
     * a screen. Every rule below is about the run and not about either file, so the ruler is both
     * of them: a rule pointed at one file only would go quiet the next time a piece moved, and a
     * quiet rule and a satisfied one look exactly alike.
     */
    private val source =
        File("src/main/java/com/soundmesh/product/PeerCalibrateActivity.kt").readText(Charsets.UTF_8) +
            File("../core/src/main/java/com/soundmesh/product/SinkRound.kt").readText(Charsets.UTF_8) +
            File("../core/src/main/java/com/soundmesh/product/HostRound.kt").readText(Charsets.UTF_8)

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
    private fun hearing(
        own: Int,
        slots: Int,
        flightFrames: (Int, Int) -> Int,
        quiet: Set<Int> = emptySet(),
        /**
         * Frames by which the handset in that slot fired late. Everybody hears it that much
         * later, itself included: a handset hears its own speaker across centimetres, so a late
         * emission moves its own reading by exactly as much as it moves everybody elses.
         */
        lateBy: (Int) -> Int = { 0 }
    ) =
        (0 until slots).map { slot ->
            arrival(
                index = 48_000 + slot * slotFrames + flightFrames(own, slot) + lateBy(slot),
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
        val field = mapOf((three to one) to 2.0, (one to two) to 3.0)

        assertEquals(field, roomFieldToStore(field, three, overhead = false))
        assertTrue(source.contains("StoredSeparation(filesDir, peer).write(metres)"))
    }

    /**
     * A round taken with one handset above somebody's head still measures every pair that handset
     * is not an end of, and those are ordinary separations: two phones on a table are the same
     * distance apart whoever is holding a third. What it must not keep as a separation is the pairs
     * the held handset is in - those are how far the person was, and the handset is about to be put
     * back somewhere else entirely.
     */
    @Test
    fun anOverheadRoundKeepsOnlyThePairsItWasNotAnEndOf() {
        val field = mapOf((three to one) to 2.0, (two to three) to 1.5, (one to two) to 3.0)

        assertEquals(mapOf((one to two) to 3.0), roomFieldToStore(field, three, overhead = true))
    }

    /** The distances it was an end of go to the listener's files, not to nowhere. */
    @Test
    fun anOverheadRoundFilesItsOwnDistancesUnderTheListener() {
        assertTrue(source.contains("StoredListenerDistance(filesDir, peer).write(metres)"))
        assertTrue(source.contains("StoredListenerDistance(filesDir, sinkId).write(keep)"))
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

    // ---- the way in, which until 09-11 was a command line and nothing else ---------------------

    private val screen =
        File("src/main/java/com/soundmesh/product/PeerCalibrateScreen.kt").readText(Charsets.UTF_8)

    /**
     * Both buttons hand this screen a fresh intent rather than a fresh argument.
     *
     * Which arm a run is has exactly one answer in this class - the intent it was started with -
     * and five readers between naming the case and filing the answer. A button that set a field
     * instead would have made a second answer that can disagree with the first, which is the
     * fault that has already cost this project a session: a flag passed to the right name on the
     * wrong handset.
     */
    @Test
    fun theRoomButtonsStartARunRatherThanSettingAFlagBesideOne() {
        assertTrue(source.contains("measureRoom = { restartAsRoom(overhead = false) }"))
        assertTrue(source.contains("measureOverhead = { restartAsRoom(overhead = true) }"))
        assertTrue(source.contains("    .putExtra(\"room\", true)"))
        assertTrue(source.contains("    .putExtra(\"overhead\", overhead)"))
        // Without this the intent is delivered, onNewIntent replaces it, and nothing begins:
        // the screen sits on the last run's answer looking exactly like a run that started.
        assertTrue(source.contains("    .putExtra(\"auto\", true)"))
    }

    /**
     * Everybody in the room presses the plain room button, and only the host is offered the
     * overhead one - it is the only handset that is held and the only one that files anything.
     */
    @Test
    fun onlyTheHostIsOfferedTheRoundThatMeasuresTheListener() {
        assertTrue(screen.contains("onClick = actions.measureRoom"))
        val gate = screen.indexOf("CalibrationRole.HOST -> if (page == 2) {")
        val next = screen.indexOf("CalibrationRole.SINK ->")
        val offer = screen.indexOf("onClick = actions.measureOverhead")
        assertTrue("the host arm of the role branch is gone", gate >= 0)
        assertTrue("the overhead button is offered outside the host arm", offer in gate until next)
    }

    /**
     * The overhead round waits for the listener to sit down before it makes a sound.
     *
     * Every other round measures phones that are already where they will be, so two seconds of
     * lead is generous. This one measures a person, who has to press the last button, raise the
     * handset over their head, walk back and stop moving - and a listener still moving when the
     * first chirp goes is measured somewhere they are not going to be, which is a wrong answer
     * that looks exactly like a right one.
     */
    @Test
    fun aRoundStartedByTheHostPutsItselfAwayAfterwards() {
        // The whole of the fault it fixes is that it did not: a handset left sitting on a
        // finished result holds the clock port and is standing by for nobody, so the next thing
        // the host says reaches nothing and the next session cannot bind.
        assertTrue(source.contains("private fun putItselfAway()"))
        assertTrue(source.contains("if (!intent.getBooleanExtra(\"sent\", false)) return"))
        // And a round nobody is watching ends rather than running on holding those same ports.
        assertTrue(source.contains("if (isFinishing) {"))
        assertTrue(source.contains("            stopServing()"))
        // The lead is the shipped one again. It was fifteen seconds while the listener had to
        // walk round pressing a button on every handset; the host says go now, and whoever
        // pressed it is already sitting down holding the phone.
        assertFalse(source.contains("OVERHEAD_PLAN_LEAD_NANOS"))
        // And the command line still wins, so a sweep can move it.
        assertTrue(source.contains("planLeadNanos = millisExtra(\"plan_lead_millis\", shipped.planLeadNanos)"))
    }

    /**
     * A round that measured nothing does not replace a round that measured something.
     *
     * 09-13, from the files rather than from reasoning: four handsets were measured at 12:59 and
     * six distances stored. At 13:03 an overhead round ran with one handset present. An overhead
     * round keeps only the pairs the host is not an end of, so with one handset it had none - and
     * the empty result replaced all six. The drawing is fitted from those distances, so it then
     * had three edges for four handsets, which does not hold a shape, and a listener reported the
     * fit as wrong. Nothing in the app said any of this had happened.
     */
    @Test
    fun `a round that measured nothing does not replace the stored room`() {
        val nothing = mapOf((one to two) to null)

        assertFalse(saysSomethingAboutTheRoom(emptyMap()))
        assertFalse(saysSomethingAboutTheRoom(nothing))
    }

    /** And one real distance is an observation, so it does replace what was there. */
    @Test
    fun `a round that measured one distance is still an observation`() {
        assertTrue(saysSomethingAboutTheRoom(mapOf((one to two) to 2.0, (two to three) to null)))
    }

    /**
     * The room answers how far apart two handsets fire, not only how far apart they stand.
     *
     * The two are orthogonal halves of the same pair of readings - the half difference is the
     * flight time, the half sum is the firing offset - so a round that measured the room has
     * already measured this. It is filed because the alternative costs a minute per handset with
     * somebody walking to each one, and on 2026-09-13 two handsets that had never had it done
     * played a whole afternoon tens of milliseconds out.
     */
    @Test
    fun `the room answers how far apart two handsets fire, not only how far apart they stand`() {
        val names = listOf(one, two, three)
        val flight = { a: Int, b: Int -> if (a == b) 0 else twoMetres }
        val fiveMillis = (5.0 / 1000 * ChirpGenerator.SAMPLE_RATE).toInt()
        val heard = (0..2).associateWith { own ->
            listOf(hearing(own, 3, flight, lateBy = { slot -> if (slot == 0) fiveMillis else 0 }))
        }

        val field = roomField(plan(names), heard, AlignmentAnalysis.DISTANCE_EDGE_SHARES)

        // Which way the sign runs is settled by facingPairs and checked against real handsets.
        // What is asserted here is the size, that it lands on the pairs that were late, and
        // that it lands on no other.
        assertEquals(5.0, abs(field.alignmentErrorMs[one to three]!!), 0.2)
        assertEquals(5.0, abs(field.alignmentErrorMs[one to two]!!), 0.2)
        assertEquals(0.0, field.alignmentErrorMs[two to three]!!, 0.2)
        // And again off the leading edge, which is the reading that is meant to be trusted:
        // these fakes put every share on one lag, so the two rules coincide here and what is
        // being checked is that the edge number is plumbed through rather than left null.
        assertEquals(5.0, abs(field.edgeFiringOffsetMs[one to three]!!), 0.2)
        assertEquals(5.0, abs(field.edgeFiringOffsetMs[one to two]!!), 0.2)
        assertEquals(0.0, field.edgeFiringOffsetMs[two to three]!!, 0.2)
        // And the distances are untouched: one half of the pair of readings cannot move
        // without the other staying where it was.
        assertEquals(2.0, field.separationMetres[one to three]!!, 0.05)
        assertEquals(2.0, field.separationMetres[one to two]!!, 0.05)
    }

    // -- what may be handed to a handset that was never measured ------------------------------

    /**
     * The offer is the same reading, signed the way the handset that applies it needs it.
     *
     * Which way that is was settled on hardware rather than here: on 2026-09-13 a handset already
     * correcting by +35.352 ms read -1.229 ms of residual off a room round. Had the room spoken
     * the opposite convention it would have read about -70. So a room pair keyed (sink, host)
     * carries the same quantity a stored constant does, and [offeredTo] passes it through.
     */
    @Test
    fun `what a room offers a handset is signed the way that handset stores it`() {
        val names = listOf(one, two, three)
        val flight = { a: Int, b: Int -> if (a == b) 0 else twoMetres }
        val fiveMillis = (5.0 / 1000 * ChirpGenerator.SAMPLE_RATE).toInt()
        val heard = (0..2).associateWith { own ->
            listOf(hearing(own, 3, flight, lateBy = { slot -> if (slot == 0) fiveMillis else 0 }))
        }

        val field = roomField(plan(names), heard, AlignmentAnalysis.DISTANCE_EDGE_SHARES)

        // The handset in slot 0 fired 5 ms after everybody else, and the host is the last slot.
        assertEquals(-5000.0, offeredTo(field, three, one)!!.toDouble(), 200.0)
        // The pair of handsets that fired together has nothing to correct.
        assertEquals(0.0, offeredTo(field, three, two)!!.toDouble(), 200.0)
        // And the host is not a handset this can be offered to: it is the thing being matched.
        assertNull(offeredTo(field, three, three))
    }

    /**
     * A pair whose repeats disagree is diagnosed but not handed out.
     *
     * The bound is [com.soundmesh.core.AlignmentVerdict.MAX_SINGLE_MS] - the one the pair flow
     * already holds a single reading of this same quantity to - rather than a new number. It is
     * borrowed in the refusing direction only, which is the direction that cannot invent an
     * offer. What must not happen is the refusal quietly costing the diagnostic: the reading is
     * still there to be read, it is just not acted on.
     */
    @Test
    fun `a pair whose repeats disagree is read but not offered`() {
        val names = listOf(one, two, three)
        val flight = { a: Int, b: Int -> if (a == b) 0 else twoMetres }
        val fiveMillis = (5.0 / 1000 * ChirpGenerator.SAMPLE_RATE).toInt()
        val heard = (0..2).associateWith { own ->
            listOf(
                hearing(own, 3, flight, lateBy = { slot -> if (slot == 0) fiveMillis else 0 }),
                hearing(own, 3, flight)
            )
        }

        val field = roomField(plan(names), heard, AlignmentAnalysis.DISTANCE_EDGE_SHARES)

        assertNotNull("the reading itself was lost", field.edgeFiringOffsetMs[one to three])
        assertNull("a pair that moved 5 ms between repeats was handed out", offeredTo(field, three, one))
        // The pair that held still across both repeats is untouched by its neighbour being refused.
        assertNotNull(offeredTo(field, three, two))
    }

    /**
     * Nothing is offered off a pair nobody could read, and the size is bounded by the same thing
     * that bounds a measured constant: past [com.soundmesh.core.CalibrationUpdate.MAX_OFFSET_MICROS]
     * the correction moves a chirp out of the window the next run would have to measure it in, so
     * a handset carrying one could never be measured again.
     */
    @Test
    fun `an unreadable pair offers nothing and an impossible one is refused`() {
        assertNull(offerableFiringOffsetMicros(emptyList()))
        assertNull(offerableFiringOffsetMicros(listOf(null, null)))
        assertNull("a pair with no edge reading was offered", offerableFiringOffsetMicros(listOf(facing(null))))
        assertEquals(-5_000L, offerableFiringOffsetMicros(listOf(facing(-5.0), facing(-5.0))))
        assertNull(offerableFiringOffsetMicros(listOf(facing(2_000.0), facing(2_000.0))))
    }

    /** The sign convention lives in one place, so [offeredTo] reads a reversed key reversed. */
    @Test
    fun `a pair keyed the other way round is offered the other way round`() {
        val field = RoomField(
            separationMetres = emptyMap(),
            alignmentErrorMs = emptyMap(),
            edgeFiringOffsetMs = emptyMap(),
            offerableOffsetMicros = mapOf((three to one) to -5_000L),
            repeats = 1
        )

        assertEquals(5_000L, offeredTo(field, three, one))
    }

    /**
     * Only the handset that can stop something is offered a way to.
     *
     * The button used to be offered whenever a host was running and it said one thing: the handset
     * being measured will finish, and no more will be waited for. That is a queue's sentence, and
     * neither job is a queue any more - so what the button means now turns on one thing only,
     * whether anything has started making a sound, which is not known when a run begins.
     * Reported from the field on 2026-09-13 as a button that was not greyed out and did nothing.
     */
    @Test
    fun offersToStopOnlyWhatCanBeStopped() {
        assertEquals(StopOffer.BEFORE_SOUND, stopOfferFor(CalibrationRole.HOST))
        // A sink is told what to do and does it. There is nothing here for it to call off.
        assertEquals(StopOffer.NONE, stopOfferFor(CalibrationRole.SINK))
        assertEquals(StopOffer.NONE, stopOfferFor(null))
    }

    /**
     * The wait before a room is told, which exists because of what one costs when it is skipped.
     *
     * Handsets told to leave their home screens are on their way back to them for a couple of
     * hundred milliseconds afterwards, and a room told during that flight reaches nobody. On
     * 2026-09-13 somebody called a round off and pressed again straight away, and the host
     * reported that it had told nobody. What it must not do is cost its budget when the room is
     * already there, which is the ordinary case and the one nobody would forgive a pause in.
     */
    @Test
    fun waitsForTheRoomToComeBackAndNotAMomentLongerThanThat() {
        var now = 0L
        val rested = mutableListOf<Long>()
        val rest = { millis: Long -> rested += millis; now += millis * 1_000_000L }

        // Already standing by: answered on the first look, nothing slept.
        assertTrue(awaitBriefly(2_000, { now }, rest) { true })
        assertTrue("waited for a room that was already there", rested.isEmpty())

        // Back on the third look, and the wait ends there rather than at its budget.
        var looks = 0
        assertTrue(awaitBriefly(2_000, { now }, rest) { looks++ >= 2 })
        assertEquals(2, rested.size)

        // Never comes back: bounded, and says so rather than answering true.
        rested.clear()
        assertFalse(awaitBriefly(300, { now }, rest) { false })
        assertTrue("waited past its budget", rested.size <= 300 / 50 + 1)
    }

    /**
     * A round that went badly can be run again, and the offer sits beside its own answer.
     *
     * Somebody still walking to their chair, a door open, a phone in the wrong hand: what that
     * leaves is an answer wrong in a way nothing can see, so the moment anybody knows to run it
     * again is the moment they read the result. The walk-through under it only goes forwards - by
     * the time the first step's answer is on screen it is already asking for the second - so the
     * result box is the only place the way back can live.
     *
     * Outlined rather than filled, because the screen is not asking for it, and named by number
     * rather than "this step", because the step highlighted below is usually the other one.
     */
    @Test
    fun aFinishedRoundOffersToBeRunAgainBesideItsOwnResult() {
        assertTrue(
            "a step that is not the one to do carries controls, so two starts are on screen",
            screen.contains("            if (now) {")
        )
        assertTrue(
            "the result box does not offer the round that produced it again",
            screen.contains("state.redo?.let { which ->")
        )
        assertTrue(
            "the button does not say which of the two steps pressing it runs",
            screen.contains("stringResource(R.string.room_calibrate_again, which)")
        )
        assertTrue(
            "the button runs a round other than the one whose answer it sits under",
            screen.contains(
                "onClick = if (which == 1) actions.measureOverhead else actions.measureRoom"
            )
        )
    }

    /**
     * Which step a finished round hands over to, and which step its result then names.
     *
     * The first measures where a person sat, from phones the second does not move; the second
     * measures where the phones are. So redoing the first leaves the second exactly as true as it
     * was, and only the first hands over.
     *
     * The other direction is the one that does expire, and it cannot be detected here: phones that
     * moved make the first step's answer describe a room that is gone. That is a sentence under
     * the button rather than a rule, because nothing in the files can tell a room that moved from
     * a round somebody simply ran twice.
     */
    @Test
    fun onlyTheOverheadRoundHandsOverAndEveryRoundNamesItself() {
        assertTrue(
            "the round of the phones sends somebody back up to the overhead step",
            source.contains("val which = if (overhead()) 1 else 2") &&
                source.contains("if (which == 1) roomStep = 2")
        )
        assertTrue(
            "the result box is never told which of the two rounds it is showing",
            source.contains("roomRedo = which")
        )
        // A round that is called off or throws leaves a message rather than an answer, and an
        // offer to run "that step" again under it would name a step this screen did not measure.
        assertTrue(
            "a round that ends badly keeps the last round's offer under its own message",
            source.contains("roomRedo = null")
        )
        assertTrue(
            "the sentence saying the overhead answer expires when the phones move is gone",
            screen.contains(
                "if (which == 2) Note(stringResource(R.string.room_calibrate_step2_again_note))"
            )
        )
    }

    /**
     * The volume gate belongs to the round, not to the screen it was written on.
     *
     * A pair is two handsets each listening for the other, so a phone nobody can hear ends the
     * round exactly as it ends a room of four. The gate was on the room screen only because that
     * is the screen somebody asked for it on.
     */
    @Test
    fun thePairRoundIsGatedOnVolumeTheSameWayTheRoomRoundIs() {
        val pair = screen.substringAfter("private fun PairBody(").substringBefore("\n@Composable")
        assertTrue(
            "the pair screen has no way to set the room's volume before a round",
            pair.contains("if (state.role == CalibrationRole.HOST) VolumeGate(state, tooQuiet, actions)")
        )
        assertTrue(
            "a pair round starts with a handset nothing can hear",
            pair.contains("enabled = roundCanStart(state.running, tooQuiet, state.alone)") &&
                pair.contains("TooQuietNote(tooQuiet)")
        )
    }

    /**
     * The sweep marks a wait with nothing to see and nothing to hear, and only that.
     *
     * A frozen screen and a hung app are the same picture, and somebody who reads it as the second
     * one picks the phone up - which is the one thing a round cannot survive. What tells the two
     * phases apart is the countdown: it is handed over by the thing that fires the chirps, so a
     * line with no count beside it is a line with nothing audible behind it.
     */
    @Test
    fun onlyTheSilentHalfOfARoundShimmers() {
        assertTrue(
            "the waiting line sits still while a round dials the other handset",
            screen.contains("Note(it, waiting = state.until == null)")
        )
        assertTrue(
            "the count sweeps too, so the sweep stops meaning anything",
            screen.contains("if (left > 0) Text(stringResource(text, left), style = style)")
        )
        assertTrue(
            "the seconds of arithmetic after the chirps stop look like a hang",
            screen.contains("modifier = Modifier.sweeping(LocalContentColor.current)")
        )
    }

    private fun facing(edgeMs: Double?) = com.soundmesh.core.FacingPair(
        alignmentErrorMs = 0.0,
        separationMetres = 0.0,
        flightTimeMs = 0.0,
        rawHostMs = 0.0,
        rawSinkMs = 0.0,
        firingOffsetMs = edgeMs
    )
}
