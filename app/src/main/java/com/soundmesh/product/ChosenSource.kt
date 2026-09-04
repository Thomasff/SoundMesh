package com.soundmesh.product

import java.io.File

/**
 * The song this handset will play, and what it is called.
 *
 * Two files rather than one, because they answer different questions: the audio is what the
 * decoder opens, the name is what a person recognises on a screen. Same arrangement as
 * [com.soundmesh.probe.sync.PairedHost] and [com.soundmesh.probe.sync.HostIdentity] - one file,
 * one fact, read back through whatever check it went in under.
 *
 * The audio has a fixed name and no extension. MediaExtractor sniffs content rather than trusting
 * a suffix, and a fixed name means choosing a second song replaces the first instead of leaving a
 * copy of somebody's music library in this app's directory.
 */
class ChosenSource(private val directory: File) {
    fun file(): File = File(directory, FILE_NAME)

    /**
     * The display name of what was chosen, or null if nothing was.
     *
     * Null also when the name is there and the audio is not. They are written one after the other,
     * and offering a song whose audio never arrived would put the failure two screens away from
     * the moment it happened.
     */
    fun name(): String? {
        val stored = runCatching { File(directory, NAME_FILE).readText().trim() }.getOrNull()
        return stored?.takeIf { it.isNotEmpty() && file().isFile }
    }

    fun remember(displayName: String) {
        File(directory, NAME_FILE).writeText(displayName)
    }

    fun forget() {
        runCatching { File(directory, NAME_FILE).delete() }
        runCatching { file().delete() }
    }

    companion object {
        /** Bare, extensionless, and inside what the service accepts - see [SERVICE_ACCEPTS]. */
        const val FILE_NAME = "product-source"
        const val NAME_FILE = "product-source-name"

        /**
         * A copy of SessionService's own SAFE_SOURCE_FILE, on purpose.
         *
         * That one is private, and its file is one this work is not allowed to touch: the service
         * is shared with the harness every alignment measurement on record was taken through. So
         * the pattern is duplicated and a test holds the duplicate to the original, rather than a
         * comment asking a future reader to remember.
         */
        val SERVICE_ACCEPTS = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}
