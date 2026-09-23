package com.soundmesh.probe.sync

import com.soundmesh.core.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PairedHostTest {
    private fun temporaryDir(): File = Files.createTempDirectory("paired-host").toFile()

    private val code = PairingCode("da3fe1c00de55dc6", "192.168.43.1", 45124)

    @Test
    fun readsNoHostBeforeAnythingIsScanned() {
        assertNull(PairedHost(temporaryDir()).read())
    }

    @Test
    fun keepsAScannedHostBeyondTheProcessThatScannedIt() {
        val directory = temporaryDir()

        PairedHost(directory).write(code)

        assertEquals(code, PairedHost(directory).read())
    }

    @Test
    fun replacesTheEarlierScanWithTheLaterOne() {
        val directory = temporaryDir()
        val other = PairingCode("0123456789abcdef", "192.168.1.7", 45124)

        PairedHost(directory).write(code)
        PairedHost(directory).write(other)

        assertEquals(other, PairedHost(directory).read())
    }

    /** Half a host is no host: the file goes back through the checks the scan itself went through. */
    @Test
    fun readsNoHostFromADamagedFile() {
        val directory = temporaryDir()
        File(directory, PairedHost.FILE_NAME).writeText("soundmesh-pairing 2 da3fe1c00de5")

        assertNull(PairedHost(directory).read())
    }
}
