package com.soundmesh.probe.sync

import java.io.File

/**
 * The song a person already waited for, held between choosing it and pressing play.
 *
 * Picking a song decodes the whole of it, to find out that it plays at all: a refusal discovered
 * when the service failed to start would put the reason three layers from the moment somebody
 * chose the file. That decode was then thrown away, and pressing play decoded the same file again
 * - about fifteen seconds for a seven minute song, paid every single time, for an answer this
 * process already had.
 *
 * In memory rather than on disk, and that is the whole design. The decoded audio is seventy-odd
 * megabytes for a long song; written out it would be a file to keep consistent, to invalidate, and
 * to find half written after the process died. Held here it cannot outlive the decode that filled
 * it, so the only stale reading it can give is one this process created itself - which the checks
 * below rule out - and losing it to a process that Android killed simply costs the decode that
 * would have been paid anyway.
 *
 * Nothing here is a cache of songs. One song is held, because one song is chosen.
 */
object DecodedSong {
    private class Held(
        val path: String,
        val bytes: Long,
        val modifiedAt: Long,
        /** Sixty seconds or the whole song, which are different audio and different arms of a run. */
        val whole: Boolean,
        val source: FileChunkSource
    )

    @Volatile private var held: Held? = null

    /** Remembers [source] as the decoding of [file], replacing whatever was held. */
    fun keep(file: File, whole: Boolean, source: FileChunkSource) {
        held = Held(file.absolutePath, file.length(), file.lastModified(), whole, source)
    }

    /**
     * A reader over the decoding of [file], or null if what is held is not that.
     *
     * A fresh reader every time, never the object that was kept: [FileChunkSource] carries a
     * position that wraps, so the same object handed to a second session would start it wherever
     * the first one stopped.
     *
     * The file is checked by name, length and modification time rather than trusted. What forbids
     * a stale answer in practice is [forget] being called before a new song is copied in, but that
     * is one statement in another file, and the failure if it ever moves is the wrong song playing
     * with nothing to say so.
     */
    fun take(file: File, whole: Boolean): FileChunkSource? {
        val kept = held ?: return null
        if (kept.whole != whole) return null
        if (kept.path != file.absolutePath) return null
        if (kept.bytes != file.length() || kept.modifiedAt != file.lastModified()) return null
        return kept.source.rewound()
    }

    /** Drops what is held, so nothing can be handed out for a file that is about to be replaced. */
    fun forget() {
        held = null
    }
}
