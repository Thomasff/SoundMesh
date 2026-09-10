package com.soundmesh.product

import com.soundmesh.core.AlignmentConfidence
import com.soundmesh.core.AlignmentPairing
import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.AlignmentResultMessage
import com.soundmesh.core.CalibrationWindow
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
import com.soundmesh.core.FacingPair
import com.soundmesh.core.LinkQuality
import com.soundmesh.core.PairedAlignment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four properties of the pair calibration screen, read out of the source the way
 * [CalibrateActivityTest] reads its own. Each is something the framework or a quiet room would
 * otherwise have had to teach, and each carries the reason it is here.
 */
class PeerCalibrateActivityTest {
    private val SINK_ID = "a1b2c3d4e5f60718"
    private val PLAN_LEAD = 7_000_000_000L
    private val CLOCK_FILL = 16_000_000_000L

    private val source =
        File("src/main/java/com/soundmesh/product/PeerCalibrateActivity.kt").readText(Charsets.UTF_8)

    /**
     * The screen is singleTask, so a second start is delivered to onNewIntent and never reaches
     * onCreate. Without the override the screen sits on the previous run's answer and measures
     * nothing, which is how a Magic6 reading was lost once already.
     */
    @Test
    fun aSecondStartRunsTheCalibrationAgainRatherThanShowingTheLastAnswer() {
        assertTrue(source.contains("override fun onNewIntent("))
    }

    /**
     * An uncaught throw on any thread takes the whole process with it, which on hardware looks
     * like the app vanishing rather than like a calibration failing.
     */
    @Test
    fun aCalibrationThatFailsLeavesTheAppStandingToSaySo() {
        assertTrue(source.contains("runCatching"))
        assertTrue(source.contains("Thread("))
    }

    /**
     * A verification measures the residual left over after the stored constant is applied. Writing
     * a residual where the constant lives would quietly halve the correction on every run after
     * it, and nothing downstream could tell.
     */
    @Test
    fun aVerificationNeverStoresWhatItMeasured() {
        assertTrue(source.contains("if (verifying) return"))
    }

    /**
     * The host id in the plan is the file name the constant is stored under. A correction filed
     * against the wrong peer is applied silently on every later session with nothing to notice it
     * by, so a plan naming a host this handset never scanned has to end the run.
     */
    @Test
    fun aPlanFromSomebodyElseEndsTheRunRatherThanBeingStored() {
        assertTrue(source.contains("plan.hostId != "))
        assertFalse(
            "the constant is written under something other than the peer that was measured",
            source.contains("StoredCalibration(filesDir, StoredCalibration.ANONYMOUS_PEER)")
        )
    }

    /**
     * The role is handed in, never worked out from the pairing file. Both handsets in this room
     * hold a scanned pairing - they have each scanned the other at some point - so "has a scanned
     * pairing" makes both of them the sink and no run can start at all. Found by reading the two
     * phones before the first run rather than by watching one fail.
     */
    /**
     * Having a clock estimate is not the same as having a settled one. MIN_SAMPLES is only the
     * point the estimator will answer at - eight of a sixty-four wide window - and the offset it
     * answers with keeps moving as the window fills. C1, the first run on hardware, scheduled its
     * five chirps against five different offsets spanning 8.1 ms, and its five alignment errors
     * moved with them one for one; the constant it stored was 12.3 ms off the harness's.
     *
     * The wait is derived from the window rather than chosen, so it cannot drift away from the
     * estimator it is waiting on.
     */
    @Test
    fun theChirpsWaitForTheClockWindowToFillRatherThanForItsFirstAnswer() {
        assertTrue(
            "the run no longer waits a whole estimator window before scheduling anything",
            source.contains("ClockOffsetEstimator.DEFAULT_WINDOW * CLOCK_INTERVAL_MILLIS")
        )
        assertTrue(source.contains("CLOCK_FILL_NANOS"))
    }

    /**
     * The constant is only as good as the offset the chirps were scheduled against, so a run that
     * does not record its clock cannot be told apart afterwards from one whose room was noisy.
     * That is what C1 cost to find out by hand.
     */
    @Test
    fun aRunRecordsTheClockItWasScheduledAgainst() {
        val clock = clockReportJson(
            intervalMillis = 250L,
            windowSize = 64,
            bestCount = 8,
            keepFractionWhileFilling = true,
            clockFillNanos = CLOCK_FILL,
            radioHeld = true,
            link = null,
            atStart = ClockEstimate(offsetNanos = -5L, uncertaintyNanos = 7L, driftPpm = 1.5, sampleCount = 8),
            atEnd = null,
            exchanges = emptyList()
        )

        assertTrue(clock.contains("\"offsetNanos\":-5"))
        assertTrue(clock.contains("\"uncertaintyNanos\":7"))
        // A run whose clock never settled has to say so rather than leave the field out, or the
        // difference between "did not settle" and "an older report" is gone from the record.
        assertTrue(clock.contains("\"atEnd\":null"))
        // And the block has to arrive under the name the analysis reads it by.
        assertTrue(withClockReport("{\"role\":\"SINK\"}", clock).contains("\"clock\":{"))
    }

