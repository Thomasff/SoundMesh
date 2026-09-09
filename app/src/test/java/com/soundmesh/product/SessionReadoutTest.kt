package com.soundmesh.product

import com.soundmesh.probe.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReadoutTest {
    /** The shape a sink actually writes, fields and all. */
    private val sinkReport = """{"played":3000,"releaseTrims":2,"trimmedFrames":88,""" +
        """"silenceWrites":1,"droppedLate":0,"droppedOverflow":0,"trackUnderruns":0,""" +
        """"reconnects":2,"rediscoveries":1,"clockHealth":"GOOD","worstUncertaintyNanos":2670000}"""

    /** And the shape a host writes: different fields, same function. */
    private val hostReport = """{"played":3000,"releaseTrims":3,"trimmedFrames":120,""" +
        """"silenceWrites":3,"droppedLate":0,"droppedOverflow":0,"trackUnderruns":0,""" +
        """"maxBroadcastNanos":1000000,"generated":3010,"droppedToSinks":1268}"""

    private fun valueOf(report: String, label: Int): String =
        SessionReadout.counters(report).first { it.label == label }.value

    @Test
    fun noReportIsNoRowsRatherThanRowsOfDashes() {
        assertEquals(emptyList<Counter>(), SessionReadout.counters(null))
    }

    /** 3000 chunks of 20 ms. Read as a duration, because that is what a listener is holding. */
    @Test
    fun playedBecomesATime() {
        assertEquals("1:00", valueOf(sinkReport, R.string.counter_played))
    }

    /** Over an hour the minutes keep counting rather than wrapping. */
    @Test
    fun aLongSessionKeepsCountingMinutes() {
        assertEquals("75:00", valueOf("""{"played":225000}""", R.string.counter_played))
    }

    /**
     * Both trim numbers get a row of their own. One without the other says nothing: two trims of a
     * frame each and two of a whole chunk are the same count and a different sound.
     */
    @Test
    fun bothHalvesOfATrimAreShown() {
        assertEquals("2", valueOf(sinkReport, R.string.counter_trims))
        assertEquals("88", valueOf(sinkReport, R.string.counter_trimmed_frames))
    }

    @Test
    fun aSinkShowsWhatOnlyASinkHas() {
        val labels = SessionReadout.counters(sinkReport).map { it.label }
        assertTrue(labels.contains(R.string.counter_reconnects))
        assertTrue(labels.contains(R.string.counter_rediscoveries))
        assertTrue(labels.contains(R.string.counter_clock))
        assertFalse(labels.contains(R.string.counter_broadcast))
        assertFalse(labels.contains(R.string.counter_to_sinks))
    }

    @Test
    fun aHostShowsWhatOnlyAHostHas() {
        val labels = SessionReadout.counters(hostReport).map { it.label }
        assertTrue(labels.contains(R.string.counter_broadcast))
        assertTrue(labels.contains(R.string.counter_to_sinks))
        assertFalse(labels.contains(R.string.counter_reconnects))
        assertFalse(labels.contains(R.string.counter_clock))
    }

    /** Nanoseconds are the wire format. Nobody reads 2670000. */
    @Test
    fun theClockRowCarriesItsWorstReadingInMilliseconds() {
        assertEquals("GOOD 2.67 ms", valueOf(sinkReport, R.string.counter_clock))
    }

    /** The counter that would say the per-sink queues had stopped working. */
    @Test
    fun theSlowestBroadcastIsMilliseconds() {
        assertEquals("1 ms", valueOf(hostReport, R.string.counter_broadcast))
    }

    /**
     * A sink that has an estimate but no grading yet still gets a row, because the alternative is
     * a row that appears partway through a session for no reason a watcher can see.
     */
    @Test
    fun aClockWithNoGradingYetStillGetsARow() {
        assertEquals("- 0.00 ms", valueOf("""{"played":10,"reconnects":0}""", R.string.counter_clock))
    }

    /** A truncated file is a report with nothing in it, not a crash. */
    @Test
    fun aReportWithNothingRecognisableProducesNoRows() {
        assertEquals(emptyList<Counter>(), SessionReadout.counters("{"))
    }

    /** Zero is a reading. A row that vanishes when its counter is zero cannot be watched. */
    @Test
    fun aCounterAtZeroStillGetsARow() {
        val labels = SessionReadout.counters(sinkReport).map { it.label }
        assertTrue(labels.contains(R.string.counter_dropped))
        assertTrue(labels.contains(R.string.counter_overflow))
        assertTrue(labels.contains(R.string.counter_underruns))
        assertEquals("0", valueOf(sinkReport, R.string.counter_underruns))
    }

    /**
     * A sink is in nobody's room and sends audio to nobody, so it gets neither row. The rows are
     * keyed off a field only a host writes, for the reason the rest of this file already gives:
     * a row of zeroes reads as a host whose sinks all left.
     */
    @Test
    fun aSinkIsInNoRoomAndCountsNoConnections() {
        val labels = SessionReadout.counters(sinkReport).map { it.label }
        assertFalse(labels.contains(R.string.counter_room))
        assertFalse(labels.contains(R.string.counter_sinks))
    }
}
