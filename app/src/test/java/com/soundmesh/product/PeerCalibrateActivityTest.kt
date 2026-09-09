package com.soundmesh.product

import com.soundmesh.core.AlignmentConfidence
import com.soundmesh.core.AlignmentPairing
import com.soundmesh.core.AlignmentReading
import com.soundmesh.core.AlignmentResultMessage
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockExchange
import com.soundmesh.core.LinkQuality
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
        val cases = Regex("const val CASE_(?:MEASURE|VERIFY) = \"([A-Z])([0-9]+)\"")
            .findAll(source).map { it.groupValues[2].toInt() }.toList()
        assertEquals(2, cases.size)
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
        assertTrue(source.contains("request.caseId !in setOf(CASE_MEASURE, CASE_VERIFY)"))
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
                250L, 64, 8, true,
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
        val gate = source.substringAfter("link?.takeIf { !it.usable }?.let {")
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

        val json = pairedReportJson("C91", "0123456789abcdef", SINK_ID, combined, host, sink, 5 * 48_000)

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
        val json = pairedReportJson("C91", "0123456789abcdef", SINK_ID, combined, host, sink, 5 * 48_000)

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
        val json = clockReportJson(250L, 64, 8, true, null, null, null, emptyList())

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
            clockReportJson(250L, 64, 8, true, null, null, null, emptyList()).contains("\"radioHeld\":true")
        )
        assertTrue(
            clockReportJson(250L, 64, 8, false, null, null, null, emptyList()).contains("\"radioHeld\":false")
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
            250L, 64, 8, true,
            LinkQuality(medianRoundTripNanos = 56_300_000L, p90RoundTripNanos = 137_900_000L, samples = 175),
            null, null, emptyList()
        )

        assertTrue(json.contains("\"medianRoundTripNanos\":56300000"))
        assertTrue(json.contains("\"p90RoundTripNanos\":137900000"))
        assertTrue(json.contains("\"samples\":175"))
    }
}
