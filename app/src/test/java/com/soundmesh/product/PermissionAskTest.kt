package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionAskTest {
    @Test
    fun `a permission already held is not asked for again`() {
        assertEquals(AskRoute.NOTHING, askRoute(granted = true, askedBefore = false))
        assertEquals(AskRoute.NOTHING, askRoute(granted = true, askedBefore = true))
    }

    @Test
    fun `never asked means show the system dialog`() {
        assertEquals(AskRoute.DIALOG, askRoute(granted = false, askedBefore = false))
    }

    // Asked once and still not granted means they said no - and after "don't ask again" the
    // system dialog never appears, so a second launch of it is a button that does nothing.
    // Sending them to the settings page is the only thing left that works.
    @Test
    fun `asked once and refused means go to the settings page instead`() {
        assertEquals(AskRoute.SETTINGS, askRoute(granted = false, askedBefore = true))
    }
}
