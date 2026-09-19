package com.soundmesh.probe.sync

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRunLogTest {
    private fun temporaryDir(): File = Files.createTempDirectory("peer-run-log").toFile()

    @Test
    fun filesAnAttemptUnderTheMomentItHappened() {
        val directory = temporaryDir()

        val written = PeerRunLog(directory).write("SINK-C90", "{\"role\":\"SINK\"}", 1_757_300_000_000L)

        assertEquals("1757300000000-SINK-C90.json", written.name)
        assertEquals(PeerRunLog.DIRECTORY, written.parentFile.name)
        assertEquals("{\"role\":\"SINK\"}", written.readText())
    }

    /**
     * The whole reason this exists. Two runs of one case share a run store directory and the
     * second overwrites the first in place, which is how four runs on 2026-09-08 became one.
     */
    @Test
    fun aSecondAttemptNeverWritesOverTheFirst() {
        val directory = temporaryDir()
        val log = PeerRunLog(directory)

        val first = log.write("SINK-C91", "{\"n\":1}", 1_757_300_000_000L)
        val second = log.write("SINK-C91", "{\"n\":2}", 1_757_300_000_000L)

        assertTrue("the two attempts landed on one name", first.name != second.name)
        assertEquals("{\"n\":1}", first.readText())
        assertEquals("{\"n\":2}", second.readText())
        assertEquals(2, log.filed().size)
    }

    /**
     * A label becomes a path. Nothing outside this class composes one today, but the run store
     * checks its case ids for the same reason and one of them arrives over a socket.
     */
    @Test
    fun aLabelThatWouldLeaveTheDirectoryIsRefused() {
        val log = PeerRunLog(temporaryDir())

        for (unusable in listOf("../escape", "SINK/C90", "", "a".repeat(49))) {
            assertThrows(IllegalArgumentException::class.java) { log.write(unusable, "{}", 1L) }
        }
    }

    /**
     * Once a host serves more than one sink, the peer's name belongs in the label - a directory
     * listing is where somebody looks first, and a signature only in the JSON body is not there.
     * The longest of them is what the cap has to admit.
     */
    @Test
    fun aLabelNamingACaseAndAPeerFits() {
        val log = PeerRunLog(temporaryDir())

        assertTrue(log.write("HOST-MEASURE-a1b2c3d4e5f60718-PAIRED", "{}", 1L).isFile)
    }

    @Test
    fun answersNothingBeforeTheFirstAttempt() {
        assertEquals(emptyList<File>(), PeerRunLog(temporaryDir()).filed())
    }

    /**
     * Nothing else deletes these, and a handset calibrates for as long as somebody owns it. The
     * oldest go because what anybody asks about is the recent end - the same reason [EventLog]
     * trims from the front.
     */
    @Test
    fun keepsOnlyTheMostRecentAttempts() {
        val log = PeerRunLog(temporaryDir())

        for (index in 1..PeerRunLog.MOST_FILES + 5) {
            log.write("SINK-C90", "{\"n\":$index}", 1_757_300_000_000L + index)
        }

        val filed = log.filed()
        assertEquals(PeerRunLog.MOST_FILES, filed.size)
        assertEquals("{\"n\":6}", filed.first().readText())
        assertEquals("{\"n\":${PeerRunLog.MOST_FILES + 5}}", filed.last().readText())
    }

    /**
     * The trim runs on the way out of a write, so the one file the caller is holding is the one it
     * could most easily take: it is the newest, and a cap applied before the count is recounted
     * would be off by exactly one.
     */
    @Test
    fun theAttemptJustFiledSurvivesAFullLog() {
        val log = PeerRunLog(temporaryDir())
        for (index in 1..PeerRunLog.MOST_FILES) {
            log.write("SINK-C90", "{\"n\":$index}", 1_757_300_000_000L + index)
        }

        val written = log.write("SINK-C90", "{\"last\":true}", 1_757_400_000_000L)

        assertTrue("the trim took the attempt it had just filed", written.isFile)
        assertEquals("{\"last\":true}", written.readText())
    }
}
