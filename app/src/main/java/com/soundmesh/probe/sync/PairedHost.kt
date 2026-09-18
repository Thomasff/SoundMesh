package com.soundmesh.probe.sync

import com.soundmesh.core.PairingCode
import com.soundmesh.core.PairingCodeCodec
import java.io.File

/**
 * The host this handset was last pointed at.
 *
 * Kept across runs because scanning and playing are separate acts: someone holds the phone up to a
 * screen once, and every run after that starts without anyone touching anything. That is also why
 * it is not held in memory - the run is launched into a fresh process.
 *
 * Stored in exactly the text that was on the screen, so the file is read back through the same
 * checks the scan went through. A truncated or edited file is then not a half-trusted host but no
 * host at all.
 */
class PairedHost(private val directory: File) {
    fun read(): PairingCode? {
        val file = file()
        if (!file.isFile) return null
        return runCatching { PairingCodeCodec.decode(file.readText()) }.getOrNull()
    }

    fun write(code: PairingCode) {
        file().writeText(PairingCodeCodec.encode(code))
    }

    /**
     * Stops pointing at anybody, which is what becoming the host means.
     *
     * Nothing used to remove this file, so a handset that had ever been pointed at a host kept
     * pointing at it for good - through being the host itself and back again. That was invisible
     * while a code had to be scanned every time, and it stops the finding working the moment it
     * does not: a look for a host is skipped entirely while one is remembered, so the phone that
     * hosted last night quietly refuses to join whoever is hosting tonight.
     */
    fun forget() {
        runCatching { file().delete() }
    }

    private fun file() = File(directory, FILE_NAME)

    companion object {
        const val FILE_NAME = "scanned-pairing"
    }
}
