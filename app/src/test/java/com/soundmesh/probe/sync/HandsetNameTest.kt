package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The half of a handset's identity that a person can say out loud.
 *
 * It is the one field in the room protocol whose content a person types, and it ends up on
 * somebody else's screen, so what it refuses is worth as much as what it keeps.
 */
class HandsetNameTest {
    private fun temporaryDir(): File = Files.createTempDirectory("handset-name").toFile()

    @Test
    fun remembersTheNameItWasGiven() {
        val directory = temporaryDir()

        StoredHandsetName(directory).write("Thomas de Ping Ban")

        assertEquals("Thomas de Ping Ban", StoredHandsetName(directory).read())
    }

    @Test
    fun answersNothingBeforeAnybodyHasNamedIt() {
        assertNull(StoredHandsetName(temporaryDir()).read())
    }

    /**
     * Flattened rather than refused: what puts a newline in here is a paste rather than a person,
     * and a paste is worth keeping the readable part of. What must not happen is a line break
     * reaching a screen that lays out one handset per line.
     */
    @Test
    fun flattensAnythingThatWouldBreakALine() {
        assertEquals("two lines", StoredHandsetName.cleaned("two\nlines"))
        assertEquals("one two", StoredHandsetName.cleaned("  one   two  "))
        assertEquals("a b", StoredHandsetName.cleaned("a\u0000b"))
    }

    /** Empty has one representation rather than two, so "no name" is answered one way. */
    @Test
    fun aNameThatCleansAwayToNothingIsNoName() {
        assertNull(StoredHandsetName.cleaned(""))
        assertNull(StoredHandsetName.cleaned("   \n  "))
        val directory = temporaryDir()
        StoredHandsetName(directory).write("something")
        StoredHandsetName(directory).write("   ")
        assertNull("an empty name was stored as a name", StoredHandsetName(directory).read())
    }

    /** Bounded, and the bound is on the wire as much as on the layout. */
    @Test
    fun aNameIsAsLongAsOneLineAndNoLonger() {
        val long = "x".repeat(StoredHandsetName.MAX_CHARACTERS + 20)

        assertEquals(StoredHandsetName.MAX_CHARACTERS, StoredHandsetName.cleaned(long)!!.length)
    }
}
