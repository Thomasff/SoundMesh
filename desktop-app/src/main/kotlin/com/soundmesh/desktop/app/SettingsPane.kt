package com.soundmesh.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/**
 * The window behind 设置 - the handset's SettingsScreen, less what a computer has no use for:
 * no permissions (Windows asks for nothing here). The words are the handset's, read from its own
 * string files.
 */
@Composable
internal fun SettingsPane(
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    language: LanguageChoice,
    onLanguage: (LanguageChoice) -> Unit,
    details: Boolean,
    onDetails: (Boolean) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(say(Phrases.settings_theme), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((choice, name) in THEME_NAMES) {
                FilterChip(selected = theme == choice, onClick = { onTheme(choice) }, label = { Text(say(name)) })
            }
        }

        Text(say(Phrases.settings_language), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((choice, name) in LANGUAGE_NAMES) {
                FilterChip(selected = language == choice, onClick = { onLanguage(choice) }, label = { Text(say(name)) })
            }
        }

        Text(say(Phrases.settings_diagnostics), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = details, onCheckedChange = onDetails)
            Text(say(Phrases.settings_details))
        }
        Text(say(Phrases.pc_details_hint))

        Text(say(Phrases.about_title), style = MaterialTheme.typography.titleSmall)
        // Always shown: the build mark is how two installs of one version are told apart.
        Text(say(Phrases.about_version, About.VERSION, About.BUILD_MARK))
        // An unset fact is an empty string and its row does not exist, as on the handset.
        About.AUTHOR.ifEmpty { null }?.let { AboutRow(say(Phrases.about_author)) { Link(it, About.AUTHOR_URL.ifEmpty { null }) } }
        About.REPO_URL.ifEmpty { null }?.let { url ->
            AboutRow(say(Phrases.about_repo)) { Link(url.trimEnd('/').substringAfterLast('/'), url) }
        }
        About.LICENCE.ifEmpty { null }?.let { AboutRow(say(Phrases.about_licence)) { Text(it) } }
    }
}

@Composable
private fun AboutRow(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp))
        value()
    }
}

/** A value that opens its page in the browser, or plain text where there is no page. */
@Composable
private fun Link(text: String, url: String?) {
    if (url == null) {
        Text(text)
        return
    }
    val open = LocalUriHandler.current
    Text(
        text,
        Modifier.clickable { runCatching { open.openUri(url) } },
        color = MaterialTheme.colorScheme.primary
    )
}

private val THEME_NAMES = listOf(
    ThemeChoice.SYSTEM to Phrases.settings_theme_system,
    ThemeChoice.LIGHT to Phrases.settings_theme_light,
    ThemeChoice.DARK to Phrases.settings_theme_dark
)

/** Each language named in itself, as on the handset: 中文 stays 中文 in an English window. */
private val LANGUAGE_NAMES = listOf(
    LanguageChoice.SYSTEM to Phrases.settings_language_system,
    LanguageChoice.CHINESE to Phrases.settings_language_chinese,
    LanguageChoice.ENGLISH to Phrases.settings_language_english
)
