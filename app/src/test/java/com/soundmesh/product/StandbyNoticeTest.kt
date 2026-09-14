package com.soundmesh.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the standing-by notification has to be written again.
 *
 * It exists because the one it replaces said "待命中" for the whole life of the service without
 * ever looking at the line, and on 2026-09-14 that sentence was read as evidence during a hunt
 * for why the host had let go of a handset - by a person standing in front of the phone that was
 * lying about it.
 */
class StandbyNoticeTest {
    private val notice = StandbyNotice(refreshMillis = 10_000L)

    @Test
    fun `the first state is always written, because the screen has nothing on it yet`() {
        assertTrue(notice.shouldPost(up = true, now = 0L))
    }

    @Test
    fun `a line that stays up is not written again, however long it stays`() {
        notice.shouldPost(up = true, now = 0L)

        assertFalse(notice.shouldPost(up = true, now = 1_000L))
        assertFalse(notice.shouldPost(up = true, now = 600_000L))
    }

    @Test
    fun `a line going down is written straight away`() {
        notice.shouldPost(up = true, now = 0L)

        assertTrue(notice.shouldPost(up = false, now = 1_000L))
    }

    @Test
    fun `a line coming back is written straight away`() {
        notice.shouldPost(up = false, now = 0L)

        assertTrue(notice.shouldPost(up = true, now = 1_000L))
    }

    /**
     * The count of attempts that did not go out is the one number that tells a person whether the
     * loop is running at all, so it has to be refreshed while the line is down. At one second a
     * tick this cannot be every tick.
     */
    @Test
    fun `a line that is still down is refreshed, but not on every tick`() {
        notice.shouldPost(up = false, now = 0L)

        assertFalse(notice.shouldPost(up = false, now = 1_000L))
        assertFalse(notice.shouldPost(up = false, now = 9_999L))
        assertTrue(notice.shouldPost(up = false, now = 10_000L))
        assertFalse(notice.shouldPost(up = false, now = 10_001L))
        assertTrue(notice.shouldPost(up = false, now = 20_000L))
    }

    /** The refresh window runs from the last write, not from when the line went down. */
    @Test
    fun `the window runs from the last thing written`() {
        notice.shouldPost(up = false, now = 0L)
        notice.shouldPost(up = false, now = 10_000L)

        assertFalse(notice.shouldPost(up = false, now = 19_000L))
        assertTrue(notice.shouldPost(up = false, now = 20_000L))
    }
}
