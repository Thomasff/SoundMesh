package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The English resources say the same things as the Chinese ones, and say them with the same holes.
 *
 * Both halves of that matter and they fail differently. A name missing from `values-en` falls back
 * to the default, so an English phone gets one Chinese sentence in the middle of an English screen
 * and nothing anywhere reports it. A name whose format arguments do not match throws at the moment
 * the string is drawn - `%1$s` against a number, or an argument the translation dropped - which is
 * a crash on a screen nobody who wrote the translation was looking at.
 *
 * Read as XML text rather than through the resource system: there is no aapt here, and what this
 * guards is the pair of files rather than anything the app does with them.
 */
class TranslationTest {
    private fun strings(path: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\">([\\s\\S]*?)</string>")
            .findAll(File(path).readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val chinese = strings("src/main/res/values/strings.xml")
    private val english = strings("src/main/res/values-en/strings.xml")

    /** Every `%1$s`, `%2$d`, `%1$.3f` in a string, in a fixed order so two can be compared. */
    private fun arguments(text: String): List<String> =
        Regex("%(?:\\d+\\$)?[0-9.]*[sdf]").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyStringHasAnEnglishOne() {
        val missing = chinese.keys - english.keys
        assertTrue("no English for: ${missing.joinToString(", ")}", missing.isEmpty())
    }

    /**
     * And nothing is in English that is not in the default.
     *
     * A name only `values-en` has is one nothing can read: the id comes from the default set, so a
     * string that exists in one language and not in the other is a translation of nothing.
     */
    @Test
    fun nothingIsTranslatedThatDoesNotExist() {
        val orphans = english.keys - chinese.keys
        assertTrue("English only: ${orphans.joinToString(", ")}", orphans.isEmpty())
    }

    @Test
    fun theTwoAgreeOnWhatEachStringIsGiven() {
        for ((name, text) in chinese) {
            english[name]?.let {
                assertEquals("$name takes different arguments in English", arguments(text), arguments(it))
            }
        }
    }

    /**
     * The two names for the languages are written in their own languages, in both files.
     *
     * Somebody who has the app in a language they cannot read has to be able to find the way out
     * of it, and the only thing on that screen they can be sure of reading is the name of their
     * own language. Translating "English" into Chinese and "中文" into English would leave exactly
     * one person unable to use the control: the one it is there for.
     */
    @Test
    fun theLanguageNamesAreTheSameInBothLanguages() {
        for (name in listOf("settings_language_chinese", "settings_language_english")) {
            assertEquals("$name is translated", chinese[name], english[name])
        }
    }
}