    /**
     * A case id is a directory the run store creates and never clears, so a measurement filed
     * under an archived run's id overwrites whatever that run left behind. C1 did exactly that on
     * the first hardware run: both handsets lost the calibration.wav an earlier alignment run had
     * put in runs/C1. The archive numbers every letter in the ones and tens, so ninety and up is
     * the first range nothing can be standing in.
     */
    @Test
    fun theCasesSitPastEverySeriesTheHarnessHasArchived() {
        val cases = Regex("const val CASE_(?:MEASURE|VERIFY|SLOW_LINK|DISTANCE) = \"([A-Z])([0-9]+)\"")
            .findAll(source).map { it.groupValues[2].toInt() }.toList()
        assertEquals(4, cases.size)
        assertTrue("a case id could be an archived run's directory", cases.all { it >= 90 })
    }

    /**
     * Only the sink knows whether a run is a measurement or a check, so the host has to be told
     * which case to file under rather than assuming the measurement. Assuming it put the verify
     * run's host half on top of the measure run's: same directory, no warning, and afterwards the
     * two rounds could not be compared. What arrives is a name from the network, so the host takes
     * it only if it is one of the two it runs.
     */
    @Test
    fun theHostFilesUnderTheCaseTheSinkAskedForRatherThanAlwaysTheMeasurement() {
        assertFalse(
            "the host plans the measurement case whatever the sink asked for",
            source.contains("caseId = CASE_MEASURE")
        )
        assertTrue(source.contains("request.caseId !in setOf(CASE_MEASURE, CASE_VERIFY, CASE_SLOW_LINK, CASE_DISTANCE)"))
    }
    /**
     * A case id names a directory the run store only ever mkdirs, so two runs of one case land on
     * one file - which is the failure the case id itself was added to stop, one dimension over.
     * With two sinks the case is the same and the peer is not, so the peer has to be in the name:
     * a host that measured the X10 and then the tablet wrote the tablet's half over the X10's, in
     * place, leaving a directory whose own mtime did not move to say it had happened.
     */
    @Test
    fun aSecondSinkDoesNotLandOnTheFirstOnesFile() {
        assertTrue(
            "the host's own half is written under a name that does not say which peer it ran with",
            source.contains("prepareRun(plan.caseId), hostArtifact(sinkId)")
        )
        assertTrue(source.contains("hostArtifact(sinkId: String): String = \"peer-calibration-\$sinkId.json\""))
        assertTrue(source.contains("fileAttempt(\"HOST-\${plan.caseId}-\$sinkId\", run.json)"))
        assertTrue(source.contains("\"HOST-\${plan.caseId}-\$sinkId-PAIRED\""))
    }

    /**
     * The name reaches a file name on this side, and it arrived over a socket. Checked for shape
     * rather than trusted from where it came, on the same terms as the case beside it - and it has
     * to be checked before it is kept, not after.
     */
    @Test
    fun aPeerNameThatCouldNameAPathIsRefusedBeforeItIsKept() {
        val gate = source.indexOf("HostId.isValid(request.sinkId)")
        val kept = source.indexOf("servedSink = request.sinkId")

        assertTrue("the sink's name is never checked for shape", gate >= 0)
        assertTrue("the name is kept before it is checked", gate < kept)
    }

    /**
     * The host hands out one plan and then waits on one socket that any handset in the room still
     * holding a plan can reach. Combining a stranger's readings does not make a worse number, it
     * makes a number about a pair that never ran - and the fold on the other side would take it.
     */
    @Test
    fun aResultFromAHandsetThisRoundDidNotServeIsNotCombined() {
        val check = source.indexOf("message.sinkId != sinkId")
        val combine = source.indexOf("AlignmentPairing.combine(")

        assertTrue("the delivery is never checked against the handset that was served", check >= 0)
        assertTrue("the readings are combined before it is known whose they are", check < combine)
        assertTrue(
            "a stranger's delivery is answered with a correction",
            source.contains("return@awaitResult CalibrationReply(null, null, null)")
        )
    }

    /**
     * Two handsets measured on one host is two answers, and the pair of them is the whole reason
     * somebody pressed the button twice. A screen that only ever holds the last one throws away
     * the comparison at the moment it becomes possible.
     */
    @Test
    fun eachSinksAnswerIsKeptBesideTheOthersRatherThanReplacingThem() {
        assertTrue(source.contains("record(sinkId, outcome)"))
        assertTrue(
            "rows are told apart by the whole name, because the short one can collide",
            source.contains("state.outcomes.filterNot { it.sinkId == sinkId }")
        )
    }


