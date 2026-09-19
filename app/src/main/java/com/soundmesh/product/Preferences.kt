package com.soundmesh.product

import java.io.File

/**
 * The handful of choices somebody made on the settings screen.
 *
 * A file rather than SharedPreferences because that is what everything else durable in this app
 * is - HostIdentity, StoredRoomDrawing, StoredListenerDistance all live in filesDir - and one
 * storage mechanism is easier to reason about at three in the morning than two.
 *
 * Read on every call rather than cached: these are read when a screen opens and written when a
 * person taps, which is a handful of times an hour, and a cache is a second copy that can be
 * wrong.
 */
class Preferences(private val dir: File) {
    private val file: File get() = File(dir, FILE_NAME)

    fun read(key: String): String? = lines()[key]

    fun write(key: String, value: String) {
        val kept = lines().toMutableMap()
        kept[key] = value
        file.writeText(kept.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    /**
     * Split on the FIRST separator only, so a value may contain one. A line without a separator
     * is dropped rather than thrown on: a kill part-way through a write leaves exactly that, and
     * a settings screen that crashes on opening is worse than one that has forgotten a choice.
     */
    private fun lines(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readLines()
                .mapNotNull { line ->
                    val at = line.indexOf('=')
                    if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    companion object {
        /**
         * Which network this handset puts in the code it shows, when it has two to choose from.
         *
         * Stored rather than worked out, because it cannot be worked out: the app has no way to
         * know which network the phone doing the scanning can see. One of LocalAddress.ReachedBy
         * by name, or absent when nobody has said and the code refuses to guess.
         */
        const val CODE_NETWORK = "code_network"

        private const val FILE_NAME = "preferences"
    }
}

/**
 * Whether somebody has asked this handset to show its internals.
 *
 * The same switch the settings screen writes, read from the places that are not a screen and have
 * no [Preferences] of their own to hand. One reader draws counters on the home screen; the two
 * below it here are about what a handset does rather than what it draws, and both are things a
 * person who never turned this on has no way to use and no reason to carry.
 */
internal fun wantsDetails(filesDir: File): Boolean =
    Preferences(filesDir).read("details") == "on"

/**
 * Whether this handset keeps the audio a calibration recorded, once the analysis has read it.
 *
 * Tied to the diagnostics switch because that switch is how somebody says they want the
 * internals, and a recording is the most internal thing a calibration produces: a few megabytes
 * per case, readable only over adb, and the only evidence left on the handset if a calibration
 * lands on a number that cannot be right. Somebody who never turned it on has no way to open
 * them and no reason to carry them.
 *
 * The harness is not on this path. It drives SyncActivity with its own case ids and its own
 * CalibrationRunner, and it exports the audio itself, so what it records is untouched by this.
 */
internal fun keepsRecordings(filesDir: File): Boolean = wantsDetails(filesDir)
