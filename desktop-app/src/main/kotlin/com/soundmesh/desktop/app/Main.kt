package com.soundmesh.desktop.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.soundmesh.desktop.HostSession
import com.soundmesh.desktop.SinkSession
import com.soundmesh.desktop.identityDirectory
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

fun main() = application {
    val host = remember { HostSession(identityDirectory()) }
    val sink = remember { SinkSession(identityDirectory()) }
    // Every call into a session goes through this one thread, in order. Two clicks in quick
    // succession would otherwise open and close the same ports from two threads at once, and
    // nothing in the sessions is written to be driven that way.
    val sessions = remember { Executors.newSingleThreadExecutor { Thread(it, "sessions").apply { isDaemon = true } } }
    val dispatcher = remember { sessions.asCoroutineDispatcher() }

    Window(
        onCloseRequest = {
            // Given back before the process goes, so the record is withdrawn and a handset
            // standing by does not keep dialling a host that has left.
            sessions.submit { host.close(); sink.stop() }.get()
            exitApplication()
        },
        title = "SoundMesh",
        state = rememberWindowState(width = 560.dp, height = 680.dp)
    ) {
        MaterialTheme {
            SoundMeshWindow(host, sink, dispatcher)
        }
    }
}
