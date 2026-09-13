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
     * answers, so the settled cadence alone leaves a joining handset silent for fourteen seconds -
     * which is what the room reported having always seen. The burst is what shortens it, and a
     * cadence passed without one restores the fourteen with nothing in any report to say so.
     */
    @Test
    fun theClockStartsWithABurstRatherThanLeavingAJoiningHandsetSilentForFourteenSeconds() {
        assertTrue(
            "the session's clock no longer bursts, so a joining handset waits out MIN_SAMPLES",
            source.contains("burstExchanges = CLOCK_BURST_EXCHANGES")
        )
        assertTrue(source.contains("burstIntervalMillis = CLOCK_BURST_INTERVAL_MILLIS"))
        // Long enough to beat the settled cadence on accuracy as well as on time - eight would reach
        // sound sooner and land past the millisecond a room can hear. See on-device-calibration 28.1.
        assertTrue(
            "a burst shorter than the window's quarter buys speed by giving up the accuracy 28.1 measured",
            Regex("""const val CLOCK_BURST_EXCHANGES = (\d+)""").find(source)!!.groupValues[1].toInt() >= 16
        )
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
