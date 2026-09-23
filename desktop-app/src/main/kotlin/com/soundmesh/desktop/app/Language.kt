package com.soundmesh.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** One thing the window says, in the two languages it says things in. See the phrases task. */
internal class Phrase(val zh: String, val en: String) {
    /**
     * The text, formatted only when there is something to put in it - as the handset's getString
     * does, which leaves a `%%` alone when it is asked for a string with no arguments.
     */
    fun of(english: Boolean, vararg args: Any?): String {
        val text = if (english) en else zh
        return if (args.isEmpty()) text else String.format(Locale.ROOT, text, *args)
    }
}

/**
 * The handset's LanguageChoice. An explicit choice wins in both directions, as there.
 *
 * [SYSTEM] follows the handset's resource rule rather than guessing better: its default strings
 * are the Chinese ones and only an English system is given English, so a French Windows gets
 * Chinese here as a French phone does.
 */
internal enum class LanguageChoice { SYSTEM, CHINESE, ENGLISH }

internal fun languageChoiceOf(stored: String?): LanguageChoice =
    LanguageChoice.entries.firstOrNull { it.name == stored } ?: LanguageChoice.SYSTEM

internal fun LanguageChoice.english(): Boolean = when (this) {
    LanguageChoice.SYSTEM -> Locale.getDefault().language == "en"
    LanguageChoice.CHINESE -> false
    LanguageChoice.ENGLISH -> true
}

/** Whether what is drawn below is in English, provided once per window. */
internal val LocalEnglish = staticCompositionLocalOf { false }

@Composable
internal fun say(phrase: Phrase, vararg args: Any?): String = phrase.of(LocalEnglish.current, *args)
