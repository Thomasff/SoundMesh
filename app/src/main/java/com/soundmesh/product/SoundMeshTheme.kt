package com.soundmesh.product

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
 * Near-neutral on purpose, and this is a constraint rather than a taste.
 *
 * [BadgePalette] hands out twelve hues that go all the way round the circle - red, orange, yellow,
 * lime, green, teal, cyan, sky, blue, purple, magenta, pink - and they are the app's way of saying
 * which handset is which across a room. An accent colour of ANY hue collides with one of them. So
 * the app's own furniture is graphite and slate, and the only saturated things on the screen are
 * the phones.
 *
 * Warmed on 2026-09-15 to the greys the drawing was made in: the ground is a warm off-white rather
 * than a blue-grey one, and panels are plain white on top of it. Same constraint, different
 * temperature - the point was never that the furniture be cold, only that it not be a hue.
 */
private val darkScheme = darkColorScheme(
    primary = Color(0xFFDCDBD7),
    onPrimary = Color(0xFF1A1C1F),
    secondary = Color(0xFFB0B1AE),
    background = Color(0xFF16171A),
    onBackground = Color(0xFFE9E8E5),
    surface = Color(0xFF1C1D20),
    onSurface = Color(0xFFE9E8E5),
    // Doing the work --line does in the drawing: it is what every hairline and every quiet edge
    // is drawn in, so it has to sit just off the surface rather than being a panel of its own.
    surfaceVariant = Color(0xFF2E3033),
    onSurfaceVariant = Color(0xFF93948F),
    error = Color(0xFFD98A82)
)

private val lightScheme = lightColorScheme(
    primary = Color(0xFF24282E),
    onPrimary = Color(0xFFF7F6F4),
    secondary = Color(0xFF43464B),
    background = Color(0xFFF7F6F4),
    onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFE3E2DF),
    onSurfaceVariant = Color(0xFF75787E),
    error = Color(0xFFA83A32)
)

@Composable
fun SoundMeshTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = darkWanted(choice, isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
}
