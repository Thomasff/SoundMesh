package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoredSeparationTest {
    @get:Rule val folder = TemporaryFolder()

    private val peer = "a1b2c3d4e5f60718"

    private fun store(id: String = peer) = StoredSeparation(folder.root, id)

    @Test
    fun aPairNobodyHasMeasuredHasNoDistance() {
        assertNull(store().read())
    }

    @Test
    fun whatWasWrittenComesBack() {
        store().write(2.75)

        assertEquals(2.75, store().read()!!, 1e-9)
    }

    /**
     * Replaced rather than averaged, unlike the alignment correction next door. That one estimates
     * a constant of the pair and gets better with every run; this one describes where the phones
     * were standing, and where they stood last week is not evidence about where they stand now.
     */
    @Test
    fun theNewestMeasurementIsTheOneKept() {
        store().write(4.0)
        store().write(1.25)

        assertEquals(1.25, store().read()!!, 1e-9)
    }

    /**
     * Zero is what an unmeasured pair reads as through the whole calibration path - see
     * AlignmentAnalysis, where it is the value passed when nothing was measured - so writing it
     * here would turn "we do not know" into "they are in the same place", which the check would
     * then read as a contradiction with every drawing.
     */
    @Test
    fun anImpossibleDistanceIsNotKept() {
        store().write(3.0)
        store().write(0.0)

        assertEquals(3.0, store().read()!!, 1e-9)
    }

    @Test
    fun oneFilePerPeer() {
        store().write(1.0)
        store("0918273645abcdef").write(9.0)

        assertEquals(1.0, store().read()!!, 1e-9)
        assertEquals(9.0, store("0918273645abcdef").read()!!, 1e-9)
    }

    /** The id becomes a file name, and it can arrive off a scanned screen. */
    @Test
    fun aPeerIdThatIsNotOneIsRefusedBeforeItBecomesAPath() {
        val thrown = runCatching { StoredSeparation(folder.root, "../elsewhere") }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    /** A half written file reads as no measurement rather than as a distance of whatever parsed. */
    @Test
    fun anUnreadableFileIsNoMeasurement() {
        store().write(2.0)
        folder.root.listFiles()!!.first { it.name.startsWith(StoredSeparation.FILE_PREFIX) }
            .writeText("2.")
        // "2." parses, so the damage has to be something a Double cannot be.
        folder.root.listFiles()!!.first { it.name.startsWith(StoredSeparation.FILE_PREFIX) }
            .writeText("2.7 metres")

        assertNull(store().read())
    }
}