    /**
     * CalibrationUpdate.usable lets a failed run through on purpose, and its own comment gives the
     * reason: a pair that has never been calibrated sits tens of milliseconds out and fails every
     * threshold, and it is precisely the run the loop has to adopt or no first correction can ever
     * be made.
     *
     * That reason is spent the moment a constant exists. C1, the first hardware run, was FAIL and
     * readable, and folding it moved a good constant from 34511 to 36962 - silently, with nothing
     * in any later result to notice it by. The condition the comment already states is applied
     * here rather than widened in core, where the harness's own 186 runs were taken under the
     * present rule.
     */
    @Test
    fun aFirstCalibrationIsAdoptedHoweverFarOutItLands() {
        assertTrue(foldsIntoStoredCalibration(observations = 0, measuredMicros = 46_766, estimateMicros = 0))
    }

    /**
     * The run C1 was: 12.3 ms away from a constant of 34511, folded, and left the pair at 36962.
     * A single run cannot move the pair's offset by more than the whole alignment budget, so a run
     * that says it did is not an observation of the same constant.
     */
    @Test
    fun aRunFurtherOutThanTheWholeAlignmentBudgetIsNotAnObservationOfThisPair() {
        assertFalse(foldsIntoStoredCalibration(observations = 4, measuredMicros = 46_766, estimateMicros = 34_511))
    }

    /**
     * The point of the whole rule, and what the eighteen runs of 2026-09-08 measured.
     *
     * The run-level bias is one wide distribution - mean 1.444 ms, sd 0.488 - against a verdict
     * gate of 1.0, so admitting only runs the verdict passed admits 18.2% of them and those have a
     * mean of 0.734. The constant then converges on 0.734 instead of 1.444 and stays 0.710 ms
     * short of the truth however many runs are taken, because the admission was reading the very
     * quantity being estimated. A window around the standing constant cuts both sides equally.
     */
    @Test
    fun aRunTheVerdictFailedStillFoldsWhenItIsNearTheConstantAlreadyHeld() {
        assertTrue(
            "a 1.3 ms residual is what this pair measures, not a broken run",
            foldsIntoStoredCalibration(observations = 5, measuredMicros = 35_845, estimateMicros = 34_511)
        )
    }

    @Test
    fun theWindowCutsBothSidesEqually() {
        assertTrue(foldsIntoStoredCalibration(observations = 5, measuredMicros = 4_999, estimateMicros = 0))
        assertTrue(foldsIntoStoredCalibration(observations = 5, measuredMicros = -4_999, estimateMicros = 0))
        assertFalse(foldsIntoStoredCalibration(observations = 5, measuredMicros = 5_001, estimateMicros = 0))
        assertFalse(foldsIntoStoredCalibration(observations = 5, measuredMicros = -5_001, estimateMicros = 0))
    }

    /**
     * A run the link gate turns away leaves the exchanges it was turned away for.
     *
     * The refusal returns before the report is written, so until now every run on a link too slow
     * to align on wrote nothing at all - the screen's one sentence was the whole record. What that
     * cost is exact: of 66 archived runs carrying exchanges, the largest round trip median is
     * 23.8 ms and the gate sits at 40, so the predictor has no variation at all in the range the
     * threshold lives in. The gate was discarding the only data that could test the gate.
     */
    @Test
    fun aRunTheLinkGateTurnedAwayLeavesTheExchangesItWasTurnedAwayFor() {
        val json = refusedRunJson(
            caseId = "C90",
            refusal = "SLOW_LINK",
            clock = clockReportJson(
                250L, 64, 8, true, CLOCK_FILL, true,
                LinkQuality(medianRoundTripNanos = 90_000_000L, p90RoundTripNanos = 152_000_000L, samples = 57),
                null, null, listOf(ClockExchange(1, 2, 3, 4))
            )
        )

        assertTrue("nothing says why this run stopped: $json", json.contains("\"refusal\":\"SLOW_LINK\""))
        assertTrue("the link that refused it is not on the record", json.contains("\"medianRoundTripNanos\":90000000"))
        assertTrue("the exchanges are gone, which is the whole point", json.contains("\"exchanges\":[[1,2,3,4]]"))
        // Read by the same script a finished run is: empty pairs and a named refusal is what the
        // runner already writes for a run that played and could not be correlated.
        assertTrue(json.contains("\"pairs\":[]"))
        assertTrue(json.contains("\"caseId\":\"C90\""))
    }

