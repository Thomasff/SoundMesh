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
 */
private val darkScheme = darkColorScheme(
    primary = Color(0xFFB9C4CF),
    onPrimary = Color(0xFF1B1F23),
    secondary = Color(0xFF8A949E),
    background = Color(0xFF14171A),
    onBackground = Color(0xFFE3E6E8),
    surface = Color(0xFF1C2024),
    onSurface = Color(0xFFE3E6E8),
    surfaceVariant = Color(0xFF272C31),
    onSurfaceVariant = Color(0xFFB4BBC2),
    error = Color(0xFFFF8A80)
)

private val lightScheme = lightColorScheme(
    primary = Color(0xFF3E4750),
    onPrimary = Color.White,
    secondary = Color(0xFF6B7681),
    background = Color(0xFFF7F8F9),
    onBackground = Color(0xFF14171A),
    surface = Color.White,
    onSurface = Color(0xFF14171A),
    surfaceVariant = Color(0xFFE8EBEE),
    onSurfaceVariant = Color(0xFF49525B),
    error = Color(0xFFB3261E)
)

@Composable
fun SoundMeshTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = darkWanted(choice, isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
}
