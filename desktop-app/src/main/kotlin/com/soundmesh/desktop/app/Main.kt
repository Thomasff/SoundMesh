package com.soundmesh.desktop.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.soundmesh.desktop.HostSession
import com.soundmesh.desktop.SinkSession
import com.soundmesh.desktop.identityDirectory
import com.soundmesh.product.soundMeshColours
import kotlinx.coroutines.asCoroutineDispatcher
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() = application {
    // A round's recording stays only while the details switch is on, as the sink's does below.
    val host = remember {
        HostSession(identityDirectory(), keepsRecordings = {
            WindowPrefs(File(identityDirectory(), "window.properties")).read("details") == "on"
        })
    }
    // A measuring round's recording stays on disk only while the details switch is on, as the
    // handset keeps its own (keepsRecordings = wantsDetails). Read from the file at each round.
    val sink = remember {
        SinkSession(identityDirectory(), keepsRecordings = {
            WindowPrefs(File(identityDirectory(), "window.properties")).read("details") == "on"
        })
    }
    // Every call into a session goes through this one thread, in order. Two clicks in quick
    // succession would otherwise open and close the same ports from two threads at once, and
    // nothing in the sessions is written to be driven that way.
    val sessions = remember { Executors.newSingleThreadExecutor { Thread(it, "sessions").apply { isDaemon = true } } }
    val dispatcher = remember { sessions.asCoroutineDispatcher() }
    // Written to the file and held here on the same change: the file alone would not repaint the
    // windows already open, and the state alone would not outlive a restart.
    val prefs = remember { WindowPrefs(File(identityDirectory(), "window.properties")) }
    var theme by remember { mutableStateOf(themeChoiceOf(prefs.read("theme"))) }
    // Off on every start, as on the handset since 2026-09-28: written back rather than only held,
    // because the two sessions above read the file for whether to keep a recording.
    var details by remember {
        prefs.write("details", "off")
        mutableStateOf(false)
    }
    var language by remember { mutableStateOf(languageChoiceOf(prefs.read("language"))) }
    // 设置's 边缘光. Not written to the file: every start draws the light round the window, and
    // round the whole screen only for as long as somebody asked this time.
    var edgeOnScreen by remember { mutableStateOf(false) }
    var look by remember { mutableStateOf<EdgeLook?>(null) }
    // The screen the window is on, which is the one the light goes round.
    var screen by remember { mutableStateOf<Rectangle?>(null) }

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
        icon = AppIcon.painter,
        state = rememberWindowState(width = 560.dp, height = 680.dp)
    ) {
        DisposableEffect(window) {
            val follow = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) {
                    screen = window.graphicsConfiguration.bounds
                }
            }
            screen = window.graphicsConfiguration.bounds
            window.addComponentListener(follow)
            onDispose { window.removeComponentListener(follow) }
        }
        Themed(theme, language, window) {
            SoundMeshWindow(
                host, sink, dispatcher, details,
                settings = { onBack ->
                    SettingsPane(
                        theme,
                        onTheme = {
                            prefs.write("theme", it.name)
                            theme = it
                        },
                        language,
                        onLanguage = {
                            prefs.write("language", it.name)
                            language = it
                        },
                        details,
                        onDetails = {
                            prefs.write("details", if (it) "on" else "off")
                            details = it
                        },
                        edgeOnScreen,
                        onEdgeOnScreen = { edgeOnScreen = it },
                        onBack
                    )
                },
                look = look,
                onLook = { look = it },
                edgeOnScreen = edgeOnScreen
            )
        }
    }
    if (edgeOnScreen) {
        screen?.let { ScreenEdges(look, { if (look?.sink == true) sink.loudness() else host.loudness() }, it) }
    }
}

/**
 * The handset's colours, on its ground so the window's ground follows the theme too - title bar
 * included, see [TitleBar] - and the language every phrase below is said in. Also where each
 * window gets the icon, in all its sizes: see [AppIcon].
 */
@Composable
private fun Themed(theme: ThemeChoice, language: LanguageChoice, window: ComposeWindow, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val colours = soundMeshColours(dark)
    LaunchedEffect(window) { AppIcon.dress(window) }
    LaunchedEffect(window, dark) { TitleBar.paint(window, colours.background, colours.onBackground, dark) }
    CompositionLocalProvider(LocalEnglish provides language.english()) {
        MaterialTheme(colorScheme = colours) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
