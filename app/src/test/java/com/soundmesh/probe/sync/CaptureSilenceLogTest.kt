package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one place a silent room leaves a mark that is still there tomorrow.
 *
 * A counter in the session report would not do: the report is rewritten by the next session, and
 * what this is chasing happened once in an evening of listening, in the middle of a song, to
 * someone who then nudged the volume and carried on.
 */
class CaptureSilenceLogTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a spell is filed with when it happened and how long it lasted`() {
        val log = CaptureSilenceLog(folder.root)

        log.append(atMillis = 1_789_225_673_685L, silentMillis = 37_000L, recovered = true)

        val line = log.lines().single()
        assertTrue(line, line.contains("1789225673685"))
        assertTrue(line, line.contains("37000"))
    }

    /** Recovered and never-came-back are the two different answers, so they read differently. */
    @Test
    fun `a spell that never ended reads differently from one that did`() {
        val log = CaptureSilenceLog(folder.root)

        log.append(atMillis = 1L, silentMillis = 5_000L, recovered = true)
        log.append(atMillis = 2L, silentMillis = 5_000L, recovered = false)

        val lines = log.lines()
        assertEquals(2, lines.size)
        assertTrue(lines.toString(), lines[0] != lines[1])
    }

    /**
     * A later session adds to the record rather than replacing it.
     *
     * This is the whole reason it is a file of its own rather than a field in the session report.
     */
    @Test
    fun `an evening of listening keeps every spell in it`() {
        CaptureSilenceLog(folder.root).append(1L, 5_000L, true)
        CaptureSilenceLog(folder.root).append(2L, 6_000L, true)

        assertEquals(2, CaptureSilenceLog(folder.root).lines().size)
    }

    /**
     * Bounded, because nothing ever deletes it.
     *
     * It sits in the app's own directory for the life of the install, and a fault that recurs
     * every few minutes for a week should still leave a file a person can read.
     */
    @Test
    fun `the record does not grow without bound`() {
        val log = CaptureSilenceLog(folder.root)

        repeat(4_000) { log.append(it.toLong(), 5_000L, true) }

        val lines = log.lines()
        assertTrue("${lines.size} lines", lines.size in 1..CaptureSilenceLog.MOST_LINES)
        // And it is the recent half that survives, not the first half: the last spell is the one
        // somebody is asking about.
        assertTrue(lines.last(), lines.last().startsWith("3999 "))
    }

    /** Nothing has happened yet is not an error, it is the ordinary case. */
    @Test
    fun `a record nothing has been filed in reads empty`() {
        assertEquals(emptyList<String>(), CaptureSilenceLog(folder.root).lines())
    }
}
