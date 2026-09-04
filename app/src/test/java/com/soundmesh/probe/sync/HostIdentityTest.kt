package com.soundmesh.probe.sync

import com.soundmesh.core.HostId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HostIdentityTest {
    private fun temporaryDir(): File = Files.createTempDirectory("host-identity").toFile()

    @Test
    fun namesItselfOnceAndThenKeepsThatName() {
        val directory = temporaryDir()

        val first = HostIdentity(directory).current()

        assertTrue(HostId.isValid(first))
        assertEquals(first, HostIdentity(directory).current())
    }

    /** Two handsets must not answer to the same name, or one peer's history covers both. */
    @Test
    fun givesTwoHandsetsDifferentNames() {
        assertNotEquals(HostIdentity(temporaryDir()).current(), HostIdentity(temporaryDir()).current())
    }

    /**
     * A truncated write reads as a different handset to everyone else, so keeping it would attach
     * this phone's history to a name nothing recognises. Replaced rather than salvaged.
     */
    @Test
    fun replacesANameThatIsNotOne() {
        val directory = temporaryDir()
        File(directory, HostIdentity.FILE_NAME).writeText("0123456")

        val recovered = HostIdentity(directory).current()

        assertTrue(HostId.isValid(recovered))
        assertEquals(recovered, HostIdentity(directory).current())
    }
}
