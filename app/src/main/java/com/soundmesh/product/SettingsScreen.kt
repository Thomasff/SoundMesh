package com.soundmesh.product

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.BuildConfig
import com.soundmesh.probe.R

/**
 * The screen behind the gear in the top bar: theme, language, the diagnostic-details switch, the
 * way back to a refused permission, this handset's own output lead, and an about block.
 *
 * Drawn out of [Look.kt] like every other screen since 2026-09-15. It was the last one still made
 * of Material cards - a heading in title type over sixteen points of padding, six of them stacked -
 * and beside the rest of the app it read as a screen from a different program. What a card buys is
 * separation between things that could be confused, and nothing here can be: these are six lists of
 * one or two facts each, which is what a small grey label over a hairline is for.
 *
 * [themeChoice], [language] and [showDetails] are each written to [prefs] AND handed back up
 * through their own callback on the same tap. [HomeActivity] keeps its own copy of each as
 * composable state - the file alone would not repaint the screen already on the tab behind this
 * one, and the state alone would not survive a restart.
 */
@Composable
fun SettingsScreen(
    prefs: Preferences,
    themeChoice: ThemeChoice,
    language: LanguageChoice,
    showDetails: Boolean,
    onBack: () -> Unit,
    onThemeChanged: (ThemeChoice) -> Unit,
    onLanguageChanged: (LanguageChoice) -> Unit,
    onDetailsChanged: (Boolean) -> Unit,
    /**
     * This handset's own output lead, measured once and then looked up.
     *
     * Here rather than only on the status board, where it used to sit for ever after it had been
     * answered. A board says what still needs doing; a measurement that is done and kept is a
     * setting, and this is where settings are.
     */
    selfCalibrated: Double? = null,
    onSelfCalibrate: () -> Unit = {},
    /**
     * Opens the same screen the first launch shows, which is the only way back to a refused ask.
     *
     * Until this existed that screen was reachable exactly once per install: somebody who tapped
     * past the microphone on their first evening had no way to change their mind inside the app,
     * and every later ask happens at the moment the thing is needed - which is the worst moment
     * to be sent to a system settings page. Reported 2026-09-18.
     */
    onPermissions: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        // Nothing between blocks: a label carries its own space above it. See Look.kt.
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        PageBar(R.string.settings_title, onBack)

        // One question with three answers, drawn as one control. Three radio rows said the same
        // thing down three lines of a screen whose other lists are one line each.
        Label(R.string.settings_theme)
        Segmented(
            listOf(
                themeSegment(ThemeChoice.SYSTEM, R.string.settings_theme_system, themeChoice, prefs, onThemeChanged),
                themeSegment(ThemeChoice.LIGHT, R.string.settings_theme_light, themeChoice, prefs, onThemeChanged),
                themeSegment(ThemeChoice.DARK, R.string.settings_theme_dark, themeChoice, prefs, onThemeChanged)
            ),
            modifier = Modifier.padding(top = 2.dp)
        )

        // Drawn the same way as the theme above it, because it is the same kind of question: a
        // default that follows the handset, and two ways of saying otherwise. The two names are
        // written in their own languages - somebody who has the app in the wrong one has to be
        // able to find the way out of it without reading the language they cannot read.
        Label(R.string.settings_language)
        Segmented(
            listOf(
                languageSegment(LanguageChoice.SYSTEM, R.string.settings_language_system, language, prefs, onLanguageChanged),
                languageSegment(LanguageChoice.CHINESE, R.string.settings_language_chinese, language, prefs, onLanguageChanged),
                languageSegment(LanguageChoice.ENGLISH, R.string.settings_language_english, language, prefs, onLanguageChanged)
            ),
            modifier = Modifier.padding(top = 2.dp)
        )

        Label(R.string.settings_diagnostics)
        Line(first = true) {
            LineName(stringResource(R.string.settings_details))
            Switch(
                checked = showDetails,
                onCheckedChange = { on ->
                    prefs.write("details", if (on) "on" else "off")
                    onDetailsChanged(on)
                }
            )
        }
        Note(stringResource(R.string.settings_details_hint))

        // Between the diagnostics switch and the calibration, because it belongs with neither: it
        // is the one block here that undoes a decision made before this screen was ever reachable.
        Label(R.string.settings_permissions)
        Line(first = true) {
            LineName(stringResource(R.string.perm_title))
            Chip(stringResource(R.string.settings_permissions_open), onPermissions)
        }
        Note(stringResource(R.string.settings_permissions_hint))

        Label(R.string.settings_calibrate)
        Line(first = true) {
            LineName(stringResource(R.string.settings_self_calibrate))
            // Filled while it has never been answered, outlined once it has: this is the one
            // errand on the screen, and the rest of the rows are details. See FilledChip.
            if (selfCalibrated != null) {
                Chip(stringResource(R.string.goto_self_again), onSelfCalibrate)
            } else {
                FilledChip(stringResource(R.string.goto_self_go), onSelfCalibrate)
            }
        }
        // The number itself, because somebody who measured it once comes back here for it. Under
        // the row rather than in it: the sentence is as wide as the row's own name, and a reading
        // that squeezes out the thing it is a reading of is worse than one on its own line.
        selfCalibrated?.let {
            Note(
                stringResource(
                    R.string.goto_self_done,
                    String.format(null as java.util.Locale?, "%.1f", it)
                ),
                Tone.GOOD
            )
        }
        Note(stringResource(R.string.goto_self_hint))

        Label(R.string.about_title)
        // Always present, and not behind the details switch above: the build mark is the
        // only way to tell two installs of the same version apart without a cable, and this
        // project has burned a whole A/B round on not having it visible.
        Line(first = true) {
            LineName(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.BUILD_MARK),
                quiet = true
            )
        }
        // A fact left unset in gradle.properties reaches BuildConfig as an empty string, and
        // configured() turns that into null - null here means the row does not exist, not a
        // placeholder, which is why there is no release-page row. See configured() in
        // HomeRoute.kt.
        //
        // The link on each of the two rows below is the value, not the whole row: a row that
        // opened a browser anywhere along its width would open one on the way to scrolling.
        configured(BuildConfig.AUTHOR)?.let { author ->
            AboutRow(R.string.about_author) {
                Link(author, configured(BuildConfig.AUTHOR_URL), showGitHub = true)
            }
        }
        configured(BuildConfig.REPO_URL)?.let { url ->
            // The repository's own name rather than the address. The address is four times as
            // wide, wraps on a phone, and says nothing the name does not - and the one thing a
            // person does here is tap it, not read it out.
            AboutRow(R.string.about_repo) { Link(url.trimEnd('/').substringAfterLast('/'), url) }
        }
        configured(BuildConfig.LICENCE)?.let {
            AboutRow(R.string.about_licence) {
                Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
        }
    }
}

