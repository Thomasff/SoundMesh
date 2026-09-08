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

        for (unusable in listOf("../escape", "SINK/C90", "", "a".repeat(33))) {
            assertThrows(IllegalArgumentException::class.java) { log.write(unusable, "{}", 1L) }
        }
    }

    @Test
    fun answersNothingBeforeTheFirstAttempt() {
        assertEquals(emptyList<File>(), PeerRunLog(temporaryDir()).filed())
    }
}
