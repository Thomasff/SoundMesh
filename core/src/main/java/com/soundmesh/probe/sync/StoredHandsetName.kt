package com.soundmesh.probe.sync

import java.io.File

/**
 * What this handset is called, in words a person can say out loud.
 *
 * The other half of an identity whose first half is [HostIdentity] - sixteen random hexadecimal
 * characters, which is the right shape for a file name and a protocol field and the wrong shape
 * for a sentence. Every screen that had to name a handset printed four of those characters, and
 * on 2026-09-11 that killed a feature outright: "which handset dropped out" was built and then
 * abandoned, because the answer it could give was "3f2a dropped out" and nobody can act on that.
 *
 * The two halves are found in deliberately different ways. The id is random, never changes while
 * the app is installed, and is allowed to collide with nothing because nothing reads it aloud.
 * This one is chosen by a person, so it is allowed to collide - two handsets in one room may well
 * both be called the same thing - and whoever is displaying it disambiguates then, by falling back
 * to the half that cannot collide. If both halves were derived the same way, one collision would
 * take out both.
 *
 * The default is the name the phone already answers to elsewhere, because the person has usually
 * set that once already and it is what their file-transfer app shows them. Measured on two
 * handsets on 2026-09-13: one answered "荣耀 Magic6 Pro" and the other answered "KKG-AN00", which
 * is its model number. So the system name is a good default and a bad guarantee, and that is the
 * whole reason this is stored and editable rather than simply read.
 */
class StoredHandsetName(private val directory: File) {
    /** The name this handset was given here, or null if nobody has given it one. */
    fun read(): String? = runCatching {
        File(directory, FILE).takeIf { it.isFile }?.readText()?.let(::cleaned)
    }.getOrNull()

    /** Writes it, cleaned. A name that cleans away to nothing forgets the stored one instead. */
    fun write(name: String) {
        val keep = cleaned(name)
        if (keep == null) File(directory, FILE).delete() else File(directory, FILE).writeText(keep)
    }

    companion object {
        const val FILE = "handset-name"

        /**
         * Long enough for "Thomas 的平板", short enough to sit on one line beside a result.
         *
         * It also bounds what crosses the wire, which matters more than the layout: this is the
         * one field in the room protocol whose content a person types.
         */
        const val MAX_CHARACTERS = 24

        /**
         * Trimmed, flattened to one line, and capped - or null if nothing readable is left.
         *
         * Flattened rather than refused, because the thing that puts a newline in here is a paste
         * rather than a person, and a paste is worth keeping the readable part of. Null for empty
         * so that "no name" has one representation instead of two.
         */
        fun cleaned(name: String): String? {
            val flat = StringBuilder()
            for (character in name) {
                val safe = if (character.isWhitespace() || character.isISOControl()) ' ' else character
                if (safe == ' ' && (flat.isEmpty() || flat.last() == ' ')) continue
                flat.append(safe)
            }
            return flat.toString().trim().take(MAX_CHARACTERS).ifEmpty { null }
        }
    }
}
