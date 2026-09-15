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
