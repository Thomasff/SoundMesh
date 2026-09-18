package com.soundmesh.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.BuildConfig
import com.soundmesh.probe.R

/**
 * The screen behind the gear in the top bar: theme, a language row with nothing behind it yet,
 * the diagnostic-details switch, and an about block.
 *
 * Both [themeChoice] and [showDetails] are written to [prefs] AND handed back up through
 * [onThemeChanged] / [onDetailsChanged] on the same tap. [HomeActivity] keeps its own copy of
 * each as composable state - the file alone would not repaint the screen already on the tab
 * behind this one, and the state alone would not survive a restart. See the plan's "另外两条".
 */
@Composable
fun SettingsScreen(
    prefs: Preferences,
    themeChoice: ThemeChoice,
    showDetails: Boolean,
    onBack: () -> Unit,
    onThemeChanged: (ThemeChoice) -> Unit,
    onDetailsChanged: (Boolean) -> Unit,
    /**
     * This handset's own output lead, measured once and then looked up.
     *
     * Here rather than only on the status board, where it used to sit for ever after it had been
     * answered. A board says what still needs doing; a measurement that is done and kept is a
     * setting, and this is where settings are.
     */
    selfCalibrated: Double? = null,
    onSelfCalibrate: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)

        Section(R.string.settings_theme) {
            ThemeRow(ThemeChoice.SYSTEM, R.string.settings_theme_system, themeChoice, prefs, onThemeChanged)
            ThemeRow(ThemeChoice.LIGHT, R.string.settings_theme_light, themeChoice, prefs, onThemeChanged)
            ThemeRow(ThemeChoice.DARK, R.string.settings_theme_dark, themeChoice, prefs, onThemeChanged)
        }

        // Only Chinese exists today - values-en arrives when the project is open-sourced, which
        // is a later task. An unclickable row says what is in use rather than offering a choice
        // that is not actually there yet.
        Section(R.string.settings_language) {
            Text(stringResource(R.string.settings_language_system), style = MaterialTheme.typography.bodyMedium)
        }

        Section(R.string.settings_diagnostics) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_details), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = showDetails,
                    onCheckedChange = { on ->
                        prefs.write("details", if (on) "on" else "off")
                        onDetailsChanged(on)
                    }
                )
            }
            Text(stringResource(R.string.settings_details_hint), style = MaterialTheme.typography.bodySmall)
        }

        Section(R.string.settings_calibrate) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_self_calibrate),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onSelfCalibrate) {
                    Text(stringResource(if (selfCalibrated != null) R.string.goto_self_again else R.string.goto_self_go))
                }
            }
            // The number itself, because somebody who measured it once comes back here for it.
            selfCalibrated?.let {
                Text(
                    stringResource(
                        R.string.goto_self_done,
                        String.format(null as java.util.Locale?, "%.1f", it)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(stringResource(R.string.goto_self_hint), style = MaterialTheme.typography.bodySmall)
        }

        Section(R.string.about_title) {
            // Always present, and not behind the details switch above: the build mark is the
            // only way to tell two installs of the same version apart without a cable, and this
            // project has burned a whole A/B round on not having it visible. See the plan's
            // "另外两条".
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.BUILD_MARK),
                style = MaterialTheme.typography.bodyMedium
            )
            // Author, repository and licence are all undecided as of 2026-09-15, so BuildConfig
            // hands back an empty string for each and configured() turns that into null - and
            // null here means the row does not exist, not a placeholder. See configured() in
            // HomeRoute.kt.
            configured(BuildConfig.AUTHOR)?.let {
                Text(stringResource(R.string.about_author, it), style = MaterialTheme.typography.bodyMedium)
            }
            configured(BuildConfig.REPO_URL)?.let {
                Text(stringResource(R.string.about_repo, it), style = MaterialTheme.typography.bodyMedium)
            }
            configured(BuildConfig.LICENCE)?.let {
                Text(stringResource(R.string.about_licence, it), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * One theme radio row.
 *
 * The row itself carries the selectable modifier and the [RadioButton] takes a null `onClick` -
 * the usual Compose split for a radio group - so that tapping the label is as good as tapping
 * the dot. Both write [prefs] and call [onThemeChanged] together; see [SettingsScreen]'s own doc.
 */
@Composable
private fun ThemeRow(
    choice: ThemeChoice,
    label: Int,
    selected: ThemeChoice,
    prefs: Preferences,
    onThemeChanged: (ThemeChoice) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = choice == selected) {
                prefs.write("theme", choice.name)
                onThemeChanged(choice)
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = choice == selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}
