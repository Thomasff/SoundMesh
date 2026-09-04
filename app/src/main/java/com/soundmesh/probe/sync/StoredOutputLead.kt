package com.soundmesh.probe.sync

import com.soundmesh.probe.PlaybackUsage
import java.io.File

/**
 * How far ahead of this handset's media output another of its outputs comes out, in microseconds.
 *
 * Not a preference and not a guess: a run measured it. Two rounds on one binary, back to back,
 * nobody touching either phone - the host on the media output landed at +0.097 ms and the same host
 * on the accessibility output landed at -19.385 ms. Nineteen and a half milliseconds, against a
 * 5 ms gate, and the only warning it ever gave was a listener saying one handset sounded "a little
 * bit faster, but you can hardly tell". At that size two speakers still fuse into one image; the
 * earlier one simply wins. Hearing is evidence that something is there, never evidence of how big.
 *
 * Per handset, because it is a property of one device's audio path rather than of the pair - the
 * standing peer correction in [StoredCalibration] cannot carry it, since that one is measured with
 * both ends on the media output and would be wrong for every other combination. A handset nobody
 * has measured reads null and corrects nothing, which is the same refusal to guess that file makes.
 *
 * The media output is the reference the others are measured against, so it leads itself by nothing
 * and is answered without reading anything.
 */
class StoredOutputLead(private val directory: File, private val usage: PlaybackUsage) {
    /** The measured lead in microseconds, or null if this output has never been measured here. */
    fun read(): Long? {
        if (usage == PlaybackUsage.MEDIA) return 0L
        val file = file()
        if (!file.isFile) return null
        return runCatching { file.readText().trim().toLong() }.getOrNull()
    }

    fun write(micros: Long) {
        file().writeText(micros.toString())
    }

    private fun file() = File(directory, "$FILE_PREFIX${usage.name}")

    companion object {
        const val FILE_PREFIX = "output-lead-us-"
    }
}
