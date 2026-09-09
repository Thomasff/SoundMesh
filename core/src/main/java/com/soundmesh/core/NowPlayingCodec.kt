package com.soundmesh.core

/**
 * Wire format for the one thing about a song a sink cannot work out for itself: its name.
 *
 * A sink is handed instants and samples and nothing else - deliberately, because everything it has
 * to do per chunk is already in the chunk. The name is not that: it changes once a song, it is
 * only ever read by a person, and a handset across the room showing which song is playing is the
 * difference between a room that is working and a room that is merely making noise.
 *
 * It travels on the rule channel rather than with the audio, because that channel already knows
 * who each handset is, already remembers what to tell one that joins late, and is nowhere near the
 * timing. What it does not already have is more than one kind of message, which is what the magic
 * line below is for: a reader that does not know this one counts it as unreadable and carries on,
 * so a handset on an older build loses the name and keeps the music.
 *
 * The name is the rest of the line, spaces and all, and a newline in it is refused rather than
 * escaped - a song whose name spans lines would be indistinguishable from a truncated message, and
 * the file names this is built from cannot contain one.
 */
object NowPlayingCodec {
    const val MAGIC = "soundmesh-playing"
    const val VERSION = 1

    fun encode(name: String): String {
        require(name.isNotEmpty()) { "a song that is playing has a name" }
        require(name.none { it == '\n' || it == '\r' }) { "a song name is one line: $name" }
        return "$MAGIC $VERSION $name"
    }

    /** True when [text] is one of these rather than some other message on the same channel. */
    fun looksLikeOne(text: String): Boolean = text.startsWith("$MAGIC ")

    /**
     * The name in [text], or throws.
     *
     * Throwing rather than returning null for the same reason [SpatialFieldCodec] does: the caller
     * that reads this channel has one place where an unreadable message is counted, and a null
     * would be a second, quieter one.
     */
    fun decode(text: String): String {
        val parts = text.split(' ', limit = 3)
        require(parts.size == 3) { "a now-playing message is a magic, a version and a name" }
        require(parts[0] == MAGIC) { "not a now-playing message: ${parts[0]}" }
        require(parts[1].toIntOrNull() == VERSION) { "unknown now-playing version: ${parts[1]}" }
        require(parts[2].isNotEmpty()) { "a song that is playing has a name" }
        return parts[2]
    }
}
