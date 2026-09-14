package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeChoiceTest {
    @Test
    fun `following the system means following the system, both ways`() {
        assertTrue(darkWanted(ThemeChoice.SYSTEM, systemIsDark = true))
        assertFalse(darkWanted(ThemeChoice.SYSTEM, systemIsDark = false))
    }

    // The point of an explicit choice is that it wins. A "dark" that goes light because the
    // phone is light is not a setting, it is a decoration.
    @Test
    fun `an explicit choice beats the system in both directions`() {
        assertTrue(darkWanted(ThemeChoice.DARK, systemIsDark = false))
        assertFalse(darkWanted(ThemeChoice.LIGHT, systemIsDark = true))
    }

    @Test
    fun `nothing stored means follow the system`() {
        assertEquals(ThemeChoice.SYSTEM, themeChoiceOf(null))
    }

    @Test
    fun `what was stored comes back`() {
        assertEquals(ThemeChoice.DARK, themeChoiceOf("DARK"))
        assertEquals(ThemeChoice.LIGHT, themeChoiceOf("LIGHT"))
    }

    // A value written by an older build, or a half-written file, must not crash the app on
    // launch - and the safe landing is the one that needs no decision.
    @Test
    fun `a value nobody recognises means follow the system`() {
        assertEquals(ThemeChoice.SYSTEM, themeChoiceOf("PUCE"))
    }
}
