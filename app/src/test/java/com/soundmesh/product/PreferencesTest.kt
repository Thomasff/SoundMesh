package com.soundmesh.product

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a key that was never written reads null`() {
        assertNull(Preferences(folder.root).read("theme"))
    }

    @Test
    fun `what was written comes back`() {
        val prefs = Preferences(folder.root)
        prefs.write("theme", "DARK")
        assertEquals("DARK", Preferences(folder.root).read("theme"))
    }

    @Test
    fun `writing a key again replaces it rather than appending`() {
        val prefs = Preferences(folder.root)
        prefs.write("theme", "DARK")
        prefs.write("theme", "LIGHT")
        assertEquals("LIGHT", prefs.read("theme"))
        assertEquals(1, folder.root.resolve("preferences").readLines().size)
    }

    @Test
    fun `one key does not disturb another`() {
        val prefs = Preferences(folder.root)
        prefs.write("theme", "DARK")
        prefs.write("details", "on")
        assertEquals("DARK", prefs.read("theme"))
        assertEquals("on", prefs.read("details"))
    }

    // A value containing the separator would otherwise come back truncated, and the one value
    // this store is most likely to hold with an "=" in it is a URL.
    @Test
    fun `a value containing an equals sign survives`() {
        val prefs = Preferences(folder.root)
        prefs.write("url", "https://example.invalid/a?b=c")
        assertEquals("https://example.invalid/a?b=c", prefs.read("url"))
    }

    // A half-written file is what a kill during write leaves behind. It must not take the
    // whole settings screen down with it.
    @Test
    fun `a line with no separator is skipped rather than thrown on`() {
        folder.root.resolve("preferences").writeText("rubbish\ntheme=DARK\n")
        assertEquals("DARK", Preferences(folder.root).read("theme"))
    }
}
