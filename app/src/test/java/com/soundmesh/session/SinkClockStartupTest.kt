package com.soundmesh.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two properties of how a sink's clock starts, read out of the source the way the pair calibration
 * screen's own test reads its.
 *
 * Neither can be reached from a unit test: SinkSession needs a host, a socket and an audio device.
 * Both are things that were true, silently stopped being true, and would cost a session on hardware
 * to notice - which is the case this style of test exists for.
 */
class SinkClockStartupTest {
    private val source =
        File("src/main/java/com/soundmesh/session/SinkSession.kt").readText(Charsets.UTF_8)

    /**
     * The estimator answers nothing below MIN_SAMPLES and this session plays nothing until it
     * answers, so a joining handset stands silent for MIN_SAMPLES of whatever rate its clock opens
     * at. At the two-second cadence this session used to settle to, that was fourteen seconds -
     * what the room reported having always seen - and a burst of thirty-two exchanges 250 ms apart
     * was what shortened it.
     *
     * The burst is gone, because the cadence became the burst's own rate. What it was protecting
     * was never the burst: it was the time to the first estimate. So that is what this asserts,
     * and either mechanism satisfies it. What must not come back is a session that opens at a rate
     * slow enough to leave somebody standing in a quiet room.
     */
    @Test
    fun aJoiningHandsetReachesItsFirstEstimateWithinFourSeconds() {
        val settled = Regex("""const val CLOCK_INTERVAL_MILLIS = (\d+)L""")
            .find(source)!!.groupValues[1].toLong()
        // Whatever rate the opening exchanges actually go out at: a burst in front of the cadence
        // if there is one, the cadence itself if there is not.
        val opening =
            if (source.contains("burstIntervalMillis =")) {
                Regex("""const val CLOCK_BURST_INTERVAL_MILLIS = (\d+)L""")
                    .find(source)!!.groupValues[1].toLong()
            } else {
                settled
            }
        // MIN_SAMPLES is eight, and it lives in core where this test cannot see the constant
        // without dragging the module in; it has not moved since the estimator was written.
        assertTrue(
            "the first estimate is ${opening * 8} ms out, and fourteen seconds is what got reported",
            opening * 8 <= 4000
        )
    }

    /**
     * The shape the scatter was measured at, named where a later move of the core defaults cannot
     * quietly take this session somewhere else.
     *
     * Sixty-four of five hundred and twelve at 250 ms: the 5-95 band of the published offset falls
     * from 1.051 ms to 0.302, and the worst round past a full window from 1.596 to 0.385. Those are
     * experiments 27 and 28, measured at this shape and at no other.
     */
    @Test
    fun theSessionRunsTheWindowItsScatterWasMeasuredAt() {
        assertTrue(source.contains("ClockOffsetEstimator(CLOCK_WINDOW, CLOCK_BEST)"))
        assertTrue(source.contains("const val CLOCK_WINDOW = 512"))
        assertTrue(source.contains("const val CLOCK_BEST = 64"))
        // And read back off the instance, so a run says which shape produced it.
        assertTrue(source.contains("estimator.windowSize"))
        assertTrue(source.contains("estimator.bestCount"))
    }

    /**
     * The silence was never recorded, so the only account of it was the room's. A change that
     * shortens it has to be readable in the same place every later change will be read.
     */
    @Test
    fun aSessionRecordsHowLongItWasSilentWaitingForItsFirstEstimate() {
        assertTrue(source.contains("silentUntilFirstEstimateNanos"))
        // Minus one rather than zero while still waiting: a session that has not answered yet must
        // not read as one that answered instantly.
        assertTrue(source.contains("firstEstimateNanos == 0L || clockStartedNanos == 0L) -1L"))
    }

    /**
     * A measurement of this pair outranks a room round's guess, and both outrank zero.
     *
     * The order is the whole of the rule, and getting it the other way round is silent: a
     * handset that has been measured properly would quietly start correcting off a guess, and
     * every screen in the room would go on saying it had been measured. Zero stays the last
     * resort rather than becoming an error, because a pair nobody has measured is not a pair
     * whose offset is known to be nothing.
     */
    @Test
    fun aMeasurementOfThisPairBeatsARoomRoundsGuess() {
        val order = source.substringAfter("private val alignmentOffsetNanos")
        assertTrue(
            "the guess is read before the measurement",
            order.indexOf("StoredCalibration(") < order.indexOf("StoredApproximateCalibration(")
        )
        assertTrue(order.substringBefore("* 1_000L").contains("?: 0L"))
    }
}