    /**
     * The refusal is filed under the case the run would have been, and the run is stopped before
     * its exchanges are read - recordedExchanges is documented to be read once runFor has returned.
     */
    @Test
    fun theRefusedRunIsFiledRatherThanOnlyShownOnTheScreen() {
        assertTrue("a refused run still writes nothing", source.contains("SINK-REFUSED"))
        val gate = source.substringAfter("link?.takeIf { !it.usable && !allowSlowLink }?.let {")
        assertTrue(
            "the exchanges are read while the clock thread is still appending to them",
            gate.indexOf("clockThread.join(") < gate.indexOf("clockClient.recordedExchanges()")
        )
    }

    /**
     * A case id names a directory the run store only ever mkdirs, so a second run of the same
     * case overwrites the first where it stands. In place, which is why nothing outside says so:
     * the directory's mtime does not move either, and a listing afterwards still reads the day of
     * the run that was lost. Four runs on 2026-09-08 went into one C91 and one survived.
     */
    @Test
    fun everyAttemptIsAlsoFiledUnderANameNoLaterRunCanClaim() {
        // Four call sites past the declaration: the host's run, the sink's run, the run the link
        // gate turned away, and the combined report the host writes once both halves are in.
        assertEquals(4, source.split("fileAttempt(").size - 2)
        assertTrue(
            "filing a second copy of the evidence is what ends a finished run",
            source.contains("runCatching { PeerRunLog(")
        )
    }

    /**
     * The combined run, and each handset's own emission beside it.
     *
     * Both are new because neither existed. The combination was never written anywhere - it
     * produced one sentence on a screen and was gone, so every offline analysis of a peer run has
     * had to re-combine the two files by hand. And a run's combined error is
     * `C + host emission - sink emission`, so its scatter alone never says which side moved: the
     * 2026-09-08 18:29 run below scattered by 0.7 ms with the X10 stepping twice past a
     * millisecond and the Magic6 holding to a tenth of one.
     */
    @Test
    fun theCombinedRunSaysWhichHandsetsEmissionMoved() {
        // 2026-09-08 18:29, X10 hosting, read out of the two archived reports.
        val host = readingsAt(
            first = listOf(263_432, 503_429, 743_435, 983_436, 1_223_432),
            second = listOf(287_434, 527_435, 767_379, 1_007_436, 1_247_369)
        )
        val sink = readingsAt(
            first = listOf(264_449, 504_440, 744_446, 984_448, 1_224_444),
            second = listOf(288_480, 528_481, 768_425, 1_008_477, 1_248_410)
        )
        val combined = AlignmentPairing.combine("C91", host, AlignmentResultMessage("C91", SINK_ID, 0L, sink))

        val json = pairedReportJson("C91", "0123456789abcdef", SINK_ID, combined, host, sink, 5 * 48_000, PLAN_LEAD)

        // The X10 is the host here and it is the one that stepped.
        assertTrue("the host's emission spread is missing: $json", json.contains("\"hostSpreadMs\":0.700"))
        assertTrue("the sink's emission spread is missing: $json", json.contains("\"sinkSpreadMs\":0.074"))
        // Read twice, once from each recording. That agreement is the check.
        assertTrue(json.contains("\"hostSeenBySinkMs\":["))
        assertTrue(json.contains("\"sinkSeenByHostMs\":["))
        // And the combination itself, which nothing wrote down before.
        assertTrue(json.contains("\"combinedMs\":["))
        assertTrue(json.contains("\"clusterMeanMs\":"))
        assertTrue(json.contains("\"maxAbsMs\":"))
    }

    /**
     * The report carries what was measured, and the verdict is computed exactly as before. A
     * correction that removed a handset's emission jitter would cut a run's scatter from 0.704 to
     * 0.292 ms and the eighteen-run spread of cluster means only 0.488 to 0.460 - and would move
     * the constant 0.17 ms, which those steps are real sound and have no business doing.
     */
    @Test
    fun theEmissionIsReportedAndNeverJudged() {
        val host = readingsAt(
            first = listOf(263_432, 503_429, 743_435, 983_436, 1_223_432),
            second = listOf(287_434, 527_435, 767_379, 1_007_436, 1_247_369)
        )
        val sink = readingsAt(
            first = listOf(264_449, 504_440, 744_446, 984_448, 1_224_444),
            second = listOf(288_480, 528_481, 768_425, 1_008_477, 1_248_410)
        )
        val combined = AlignmentPairing.combine("C91", host, AlignmentResultMessage("C91", SINK_ID, 0L, sink))
        val json = pairedReportJson("C91", "0123456789abcdef", SINK_ID, combined, host, sink, 5 * 48_000, PLAN_LEAD)

        assertEquals(
            "the verdict in the report is not the verdict the run was judged by",
            combined.verdict?.clusterMeanMs?.let { String.format(java.util.Locale.US, "%.6f", it) },
            Regex("\"clusterMeanMs\":([-0-9.]+)").find(json)?.groupValues?.get(1)
        )
    }

