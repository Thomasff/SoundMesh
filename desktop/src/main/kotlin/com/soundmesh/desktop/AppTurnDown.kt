package com.soundmesh.desktop

import java.io.File

/**
 * Keeps a captured program's own copy off this machine's speakers, and gives it back.
 *
 * The handset silences its media stream while it captures, since the program being captured is
 * heard there live and the room plays the same thing a lead later. Here the program's row in the
 * volume mixer is turned down to [LEVEL] of where it was instead of muted, and for a measured reason: a muted row
 * is heard muted by the capture as well (AppCaptureProbe, 2026-09-23: nothing at all), while a row
 * turned down comes back exactly that much quieter and [AppCapture.gain] undoes it with nothing
 * lost - a tone at 0.1% and ×1000 came back at 0.00 dB and 69 dB clear of everything else, the
 * same as untouched. What is left on the speakers is that tone 60 dB down.
 *
 * Written down before it is done, and read back when the host opens: Windows keeps a program's
 * level in the mixer after the program closes, so a host that died while it held one down would
 * leave that program at a thousandth the next time it was started, with nothing to say why. A
 * line that cannot be put back yet - its program is not running - stays until it can.
 */
class AppTurnDown(private val directory: File, private val mixer: AppMixer) {

    private val record: File get() = File(directory, RECORD_NAME)

    /**
     * Turns [pid] down to a thousandth of its own level and returns what its capture must be
     * multiplied by to sound as it did: [GAIN] for every program, which is what lets one capture
     * of several of them undo them all. 1 when it has no row to turn down.
     *
     * Several can be held at once. One already held is left as it is, and a program held under
     * another process - it was started again, and Windows gave it back the level this left it at -
     * is held at the level written for its name, so a level read here is never one this left
     * behind. What an earlier host left is the caller's to [putBack] first.
     */
    @Synchronized
    fun turnDown(pid: Long, name: String): Float {
        val held = read()
        if (held.any { it.pid == pid }) return GAIN
        val original = held.firstOrNull { it.name == name }?.level ?: mixer.volume(pid) ?: return 1f
        directory.mkdirs()
        record.appendText("$pid\t$original\t$name\n")
        mixer.setVolume(pid, original * LEVEL)
        return GAIN
    }

    /** Whatever was turned down back where it was. Keeps the lines that cannot be done yet. */
    @Synchronized
    fun putBack() {
        val held = read()
        if (held.isEmpty()) return
        val rows = mixer.list()
        val left = held.filterNot { line ->
            // Not in the list of programs, and not anybody's process: its row is its own.
            if (line.pid == SYSTEM_SOUNDS) return@filterNot mixer.setVolume(SYSTEM_SOUNDS, line.level)
            // The same process if it is still that program, or else whatever runs under its name now.
            val targets = rows.filter { it.pid == line.pid && it.name == line.name }
                .ifEmpty { rows.filter { it.name == line.name } }
            for (row in targets) mixer.setVolume(row.pid, line.level)
            targets.isNotEmpty()
        }
        if (left.isEmpty()) record.delete()
        else record.writeText(left.joinToString("") { "${it.pid}\t${it.level}\t${it.name}\n" })
    }

    /** Names of the programs still turned down, or waiting to be put back. */
    @Synchronized
    fun held(): List<String> = read().map { it.name }

    private data class Held(val pid: Long, val level: Float, val name: String)

    private fun read(): List<Held> {
        val text = runCatching { record.readText() }.getOrNull() ?: return emptyList()
        return text.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            val pid = parts[0].toLongOrNull() ?: return@mapNotNull null
            val level = parts[1].toFloatOrNull() ?: return@mapNotNull null
            Held(pid, level, parts[2])
        }
    }

    companion object {
        /** A thousandth: 60 dB down. */
        const val LEVEL = 0.001f

        /** What undoes [LEVEL]. */
        const val GAIN = 1f / LEVEL

        /** The process the mixer's system sounds row answers to. */
        const val SYSTEM_SOUNDS = 0L

        private const val RECORD_NAME = "turned-down.txt"
    }
}
