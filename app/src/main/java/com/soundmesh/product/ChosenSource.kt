package com.soundmesh.product

import java.io.File

/** One song, or a folder of them. What is stored for a folder is the folder, never its contents. */
enum class ChosenKind { SONG, FOLDER }

/** Where music lives and what it is called: the two things a screen and a decoder want. */
data class Chosen(val kind: ChosenKind, val uri: String, val name: String)

/**
 * [chosen] unless this app has lost the permission to open it.
 *
 * A function of its values so it can be tested off a handset; the list it is given is whatever the
 * platform says this app still holds. The failure it exists for is quiet: permissions outlive a
 * process but not everything - a listener can revoke one, and a provider that was never asked for
 * a persistable grant hands one that dies when the process does. A song offered anyway becomes a
 * refusal at the moment somebody presses play, one screen away from anything that could explain
 * it, while the song sits in their file manager looking perfectly fine.
 */
fun stillPermitted(chosen: Chosen?, persistedUris: List<String>): Chosen? =
    chosen?.takeIf { it.uri in persistedUris }

/**
 * The song this handset will play.
 *
 * It used to be a copy. Choosing a song read the whole file into this app's directory under a fixed
 * name, which made the decoder's job a plain [File] and cost a copy of the song at the moment it
 * was chosen - and would have cost a copy of an entire folder the moment folders arrived. What is
 * kept now is the address the system handed over, held open by a persisted permission.
 *
 * One file rather than two, unlike [com.soundmesh.probe.sync.PairedHost] next door, because these
 * two are not two facts: a name with no address behind it is not a song anybody can play, and a
 * process that died between two writes would leave exactly that. Together, half a write does not
 * parse and reads as nothing chosen.
 */
class ChosenSource(private val directory: File) {
    /**
     * What was chosen, or null if nothing was - including a record only half written.
     *
     * The name is last because a URI cannot contain a raw line break and a display name can:
     * providers hand back names that people typed. A kind this build does not know reads as
     * nothing chosen, which is what a downgrade should look like.
     */
    fun chosen(): Chosen? {
        val stored = runCatching { File(directory, CHOSEN_FILE).readText() }.getOrNull() ?: return null
        val firstBreak = stored.indexOf('\n')
        if (firstBreak <= 0) return null
        val secondBreak = stored.indexOf('\n', firstBreak + 1)
        if (secondBreak < 0) return null
        val kind = ChosenKind.entries.firstOrNull { it.name == stored.substring(0, firstBreak) } ?: return null
        val uri = stored.substring(firstBreak + 1, secondBreak)
        val name = stored.substring(secondBreak + 1)
        return if (uri.isEmpty() || name.isEmpty()) null else Chosen(kind, uri, name)
    }

    fun remember(kind: ChosenKind, uri: String, displayName: String) {
        File(directory, CHOSEN_FILE).writeText("${kind.name}\n$uri\n$displayName")
    }

    fun forget() {
        runCatching { File(directory, CHOSEN_FILE).delete() }
    }

    /**
     * Removes what choosing a song used to leave behind, which is as big as the song was.
     *
     * An install that upgraded across the change from copying to addressing is holding a copy of
     * somebody's music that nothing will ever read again. Nobody would find it: it is inside this
     * app's own directory, under a name with no extension.
     */
    fun discardTheOldCopy() {
        runCatching { File(directory, LEGACY_FILE_NAME).delete() }
        runCatching { File(directory, LEGACY_NAME_FILE).delete() }
    }

    companion object {
        const val CHOSEN_FILE = "product-source-chosen"

        /** What the copy and its name were called, kept only so they can be deleted. */
        const val LEGACY_FILE_NAME = "product-source"
        const val LEGACY_NAME_FILE = "product-source-name"
    }
}