    /**
     * Every reading trustworthy and no separation assumed: combineFacing removes the flight time
     * by construction, which is why the runs pass 0.0 here.
     */
    private fun readingsAt(first: List<Int>, second: List<Int>): List<AlignmentReading> =
        first.indices.map { repeat ->
            val stagger = second[repeat] - first[repeat]
            AlignmentReading(
                firstIndex = first[repeat],
                secondIndex = second[repeat],
                measuredStaggerFrames = stagger,
                alignmentErrorMs = (stagger - 24_000).toDouble() / 48_000 * 1000,
                propagationCorrectionMs = 0.0,
                separationMetres = 0.0,
                confidence = AlignmentConfidence.OK,
                ratios = listOf(9.0, 9.0),
                atSearchEdge = listOf(false, false)
            )
        }

    @Test
    fun theRoleIsToldToTheScreenRatherThanGuessedFromThePairingFile() {
        assertTrue(source.contains("intent.getStringExtra(\"role\")"))
        assertFalse(
            "the role is derived from the pairing file, which both handsets of a pair can hold",
            source.contains("if (PairedHost(filesDir).read() != null) CalibrationRole.SINK")
        )
    }

    /**
     * A run carries every exchange its offset was fitted from.
     *
     * They are the expensive half to collect and the cheap half to store - tens of kilobytes
     * against a 2.7 MB recording - and they are the only thing that lets a candidate estimator be
     * scored offline on what a real run actually saw, rather than on a second run of the phones.
     * [ClockSyncClient] has kept them all along and says in as many words that this is why; the
     * harness has written them out since it existed. This path did not, so the two runs of
     * 2026-09-08 that caught the offset walking 3.7 ms on a router threw away the only data that
     * could tell a minimum filter from a mean of eight.
     */
    @Test
    fun aRunCarriesEveryExchangeItsOffsetWasFittedFrom() {
        val json = clockReportJson(
            intervalMillis = 250L,
            windowSize = 64,
            bestCount = 8,
            keepFractionWhileFilling = true,
            clockFillNanos = CLOCK_FILL,
            radioHeld = true,
            link = null,
            atStart = null,
            atEnd = null,
            exchanges = listOf(ClockExchange(1, 2, 3, 4), ClockExchange(10, 20, 30, 40))
        )

        assertTrue(
            "the run cannot be replayed through another estimator: $json",
            json.contains("\"exchanges\":[[1,2,3,4],[10,20,30,40]]")
        )
    }

    /**
     * The estimator's own shape travels with the exchanges. Replaying them through a candidate is
     * only a comparison if what the run actually used is on the record beside them.
     */
    @Test
    fun theReportSaysWhichEstimatorShapeTheRunUsed() {
        val json = clockReportJson(250L, 64, 8, true, CLOCK_FILL, true, null, null, null, emptyList())

        assertTrue(json.contains("\"windowSize\":64"))
        assertTrue(json.contains("\"bestCount\":8"))
        // A refused run records no exchanges and still has to parse.
        assertTrue(json.contains("\"exchanges\":[]"))
    }

    /**
     * Whether the radio was actually held out of power save is on the record beside the numbers.
     *
     * The lock is best effort - it needs WAKE_LOCK and a vendor build that will hand one over - so
     * a run whose lock quietly did nothing reads exactly like a run proving power save does not
     * matter. The two have to be told apart afterwards, by somebody who was not in the room.
     */
    @Test
    fun theReportSaysWhetherTheRadioWasActuallyHeld() {
        assertTrue(
            clockReportJson(250L, 64, 8, true, CLOCK_FILL, true, null, null, null, emptyList()).contains("\"radioHeld\":true")
        )
        assertTrue(
            clockReportJson(250L, 64, 8, true, CLOCK_FILL, false, null, null, null, emptyList()).contains("\"radioHeld\":false")
        )
    }

    /**
     * The link the run was spent on travels with the numbers it produced. A residual of 2.5 ms and
     * a residual of 0.2 ms look like the same kind of fact until you can see that one was measured
     * across a link whose median round trip was seven times the other's.
     */
    @Test
    fun theReportSaysWhatLinkTheRunWasSpentOn() {
        val json = clockReportJson(
            250L, 64, 8, true, CLOCK_FILL, true,
            LinkQuality(medianRoundTripNanos = 56_300_000L, p90RoundTripNanos = 137_900_000L, samples = 175),
            null, null, emptyList()
        )

        assertTrue(json.contains("\"medianRoundTripNanos\":56300000"))
        assertTrue(json.contains("\"p90RoundTripNanos\":137900000"))
        assertTrue(json.contains("\"samples\":175"))
    }
    /**
     * Three arms, three directories. A case id is a place on disk that RunStore never clears, so
     * two arms sharing one is two arms overwriting each other - which has happened here once, and
     * cost the pair's calibration.wav on both handsets.
     */
    @Test
    fun theThreeArmsAreThreeDifferentPlaces() {
        assertEquals(4, setOf(CASE_MEASURE, CASE_VERIFY, CASE_SLOW_LINK, CASE_DISTANCE).size)
    }

