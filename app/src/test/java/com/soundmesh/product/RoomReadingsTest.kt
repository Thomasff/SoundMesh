package com.soundmesh.product

import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic strip beside the sliders.
 *
 * Worth asserting rather than eyeballing, even though it is instrumentation, because it is
 * instrumentation somebody is about to settle a question with. A readout that is subtly wrong -
 * the wrong handset named, a sign the wrong way round, a number that does not move when the thing
 * it measures does - would not look broken. It would look like an answer.
 */
class RoomReadingsTest {
    /** Two handsets, hard left and hard right, the same distance off. The listener's own setup. */
    private fun pair(): SpatialLayout = SpatialLayout(
        listOf(SpatialPosition("l", -1.0, 0.0), SpatialPosition("r", 1.0, 0.0))
    )

    private fun readingOf(field: SpatialField, peerId: String, hostNanos: Long = 0L): RoomReading =
        roomReadings(field, hostNanos).first { it.peerId == peerId }

    /** Every handset in the drawing gets a line, named, in the order the drawing names them. */
    @Test
    fun `there is one reading per handset`() {
        val readings = roomReadings(SpatialField(SpatialMode.SPLIT, pair()), 0L)
        assertEquals(listOf("l", "r"), readings.map { it.peerId })
    }

    /**
     * A plain split: both carry a side, both play at the same level, neither waits.
     *
     * The level is that of the pair rather than of either channel - a handset holding one side has
     * a silent channel and is not half as loud for it.
     */
    @Test
    fun `a plain split reads the same on both handsets`() {
        val room = SpatialField(SpatialMode.SPLIT, pair())
        for (peerId in listOf("l", "r")) {
            assertEquals(71, readingOf(room, peerId).loudness)
            assertEquals(0.0, readingOf(room, peerId).leadMillis, 1e-9)
        }
    }

    /** Placed by loudness: the source's side is louder and nothing is earlier than anything. */
    @Test
    fun `with the travel knob down only the loudness moves`() {
        val room = SpatialField(SpatialMode.PAN, pair(), pan = 1.0)
        assertEquals(100, readingOf(room, "r").loudness)
        assertEquals(0, readingOf(room, "l").loudness)
        assertEquals(0.0, readingOf(room, "r").leadMillis, 1e-9)
        assertEquals(0.0, readingOf(room, "l").leadMillis, 1e-9)
    }

    /**
     * Placed by time: the two are equally loud and the source's side is earlier by the whole depth.
     *
     * The pair of assertions that makes the strip worth having. Read together with the test above,
     * they are the travel knob's entire content written as numbers: winding it up stops one column
     * moving and starts the other, and a listener watching that happen knows which cue their ears
     * are being asked about.
     */
    @Test
    fun `with the travel knob up only the lead moves`() {
        val room = SpatialField(
            SpatialMode.PAN,
            pair(),
            pan = 1.0,
            travelDelayNanos = SpatialField.MAX_TRAVEL_DELAY_NANOS
        )
        assertEquals(readingOf(room, "l").loudness, readingOf(room, "r").loudness)
        assertEquals(
            SpatialField.MAX_TRAVEL_DELAY_NANOS / 1_000_000.0,
            readingOf(room, "r").leadMillis,
            1e-6
        )
        assertEquals(0.0, readingOf(room, "l").leadMillis, 1e-9)
    }

    /**
     * Under the wander alone, the lead changes hands - which is the claim being listened for.
     *
     * The listener reported hearing "a little something" from the wander at about a third of the
     * slider and had no way to name it. If the wander does anything locatable, this is the
     * mechanism: the handsets wander independently, so at any instant one of them is the earlier,
     * and under the precedence effect that is the side the sound should be on. Asserted over a
     * whole cycle and from both sides, because a wander that never changed hands would be a strip
     * that always named the same handset - which reads exactly like the feature working.
     */
    @Test
    fun `under the wander the lead changes hands`() {
        val room = SpatialField(
            SpatialMode.SPLIT,
            pair(),
            shimmerDelayNanos = SpatialField.MAX_SHIMMER_DELAY_NANOS
        )
        val step = SpatialField.DEFAULT_SHIMMER_PERIOD_NANOS / 64
        var leftLed = false
        var rightLed = false
        for (tick in 0 until 64) {
            val at = tick * step
            if (readingOf(room, "l", at).leadMillis > 0.0) leftLed = true
            if (readingOf(room, "r", at).leadMillis > 0.0) rightLed = true
        }
        assertTrue("the left handset is never the earlier one", leftLed)
        assertTrue("the right handset is never the earlier one", rightLed)
    }

    /**
     * And it is a lead rather than a delay: whoever is latest reads zero, always.
     *
     * Stated because the underlying number runs the other way - [SpatialField.playbackDelayNanosFor]
     * is how long a handset waits, so the handset the sound is nearest is the one with the
     * smallest. A strip whose numbers grew away from the sound would be read backwards by
     * everybody, and would be read backwards confidently.
     */
    @Test
    fun `whichever handset is latest reads nothing`() {
        val room = SpatialField(
            SpatialMode.SPLIT,
            pair(),
            shimmerDelayNanos = SpatialField.MAX_SHIMMER_DELAY_NANOS
        )
        val step = SpatialField.DEFAULT_SHIMMER_PERIOD_NANOS / 32
        for (tick in 0 until 32) {
            val leads = roomReadings(room, tick * step).map { it.leadMillis }
            assertEquals("at tick $tick", 0.0, leads.min(), 1e-9)
            assertTrue("no lead may be negative at tick $tick", leads.all { it >= 0.0 })
        }
    }
}
