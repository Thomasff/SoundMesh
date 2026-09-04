package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostIdTest {
    @Test
    fun generatesTheShapeItAccepts() {
        val id = HostId.generate()

        assertEquals(HostId.LENGTH, id.length)
        assertTrue("generated ids must pass their own check: $id", HostId.isValid(id))
    }

    @Test
    fun doesNotHandOutTheSameNameTwice() {
        val ids = (1..1000).map { HostId.generate() }.toSet()

        assertEquals(1000, ids.size)
    }

    /**
     * The shape is the guard, not decoration. This value names a file on the handset that read it,
     * and a scanner is the one place in the system where a stranger's bytes get to become one.
     */
    @Test
    fun refusesAnythingThatCouldBeAPathOrASeparator() {
        assertFalse(HostId.isValid("../../etc/passwd"))
        assertFalse(HostId.isValid("0123456789abcde/"))
        assertFalse(HostId.isValid("0123456789abcd f"))
        assertFalse(HostId.isValid(null))
    }

    @Test
    fun refusesTheRightShapeInTheWrongAlphabet() {
        assertFalse("uppercase would file the same peer twice", HostId.isValid("0123456789ABCDEF"))
        assertFalse(HostId.isValid("0123456789abcdeg"))
    }

    @Test
    fun refusesTheRightAlphabetAtTheWrongLength() {
        assertFalse(HostId.isValid("0123456789abcde"))
        assertFalse(HostId.isValid("0123456789abcdef0"))
        assertFalse(HostId.isValid(""))
    }
}