    /**
     * The gate opens for one arm and one arm only.
     *
     * The gate refuses a link that no estimator can align across, so the run behind it is an
     * experiment rather than a calibration: what it measures is the bias itself. Reading it as
     * either of the other two would fold that bias into the constant every session applies.
     */
    @Test
    fun onlyTheExperimentArmGoesPastTheLinkGate() {
        assertEquals(CASE_MEASURE, calibrationCase(verifying = false, allowSlowLink = false))
        assertEquals(CASE_VERIFY, calibrationCase(verifying = true, allowSlowLink = false))
        assertEquals(CASE_SLOW_LINK, calibrationCase(verifying = false, allowSlowLink = true))
    }

    /**
     * And the one combination that names no run says so instead of picking an arm.
     *
     * A verification measures what is left after the stored constant is applied; the experiment
     * arm is defined by never touching that constant. Silently choosing one of the two is the
     * shape of a mistake this project has already paid for once - a missing flag swapped the arm
     * and nothing in the result said which one had run.
     */
    @Test
    fun aVerificationWithTheGateOpenIsNotARun() {
        assertEquals(null, calibrationCase(verifying = true, allowSlowLink = true))
    }

    /**
     * Nothing a listener can press opens the gate, and this is why the extra is read from an
     * intent rather than offered as a third button: past the gate is a link on which the offset a
     * two-way exchange gives is biased by half the difference of the one way delays. A correction
     * folded from there would be applied to every session afterwards, silently and forever.
     */
    /**
     * And the arm that does go past it never reaches the line that moves the constant.
     *
     * Read out of the source because there is no seam: the whole of it is inside a method that
     * binds sockets and records audio. It is worth reading anyway - the failure it guards against
     * is silent and permanent. A constant folded from a link the gate refuses carries half the
     * difference of that link's one way delays, and every session afterwards applies it with
     * nothing in any result to notice it by. That is the C1 failure exactly, one cause over.
     */
    @Test
    fun theExperimentArmStopsShortOfTheConstant() {
        val ending = source.indexOf("if (allowSlowLink) return show(")
        val fold = source.indexOf("StoredCalibration(filesDir, paired.hostId).write(")

        assertTrue("the experiment arm does not end before the fold", ending in 1 until fold)
    }

    @Test
    fun nothingOnTheScreenOpensTheGate() {
        assertFalse(source.contains("allowSlowLink = true"))
        assertEquals(1, source.split("\"allow_slow_link\"").size - 1)
    }

    /**
     * Section 26 changed how a filling window is filtered and moved nothing else, and every number
     * that justified it came from replay. The acoustic arm needs the old rule and an early first
     * chirp, and both are constants no command line could reach - so a hardware session could only
     * ever carry one arm, and never the arm the change was made for.
     *
     * Two runs of this screen differing only in these two settings are indistinguishable in their
     * reports unless the report says which it was. That is the failure this asserts against: a
     * silently swapped arm is the one mistake this project has made more than once.
     */
    @Test
    fun theClockReportSaysWhichArmOfTheFillingRuleExperimentRan() {
        val json = clockReportJson(
            intervalMillis = 250L,
            windowSize = 64,
            bestCount = 8,
            keepFractionWhileFilling = false,
            clockFillNanos = 0L,
            radioHeld = true,
            link = null,
            atStart = null,
            atEnd = null,
            exchanges = emptyList()
        )

        assertTrue("the arm is not on the record: $json", json.contains("\"keepFractionWhileFilling\":false"))

        // Without this the two arms of the same experiment are indistinguishable in the file:
        // the filling rule only has an effect on a chirp fired while the window is filling,
        // and whether that happened is decided entirely by how long this wait was.
        assertTrue("the fill wait is not on the record: $json", json.contains("\"clockFillNanos\":0"))

        // The other knob is the host's, so it is on the host's own report rather than this one.
        val paired = pairedReportJson(
            caseId = "C93", hostId = SINK_ID, sinkId = SINK_ID,
            combined = PairedAlignment(null, emptyList(), null),
            hostReadings = emptyList(), sinkReadings = emptyList(),
            intervalFrames = 240_000, planLeadNanos = 2_000_000_000L
        )
        assertTrue("the chirp lead is not on the record: $paired", paired.contains("\"planLeadNanos\":2000000000"))
    }

