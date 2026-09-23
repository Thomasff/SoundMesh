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
 * no language (the window is Chinese only), no permissions (Windows asks for nothing here).
 */
@Composable
internal fun SettingsPane(
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    details: Boolean,
    onDetails: (Boolean) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("主题", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((choice, name) in THEME_NAMES) {
                FilterChip(selected = theme == choice, onClick = { onTheme(choice) }, label = { Text(name) })
            }
        }

        Text("诊断", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = details, onCheckedChange = onDetails)
            Text("显示诊断细节")
        }
        Text("主窗口里的计数、时钟偏移和接缝这些数。平时用不上，出问题时有用。")

        Text("关于", style = MaterialTheme.typography.titleSmall)
        // Always shown: the build mark is how two installs of one version are told apart.
        Text("版本 ${About.VERSION} · 构建 ${About.BUILD_MARK}")
        // An unset fact is an empty string and its row does not exist, as on the handset.
        About.AUTHOR.ifEmpty { null }?.let { AboutRow("作者") { Link(it, About.AUTHOR_URL.ifEmpty { null }) } }
        About.REPO_URL.ifEmpty { null }?.let { url ->
            AboutRow("项目仓库") { Link(url.trimEnd('/').substringAfterLast('/'), url) }
        }
        About.LICENCE.ifEmpty { null }?.let { AboutRow("许可") { Text(it) } }
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
    ThemeChoice.SYSTEM to "跟随系统",
    ThemeChoice.LIGHT to "浅色",
    ThemeChoice.DARK to "深色"
)
