package com.soundmesh.desktop.app

import java.io.File
import java.util.Properties

/** Light, dark, or whatever Windows is set to - the handset's ThemeChoice. */
internal enum class ThemeChoice { SYSTEM, LIGHT, DARK }

internal fun themeChoiceOf(stored: String?): ThemeChoice =
    ThemeChoice.entries.firstOrNull { it.name == stored } ?: ThemeChoice.SYSTEM

/**
 * The window's own choices - the theme and the diagnostics switch - kept in a file beside this
 * machine's identity so they outlive a restart, as the handset's Preferences do.
 *
 * A file that cannot be read or written costs the choice, not the window: it is read as empty and
 * a failed write is dropped.
 */
internal class WindowPrefs(private val file: File) {
    private val values = Properties().apply { runCatching { file.inputStream().use(::load) } }

    fun read(key: String): String? = values.getProperty(key)

    fun write(key: String, value: String) {
        values.setProperty(key, value)
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { values.store(it, null) }
        }
    }
}
