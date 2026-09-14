package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who is in the room when nobody is playing.
 *
 * Reported on 2026-09-14: a handset opened its app, appeared in the volume list on the host
 * straight away, and did not appear on the drawing at all until somebody pressed play. Two
 * components on one screen, two ideas of the room - the volume list is built from the standing
 * channel, and the drawing had no roster of its own outside a session, so between sessions it
 * could only ever hollow out the handsets the last session had left behind.
 */
class BetweenSessionsRosterTest {
    @Test
    fun `a handset standing by is in the room, even if no session ever drew it`() {
        assertEquals(
            listOf("me", "kept", "new"),
            betweenSessionsRoster(selfId = "me", drawn = listOf("me", "kept"), standing = listOf("kept", "new"))
        )
    }

    @Test
    fun `a handset that left stays on the drawing, so its position is not lost`() {
        assertEquals(
            listOf("me", "gone"),
            betweenSessionsRoster(selfId = "me", drawn = listOf("me", "gone"), standing = emptyList())
        )
    }

    @Test
    fun `this handset comes first, because that is what says which one is not a sink`() {
        // whoIsNotStandingBy drops the head of this list. Put the head anywhere else and the host
        // hollows itself out while some sink is quietly exempted from ever being hollowed.
        assertEquals(
            "me",
            betweenSessionsRoster(selfId = "me", drawn = listOf("other", "me"), standing = listOf("other")).first()
        )
    }

    @Test
    fun `nobody is named twice, whichever lists they turn up in`() {
        assertEquals(
            listOf("me", "both"),
            betweenSessionsRoster(selfId = "me", drawn = listOf("me", "both"), standing = listOf("both", "me"))
        )
    }

    @Test
    fun `a host that has never drawn anything is still in its own room`() {
        assertEquals(
            listOf("me"),
            betweenSessionsRoster(selfId = "me", drawn = emptyList(), standing = emptyList())
        )
    }

    @Test
    fun `a host that does not know its own name does not invent one`() {
        assertEquals(
            listOf("one"),
            betweenSessionsRoster(selfId = null, drawn = emptyList(), standing = listOf("one"))
        )
    }
}
