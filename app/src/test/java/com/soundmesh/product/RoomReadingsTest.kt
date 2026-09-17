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
        }
    }

    /** Placed by loudness, which since 2026-09-17 is the only way anything here is placed. */
    @Test
    fun `the side the source is on is the louder one`() {
        val room = SpatialField(SpatialMode.PAN, pair(), pan = 1.0)
        assertEquals(100, readingOf(room, "r").loudness)
        assertEquals(0, readingOf(room, "l").loudness)
    }

}
