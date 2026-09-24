package com.soundmesh.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
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
 * The page behind 设置 - the handset's SettingsScreen, a page of the one window as it is there,
 * less what a computer has no use for: no permissions (Windows asks for nothing here). The words
 * are the handset's, read from its own string files.
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
    onEdgeOnScreen: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    // Nothing between blocks: a label carries its own space above it, as on the handset.
    Page(say(Phrases.settings_title), onBack, spacing = 0.dp) {
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
        About.AUTHOR.ifEmpty { null }?.let { AboutRow(say(Phrases.about_author)) { Link(it, About.AUTHOR_URL.ifEmpty { null }, showGitHub = true) } }
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

/**
 * A value that opens its page in the browser, or plain text where there is no page - with
 * GitHub's mark beside it where [showGitHub] says the page is there, as on the handset.
 */
@Composable
private fun RowScope.Link(text: String, url: String?, showGitHub: Boolean = false) {
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
    if (showGitHub) {
        Icon(
            GITHUB_MARK,
            contentDescription = say(Phrases.about_github),
            modifier = Modifier.size(18.dp).clickable { runCatching { open.openUri(url) } },
            tint = linkColour()
        )
    }
}

/**
 * GitHub's own mark, used only as the label on a link to GitHub - which is what GitHub's brand
 * guidelines allow it for. The path is the handset's, app/src/main/res/drawable/ic_github.xml.
 */
private val GITHUB_MARK: ImageVector by lazy {
    ImageVector.Builder("github", 24.dp, 24.dp, viewportWidth = 16f, viewportHeight = 16f)
        .addPath(addPathNodes(GITHUB_PATH), fill = SolidColor(Color.White))
        .build()
}

private const val GITHUB_PATH =
    "M8,0C3.58,0 0,3.58 0,8c0,3.54 2.29,6.53 5.47,7.59c0.4,0.07 0.55,-0.17 0.55,-0.38c0,-0.19 -0.01,-0.82 -0.01,-1.49c-2.01,0.37 -2.53,-0.49 -2.69,-0.94c-0.09,-0.23 -0.48,-0.94 -0.82,-1.13c-0.28,-0.15 -0.68,-0.52 -0.01,-0.53c0.63,-0.01 1.08,0.58 1.23,0.82c0.72,1.21 1.87,0.87 2.33,0.66c0.07,-0.52 0.28,-0.87 0.51,-1.07c-1.78,-0.2 -3.64,-0.89 -3.64,-3.95c0,-0.87 0.31,-1.59 0.82,-2.15c-0.08,-0.2 -0.36,-1.02 0.08,-2.12c0,0 0.67,-0.21 2.2,0.82c0.64,-0.18 1.32,-0.27 2,-0.27c0.68,0 1.36,0.09 2,0.27c1.53,-1.04 2.2,-0.82 2.2,-0.82c0.44,1.1 0.16,1.92 0.08,2.12c0.51,0.56 0.82,1.27 0.82,2.15c0,3.07 -1.87,3.75 -3.65,3.95c0.29,0.25 0.54,0.73 0.54,1.48c0,1.07 -0.01,1.93 -0.01,2.2c0,0.21 0.15,0.46 0.55,0.38C13.71,14.53 16,11.53 16,8C16,3.58 12.42,0 8,0z"

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
