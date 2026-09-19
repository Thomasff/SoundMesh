package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The language somebody picked reaches every component that draws or posts a string.
 *
 * An Activity's resources come from the base context it was attached to, and so do a Service's.
 * There is no androidx.appcompat here to do that, and the platform's own per-app language needs
 * API 33 while this app runs from 29, so each component puts the choice on itself. One that
 * forgets is not a crash and not a blank: it draws the phone's language while every screen around
 * it draws the chosen one, which reads as a half-translated app rather than as a bug.
 *
 * Read as source because none of it runs here: `attachBaseContext` is the framework calling into
 * a Context that the JVM has no way to build.
 */
class ChosenLanguageTest {
    private fun source(path: String) = File(path).readText(Charsets.UTF_8).replace("\r\n", "\n")

    /** Everything a person ever sees a string from. The harness screens are not on this list. */
    private val components = listOf(
        "src/main/java/com/soundmesh/product/HomeActivity.kt",
        "src/main/java/com/soundmesh/product/CalibrateActivity.kt",
        "src/main/java/com/soundmesh/product/PeerCalibrateActivity.kt",
        "src/main/java/com/soundmesh/product/StandbyService.kt",
        "src/main/java/com/soundmesh/probe/sync/ScanActivity.kt",
        "src/main/java/com/soundmesh/probe/sync/ShowCodeActivity.kt",
        "src/main/java/com/soundmesh/probe/CaptureForegroundService.kt",
        "src/main/java/com/soundmesh/session/SessionActivity.kt",
        "src/main/java/com/soundmesh/session/SessionService.kt"
    )

    @Test
    fun everyScreenAndServiceIsAttachedInTheChosenLanguage() {
        for (path in components) {
            assertTrue(
                "$path draws in the phone's language whatever was picked",
                source(path).contains("super.attachBaseContext(base.inChosenLanguage())")
            )
        }
    }

    /**
     * An explicit choice wins over the phone, and "system" leaves the phone alone.
     *
     * The second half is the one worth a test. Pinning the system's locale at start-up would look
     * identical on the day it was written and be wrong the first time somebody changed their
     * phone's language with this app still in the background.
     */
    @Test
    fun followingTheSystemPinsNothing() {
        val language = source("src/main/java/com/soundmesh/product/Language.kt")
        assertEquals(
            "the choices are no longer system, Chinese and English",
            listOf(LanguageChoice.SYSTEM, LanguageChoice.CHINESE, LanguageChoice.ENGLISH),
            LanguageChoice.entries.toList()
        )
        assertEquals("following the system is no longer a locale of nothing", null, LanguageChoice.SYSTEM.tag)
        assertTrue(
            "a choice of nothing still builds a context, pinning today's system locale",
            language.contains("val tag = choice.tag ?: return this")
        )
        assertEquals("an unreadable or absent choice is no longer the system's", LanguageChoice.SYSTEM, languageChoiceOf(null))
        assertEquals("a stored choice is not read back", LanguageChoice.ENGLISH, languageChoiceOf("ENGLISH"))
    }

    /**
     * The settings screen changes the language of the screen it is drawn on.
     *
     * Settings is a place inside the home screen rather than an activity of its own, so the usual
     * answer - restart on change - would throw somebody out of the screen they just tapped on.
     * The strings come from the composition, so re-providing the context is the whole change.
     */
    @Test
    fun thePickTakesEffectWithoutThrowingAnybodyOffTheScreen() {
        assertTrue(
            "the theme wrapper no longer carries the language, so a pick waits for a cold start",
            source("src/main/java/com/soundmesh/product/SoundMeshTheme.kt")
                .contains("CompositionLocalProvider(LocalContext provides LocalContext.current.inLanguage(language))")
        )
        assertTrue(
            "the home screen stopped handing its own choice down",
            source("src/main/java/com/soundmesh/product/HomeActivity.kt")
                .contains("SoundMeshTheme(themeChoice, language) {")
        )
    }
}
