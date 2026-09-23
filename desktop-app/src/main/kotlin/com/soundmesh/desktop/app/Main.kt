package com.soundmesh.desktop.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.soundmesh.desktop.HostSession
import com.soundmesh.desktop.SinkSession
import com.soundmesh.desktop.identityDirectory
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() = application {
    val host = remember { HostSession(identityDirectory()) }
    val sink = remember { SinkSession(identityDirectory()) }
    // Every call into a session goes through this one thread, in order. Two clicks in quick
    // succession would otherwise open and close the same ports from two threads at once, and
    // nothing in the sessions is written to be driven that way.
    val sessions = remember { Executors.newSingleThreadExecutor { Thread(it, "sessions").apply { isDaemon = true } } }
    val dispatcher = remember { sessions.asCoroutineDispatcher() }
    // Written to the file and held here on the same change: the file alone would not repaint the
    // windows already open, and the state alone would not outlive a restart.
    val prefs = remember { WindowPrefs(File(identityDirectory(), "window.properties")) }
    var theme by remember { mutableStateOf(themeChoiceOf(prefs.read("theme"))) }
    var details by remember { mutableStateOf(prefs.read("details") == "on") }
    var settingsOpen by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            // Given back before the process goes, so the record is withdrawn and a handset
            // standing by does not keep dialling a host that has left. Bounded, and each call on
            // its own: a session that throws or hangs on the way out must not keep the window up.
            runCatching {
                sessions.submit {
                    runCatching { host.close() }
                    runCatching { sink.stop() }
                }.get(15, TimeUnit.SECONDS)
            }
            exitApplication()
        },
        title = "SoundMesh",
        state = rememberWindowState(width = 560.dp, height = 680.dp)
    ) {
        Themed(theme) {
            SoundMeshWindow(host, sink, dispatcher, details, onOpenSettings = { settingsOpen = true })
        }
    }
    if (settingsOpen) {
        Window(
            onCloseRequest = { settingsOpen = false },
            title = "SoundMesh 设置",
            state = rememberWindowState(width = 420.dp, height = 480.dp)
        ) {
            Themed(theme) {
                SettingsPane(
                    theme,
                    onTheme = {
                        prefs.write("theme", it.name)
                        theme = it
                    },
                    details,
                    onDetails = {
                        prefs.write("details", if (it) "on" else "off")
                        details = it
                    }
                )
            }
        }
    }
}

/** Material's own light and dark, on a surface so the window's ground follows the theme too. */
@Composable
private fun Themed(theme: ThemeChoice, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(content = content)
    }
}
