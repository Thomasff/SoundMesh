package com.soundmesh.desktop

import java.io.File

/**
 * Keeps a captured program's own copy off this machine's speakers, and gives it back.
 *
 * The handset silences its media stream while it captures, since the program being captured is
 * heard there live and the room plays the same thing a lead later. Here the program's row in the
 * volume mixer is turned down to [LEVEL] instead of muted, and for a measured reason: a muted row
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
     * Turns [pid] down and returns what its capture must be multiplied by to sound as it did. 1
     * when it has no row to turn down.
     *
     * Anything still held is put back first, so a level read here is never one this left behind.
     */
    fun turnDown(pid: Long, name: String): Float {
        putBack()
        val original = mixer.volume(pid) ?: return 1f
        directory.mkdirs()
        record.appendText("$pid\t$original\t$name\n")
        mixer.setVolume(pid, LEVEL)
        return original / LEVEL
    }

    /** Whatever was turned down back where it was. Keeps the lines that cannot be done yet. */
    fun putBack() {
        val held = read()
        if (held.isEmpty()) return
        val rows = mixer.list()
        val left = held.filterNot { line ->
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

        private const val RECORD_NAME = "turned-down.txt"
    }
}
