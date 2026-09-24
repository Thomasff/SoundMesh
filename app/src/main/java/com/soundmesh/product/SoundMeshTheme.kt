package com.soundmesh.product

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

/** What somebody picked on the settings screen. [SYSTEM] is what they get without picking. */
enum class ThemeChoice { SYSTEM, LIGHT, DARK }

internal fun themeChoiceOf(stored: String?): ThemeChoice =
    ThemeChoice.entries.firstOrNull { it.name == stored } ?: ThemeChoice.SYSTEM

/**
 * Whether to draw dark, given the choice and what the phone is doing.
 *
 * An explicit choice wins in BOTH directions. A "dark" that goes light because the phone is in
 * light mode is not a setting.
 */
internal fun darkWanted(choice: ThemeChoice, systemIsDark: Boolean): Boolean = when (choice) {
    ThemeChoice.SYSTEM -> systemIsDark
    ThemeChoice.DARK -> true
    ThemeChoice.LIGHT -> false
}

/**
 * The colours ([soundMeshColours], shared with the computer's window), and the language the strings
 * under them are read in.
 *
 * [language] is here rather than at each screen's `setContent` because this is already the one
 * wrapper every screen in the app goes through, and what it does is the same shape: a choice
 * somebody made in settings, applied to the whole composition rather than to any screen.
 *
 * It defaults to [LanguageChoice.SYSTEM], which changes nothing - every component has already put
 * the stored choice on in `attachBaseContext` by the time this runs. Only [HomeActivity] passes
 * it, because that is the one screen where the choice can change while the screen is up: settings
 * is a place inside it, so answering that tap by restarting the activity would throw somebody out
 * of the screen they tapped on.
 */
@Composable
fun SoundMeshTheme(
    choice: ThemeChoice,
    language: LanguageChoice = LanguageChoice.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = darkWanted(choice, isSystemInDarkTheme())
    // The clock and the battery are drawn by the system over this app's own background, and the
    // system picks their colour from the phone's light/dark setting - which is not this app's, as
    // soon as somebody picks one on the settings screen. Left alone, dark-app-on-light-phone puts
    // black text on a black bar. Said here rather than in an activity because this is the one place
    // that knows the answer, and it is the same answer for every screen.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalContext provides LocalContext.current.inLanguage(language)) {
        MaterialTheme(colorScheme = soundMeshColours(dark), content = content)
    }
}