/** One fact in the about block: what it is called on the left, what it says on the right. */
@Composable
private fun AboutRow(@StringRes label: Int, value: @Composable RowScope.() -> Unit) {
    Line {
        LineName(stringResource(label), quiet = true)
        value()
    }
}

/**
 * A value that opens a web page, or the same value as plain text where there is no page.
 *
 * Null [url] is not an error and is not hidden: the name of the author is worth showing whether or
 * not anybody set a page for them, and a tap target that goes nowhere is worse than no tap target.
 * That is the same rule [configured] applies one level up, one level further in.
 */
@Composable
private fun RowScope.Link(text: String, url: String?, showGitHub: Boolean = false) {
    if (url == null) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val open = LocalUriHandler.current
    val colour = linkColour()
    Text(
        text,
        modifier = Modifier.clickable { runCatching { open.openUri(url) } },
        style = MaterialTheme.typography.bodyMedium,
        color = colour
    )
    if (showGitHub) {
        Icon(
            painterResource(R.drawable.ic_github),
            contentDescription = stringResource(R.string.about_github),
            modifier = Modifier
                .size(18.dp)
                .clickable { runCatching { open.openUri(url) } },
            tint = colour
        )
    }
}

/**
 * One theme choice as a cell of the segmented control.
 *
 * Writes [prefs] and calls [onThemeChanged] together; see [SettingsScreen]'s own doc for why both.
 */
@Composable
private fun themeSegment(
    choice: ThemeChoice,
    @StringRes label: Int,
    selected: ThemeChoice,
    prefs: Preferences,
    onThemeChanged: (ThemeChoice) -> Unit
) = Segment(stringResource(label), choice == selected) {
    prefs.write("theme", choice.name)
    onThemeChanged(choice)
}

/** One language choice as a cell of the segmented control. Same two writes as [themeSegment]. */
@Composable
private fun languageSegment(
    choice: LanguageChoice,
    @StringRes label: Int,
    selected: LanguageChoice,
    prefs: Preferences,
    onLanguageChanged: (LanguageChoice) -> Unit
) = Segment(stringResource(label), choice == selected) {
    prefs.write(LANGUAGE_KEY, choice.name)
    onLanguageChanged(choice)
}
