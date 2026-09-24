package com.soundmesh.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundmesh.product.Label
import com.soundmesh.product.Line
import com.soundmesh.product.LineName
import com.soundmesh.product.Note
import com.soundmesh.product.Segment
import com.soundmesh.product.Segmented
import com.soundmesh.product.linkColour

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
    onDetails: (Boolean) -> Unit,
    edgeOnScreen: Boolean,
    onEdgeOnScreen: (Boolean) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
        // Nothing between blocks: a label carries its own space above it, as on the handset.
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Label(say(Phrases.settings_theme))
        Segmented(
            THEME_NAMES.map { (choice, name) -> Segment(say(name), theme == choice) { onTheme(choice) } },
            modifier = Modifier.padding(top = 2.dp)
        )

        Label(say(Phrases.settings_language))
        Segmented(
            LANGUAGE_NAMES.map { (choice, name) -> Segment(say(name), language == choice) { onLanguage(choice) } },
            modifier = Modifier.padding(top = 2.dp)
        )

        // Only a computer asks: a handset's window is its screen.
        Label(say(Phrases.pc_edge_title))
        Segmented(
            listOf(
                Segment(say(Phrases.pc_edge_window), !edgeOnScreen) { onEdgeOnScreen(false) },
                Segment(say(Phrases.pc_edge_screen), edgeOnScreen) { onEdgeOnScreen(true) }
            ),
            modifier = Modifier.padding(top = 2.dp)
        )
        Note(say(Phrases.pc_edge_hint))

        Label(say(Phrases.settings_diagnostics))
        Line(first = true) {
            LineName(say(Phrases.settings_details))
            Switch(checked = details, onCheckedChange = onDetails)
        }
        Note(say(Phrases.pc_details_hint))

        Label(say(Phrases.about_title))
        // Always shown: the build mark is how two installs of one version are told apart.
        Line(first = true) {
            LineName(say(Phrases.about_version, About.VERSION, About.BUILD_MARK), quiet = true)
        }
        // An unset fact is an empty string and its row does not exist, as on the handset.
        About.AUTHOR.ifEmpty { null }?.let { AboutRow(say(Phrases.about_author)) { Link(it, About.AUTHOR_URL.ifEmpty { null }) } }
        About.REPO_URL.ifEmpty { null }?.let { url ->
            AboutRow(say(Phrases.about_repo)) { Link(url.trimEnd('/').substringAfterLast('/'), url) }
        }
        About.LICENCE.ifEmpty { null }?.let {
            AboutRow(say(Phrases.about_licence)) {
                Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
        }
    }
}

/** One fact in the about block: what it is called on the left, what it says on the right. */
@Composable
private fun AboutRow(label: String, value: @Composable RowScope.() -> Unit) {
    Line {
        LineName(label, quiet = true)
        value()
    }
}

/** A value that opens its page in the browser, or plain text where there is no page. */
@Composable
private fun Link(text: String, url: String?) {
    if (url == null) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val open = LocalUriHandler.current
    Text(
        text,
        Modifier.clickable { runCatching { open.openUri(url) } },
        style = MaterialTheme.typography.bodyMedium,
        color = linkColour()
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
