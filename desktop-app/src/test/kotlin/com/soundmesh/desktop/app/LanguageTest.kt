package com.soundmesh.desktop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The handset's rule for [LanguageChoice.SYSTEM]: Chinese stays Chinese, any other language is English. */
class LanguageTest {
    @Test
    fun aSystemInAnyLanguageButChineseIsGivenEnglish() {
        assertEquals("a Chinese system is no longer given Chinese", false, englishFor("zh"))
        for (other in listOf("en", "fr", "de", "ja", "es", "ko")) {
            assertEquals("a system in $other is not given English", true, englishFor(other))
        }
    }

    @Test
    fun anExplicitChoiceStillWinsBothWays() {
        assertEquals(false, LanguageChoice.CHINESE.english())
        assertEquals(true, LanguageChoice.ENGLISH.english())
    }
}
