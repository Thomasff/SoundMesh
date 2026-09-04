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

    private fun file() = File(directory, FILE_NAME)

    companion object {
        const val FILE_NAME = "scanned-pairing"
    }
}
