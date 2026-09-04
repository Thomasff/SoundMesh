package com.soundmesh.probe.sync

import com.soundmesh.core.HostId
import java.io.File

/**
 * The name this handset answers to, kept across runs.
 *
 * Generated once and then never again, because everything filed under it - a peer's alignment
 * correction first - is lost the moment it changes. Re-generated only when what is on disk is not
 * a name at all: a truncated write is indistinguishable from a different handset to whoever reads
 * it, so keeping it would silently attach one phone's history to a name nothing else recognises.
 */
class HostIdentity(private val directory: File) {
    fun current(): String {
        val file = File(directory, FILE_NAME)
        val stored = runCatching { file.readText().trim() }.getOrNull()
        if (HostId.isValid(stored)) return stored!!
        val fresh = HostId.generate()
        file.writeText(fresh)
        return fresh
    }

    companion object {
        const val FILE_NAME = "host-id"
    }
}
