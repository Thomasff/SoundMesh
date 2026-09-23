package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one place this app writes down what it did, in an order, that is still there ten minutes
 * later.
 *
 * logcat is not that place on these handsets: on 09-13 it held ninety seconds of this package and
 * not one of those lines was ours.
 */
class EventLogTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `an event is filed with when it happened`() {
        val log = EventLog(folder.root)

        log.write("room-open C94", atMillis = 1_789_275_573_845L)

        val line = log.lines().single()
        assertTrue(line, line.startsWith("1789275573845 "))
        assertTrue(line, line.endsWith("room-open C94"))
    }

    /**
     * One timeline, in order, across everything that writes to it.
     *
     * Which of two things happened first is most of what makes a sequence explicable, and it is
     * exactly what a listener cannot remember afterwards.
     */
    @Test
    fun `events keep the order they happened in`() {
        val log = EventLog(folder.root)

        log.write("role HOST", 1L)
        log.write("standby 3", 2L)
        log.write("room-open C94", 3L)

        assertEquals(
            listOf("1 role HOST", "2 standby 3", "3 room-open C94"),
            log.lines()
        )
    }

    /** A later session adds to the record rather than replacing it: an evening is many sessions. */
    @Test
    fun `an evening of use keeps every event in it`() {
        EventLog(folder.root).write("session-start", 1L)
        EventLog(folder.root).write("session-stop", 2L)

        assertEquals(2, EventLog(folder.root).lines().size)
    }

    /** One line each, because a line that wrapped would read as two events that never happened. */
    @Test
    fun `an event that spans lines is filed as one line`() {
        val log = EventLog(folder.root)

        log.write("bind-failed 45123\njava.net.BindException", 1L)

        assertEquals(1, log.lines().size)
    }

    /**
     * Bounded, because nothing ever deletes it.
     *
     * Trimmed from the front: the end of the file is the part somebody is asking about.
     */
    @Test
    fun `the record does not grow without bound`() {
        val log = EventLog(folder.root)

        repeat(EventLog.MOST_LINES + 500) { log.write("tick $it", it.toLong()) }

        val lines = log.lines()
        assertEquals(EventLog.MOST_LINES, lines.size)
        assertTrue(lines.last(), lines.last().endsWith("tick ${EventLog.MOST_LINES + 499}"))
    }

    /** Nothing has happened yet is the ordinary case, not an error. */
    @Test
    fun `a record nothing has been filed in reads empty`() {
        assertEquals(emptyList<String>(), EventLog(folder.root).lines())
    }
}