    /**
     * The three settings the acoustic arm needs, each reachable from a command line.
     *
     * The capture source matters twice over: the screen builds a runner in two places, and a
     * control round that reached only one of them would be a control round in name. Counted rather
     * than found, because finding it once is what wiring half of it looks like.
     */
    @Test
    fun theCommandLineCanReachTheChirpLeadTheFillingRuleAndTheCaptureSource() {
        for (name in listOf("plan_lead_millis", "clock_fill_millis", "chirp_interval_millis")) {
            assertTrue("$name cannot be reached from a command line", source.contains("millisExtra(\"$name\""))
        }
        assertTrue(source.contains("getIntExtra(\"chirp_repeats\""))
        // And they stay integer extras, so `--ei` remains the right flag. A string extra read as
        // an int returns the default with a log line, and the run then measures the shipped arm
        // while the command line says otherwise.
        assertTrue(source.contains("intent.getIntExtra(name, (fallbackNanos / 1_000_000L).toInt())"))
        assertTrue(source.contains("getBooleanExtra(\"frozen_count\""))
        assertTrue(source.contains("CalibrationAudioSource.parse(intent.getStringExtra(\"audio_source\"))"))

        val runners = source.split("PeerCalibrationRunner(").size - 1
        val sourced = source.split("audioSource = audioSource()").size - 1
        assertEquals("a runner is built without a capture source", runners, sourced)
    }

    /**
     * The wait the chirp lead cannot reach past, which is why section 26 stayed unmeasured.
     *
     * The lead moves the first chirp relative to the plan request; this wait sits before the
     * plan is requested at all. Measured on hardware 09-10: lead 7000 put the first chirp at
     * 22.9 s of estimator life, lead 2000 at 18.0 s, and the effect being hunted lives in the
     * first four. The knob has to be this one or the run cannot reach the question.
     */
    @Test
    fun theFillWaitIsWaitedOutRatherThanSleptThrough() {
        // A wait written as one sleep cannot be shortened by a command line without also
        // changing what "shortened" means when the estimator is slow; a poll loop against a
        // deadline reads the same at zero as it does at sixteen seconds.
        assertTrue(
            "the fill wait must stay a deadline loop, not a single sleep",
            source.contains("while (System.nanoTime() - clockStartedAt < timing.clockFillNanos)")
        )
    }
    /**
     * A distance measurement is its own arm, and its own arm is the whole of how it is asked for.
     *
     * Everything this arm shortens - the fill wait, the lead, the repeats, the interval - could
     * have been four numbers on a command line instead. Four numbers that have to agree, spread
     * over two `am start`s, with nothing to check they did: that is the shape that has already
     * swapped an arm on this project once and left no trace in the result of which one ran.
     * One flag names it, and every number the arm needs is derived from the name.
     */
    @Test
    fun aDistanceMeasurementIsItsOwnArm() {
        assertEquals(CASE_DISTANCE, calibrationCase(verifying = false, allowSlowLink = false, distanceOnly = true))
        assertEquals(CASE_MEASURE, calibrationCase(verifying = false, allowSlowLink = false, distanceOnly = false))
    }

    /**
     * And the combinations that name no run say so, on the same terms the gate already does.
     *
     * A verification measures the residual left by the stored constant; a distance measurement
     * never touches that constant and deliberately runs on a clock too young to align by. Neither
     * pairing describes a run, so neither gets to pick one silently.
     */
    @Test
    fun aDistanceMeasurementCombinedWithAnotherArmIsNotARun() {
        assertEquals(null, calibrationCase(verifying = true, allowSlowLink = false, distanceOnly = true))
        assertEquals(null, calibrationCase(verifying = false, allowSlowLink = true, distanceOnly = true))
    }

    /**
     * The arm skips exactly the waits its answer does not depend on, and no others.
     *
     * The flight time comes out of the half difference of the two recordings, where a clock error
     * enters both sides with the same sign and cancels. So the sixteen second fill - a wait whose
     * entire purpose is an offset accurate enough to align by - buys a distance nothing at all,
     * and it is the single biggest thing standing between a person holding a phone to their ear
     * and being told they may put it down.
     */
    @Test
    fun aDistanceMeasurementSkipsTheWaitsItsAnswerDoesNotDependOn() {
        val short = defaultTimingFor(CASE_DISTANCE)
        assertEquals(0L, short.clockFillNanos)
        assertTrue("the lead has to come down too, or the fill saving is spent waiting", short.planLeadNanos < PLAN_LEAD)
        assertTrue(short.repeats < 5)
    }

    /**
     * Every other arm keeps what it was given, so this cannot quietly reshape a calibration.
     */
    @Test
    fun theArmsThatAlignStillRunTheShippedSchedule() {
        for (caseId in listOf(CASE_MEASURE, CASE_VERIFY, CASE_SLOW_LINK)) {
            val timing = defaultTimingFor(caseId)
            assertEquals(PLAN_LEAD, timing.planLeadNanos)
            assertEquals(CLOCK_FILL, timing.clockFillNanos)
            assertEquals(5, timing.repeats)
            assertEquals(5_000_000_000L, timing.intervalNanos)
        }
    }

    /**
     * The short interval still has to keep one pair's search out of the next pair's chirps.
     *
     * A pair is looked for over the stagger with the recording-start uncertainty added either
     * side, so consecutive windows touch when the interval falls to stagger plus twice that
     * uncertainty. Under it, a pair can be answered by its neighbour's chirp and the reading is
     * wrong rather than missing. This is the floor the shortening is allowed to approach, and
     * measuring it here is cheaper than discovering it in a quiet room.
     */
    @Test
    fun theShortIntervalStillKeepsOnePairsSearchOutOfTheNexts() {
        val uncertaintyNanos =
            CalibrationWindow.DEFAULT_UNCERTAINTY_FRAMES.toLong() * 1_000_000_000L / ChirpGenerator.SAMPLE_RATE
        val timing = defaultTimingFor(CASE_DISTANCE)
        val floor = timing.staggerNanos + 2 * uncertaintyNanos
        assertTrue(
            "interval ${timing.intervalNanos} is under the floor $floor",
            timing.intervalNanos > floor
        )
    }

    /**
     * A run this short never moves the standing correction, whatever it happens to have measured.
     *
     * It runs on an estimator eight samples old by design. That is ample for a distance, which
     * cancels the clock, and nowhere near enough for an alignment, which is the clock. The fold
     * exempts a pair nobody has measured yet - on purpose, or no first correction could ever be
     * made - so on a fresh pair there is nothing but this to stop a two second run becoming the
     * constant every session afterwards applies.
     */
    @Test
    fun aShortRunNeverMovesTheStandingCorrection() {
        assertFalse(keepsCorrection(CASE_DISTANCE))
        assertTrue(keepsCorrection(CASE_MEASURE))
        assertTrue(keepsCorrection(CASE_VERIFY))
        assertTrue(keepsCorrection(CASE_SLOW_LINK))
    }

    /**
     * The separation survives a run whose alignment cannot be read.
     *
     * It used to be written inside the branch that had a cluster mean, which made a measurement
     * that cancels the clock conditional on one that is made of it. The two share the chirps and
     * nothing else: the flight time is the half difference, the alignment is the half sum, and
     * they are orthogonal by construction. A run that clusters badly still measured the room.
     */
    @Test
    fun theSeparationSurvivesARunTheAlignmentCannotRead() {
        val pairs = listOf(facing(1.90), null, facing(2.10))
        assertEquals(2.00, measuredSeparationMetres(pairs)!!, 1e-9)
        assertEquals(null, measuredSeparationMetres(listOf(null, null)))
        assertEquals(null, measuredSeparationMetres(emptyList<FacingPair?>()))
        // The median is the point: one pair at twice its neighbours is what the archive shows
        // roughly one in six of them doing, and a three sample mean cannot survive one.
        assertEquals(2.0, measuredSeparationMetres(listOf(facing(1.9), facing(2.0), facing(9.9)))!!, 1e-9)
    }

    /**
     * And a run the pairs themselves disagree about writes nothing rather than a wrong distance.
     *
     * The stored distance is replaced, not averaged, so one bad run wipes out a good measurement.
     * Removing the verdict from the write removed a guard that had been doing this by accident:
     * three pairs measured through a handset whose media volume was at zero wrote 17.04 metres
     * over a real 0.19. The bound is what reads the answer, not what the correlator can do.
     */
    @Test
    fun aRunWhosePairsDisagreeWritesNothing() {
        // Ten good runs measured 09-10 sat at a median absolute deviation of 0 to 2.1 cm.
        assertEquals(0.20, measuredSeparationMetres(listOf(facing(0.19), facing(0.20), facing(0.21)))!!, 1e-9)
        // Metres apart is the shape a silent room makes, and it is refused rather than averaged.
        assertEquals(null, measuredSeparationMetres(listOf(facing(2.01), facing(0.62), facing(9.9))))
        // A negative separation is the two sides disagreeing about which is nearer.
        assertEquals(null, measuredSeparationMetres(listOf(facing(-0.10), facing(-0.05), facing(-0.02))))
        assertTrue(
            "the bound has to be the precedence threshold in metres, not a number picked to fit",
            SEPARATION_AGREEMENT_METRES == 0.30
        )
    }

    private fun facing(metres: Double) = FacingPair(
        alignmentErrorMs = 0.0,
        separationMetres = metres,
        flightTimeMs = metres / 343.0 * 1000,
        rawHostMs = 0.0,
        rawSinkMs = 0.0
    )
}
