package com.soundmesh.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.desktop.HostPort
import com.soundmesh.desktop.HostProblem
import com.soundmesh.desktop.HostSession
import com.soundmesh.desktop.SinkSession
import com.soundmesh.desktop.SinkStage
import com.soundmesh.desktop.SinkStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

private enum class Role { HOST, SINK }

@Composable
fun SoundMeshWindow(host: HostSession, sink: SinkSession, sessions: CoroutineDispatcher) {
    var role by remember { mutableStateOf<Role?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = role == Role.HOST,
                onClick = {
                    role = Role.HOST
                    scope.launch(sessions) { sink.stop(); host.open() }
                },
                label = { Text("当主机") }
            )
            FilterChip(
                selected = role == Role.SINK,
                onClick = {
                    role = Role.SINK
                    scope.launch(sessions) { host.close() }
                },
                label = { Text("跟着放") }
            )
        }
        when (role) {
            Role.HOST -> HostPane(host, sessions)
            Role.SINK -> SinkPane(sink, sessions)
            null -> Text("这台电脑当主机，还是跟着房间里的主机放？")
        }
    }
}

@Composable
private fun HostPane(host: HostSession, sessions: CoroutineDispatcher) {
    val status = polled { host.status() } ?: return
    var file by remember { mutableStateOf<File?>(null) }
    var alsoHere by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    status.problem?.let { Text(describe(it), color = MaterialTheme.colorScheme.error) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { pickWav()?.let { file = it } }, enabled = !status.playing) { Text("选 WAV 文件") }
        Text(file?.name ?: "还没选文件")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = alsoHere, onCheckedChange = { alsoHere = it }, enabled = !status.playing)
        Text("电脑自己也出声")
    }
    if (status.playing) {
        Button(onClick = { scope.launch(sessions) { host.stopPlaying() } }) { Text("停止") }
    } else {
        Button(
            onClick = { file?.let { chosen -> scope.launch(sessions) { host.play(chosen, alsoHere) } } },
            enabled = status.open && file != null
        ) { Text("播放") }
    }
    Text("手机上的 SoundMesh 在待命时会自动跟上。")
    Text(if (status.phones.isEmpty()) "还没有手机连上" else "已连上：" + status.phones.joinToString("、"))
    Diagnostics(
        listOf(
            "音频口" to "${status.sinksOnAudio} 台在收",
            "丢块" to "${status.droppedChunks}",
            "本机接缝" to (status.localBand ?: "—"),
            "接缝拆分" to (status.localShares ?: "—")
        )
    )
}

@Composable
private fun SinkPane(sink: SinkSession, sessions: CoroutineDispatcher) {
    val status = polled { sink.status() } ?: return
    val scope = rememberCoroutineScope()
    val going = status.stage !in setOf(SinkStage.IDLE, SinkStage.NOT_FOUND, SinkStage.NO_CLOCK, SinkStage.FAILED)

    if (going) {
        Button(onClick = { scope.launch(sessions) { sink.stop() } }) { Text("停止") }
    } else {
        Button(onClick = { scope.launch(sessions) { sink.start() } }) { Text("开始") }
    }
    Text(describe(status))
    status.hostName?.let { name -> Text("跟的是：$name" + (status.hostAddress?.let { "（$it）" } ?: "")) }
    Diagnostics(
        listOf(
            "时钟偏移" to (status.offsetMillis?.let { String.format("%.3f ms", it) } ?: "—"),
            "放了" to "${status.played} 块",
            "迟到丢掉" to "${status.late} 块",
            "接缝" to (status.band ?: "—"),
            "接缝拆分" to (status.shares ?: "—")
        )
    )
}

@Composable
private fun Diagnostics(rows: List<Pair<String, String>>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "收起诊断" else "诊断") }
    if (open) {
        for ((label, value) in rows) {
            Row {
                Text(label, Modifier.width(96.dp))
                Text(value, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * A session's status, read every half second off the window's thread.
 *
 * Read rather than pushed, the way the handset's home screen reads its room: the sessions have no
 * events to push, and a host's status takes the lock a stop is holding while its stream winds down.
 */
@Composable
private fun <T> polled(read: () -> T): T? {
    var value by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            value = withContext(Dispatchers.IO) { read() }
            delay(POLL_MILLIS)
        }
    }
    return value
}

private fun pickWav(): File? {
    val dialog = FileDialog(null as Frame?, "选一个 WAV 文件", FileDialog.LOAD)
    // Windows ignores the filter; the pattern in the file name box is what filters there.
    dialog.file = "*.wav"
    dialog.filenameFilter = FilenameFilter { _, name -> name.endsWith(".wav", ignoreCase = true) }
    dialog.isVisible = true
    return dialog.files.firstOrNull()
}

private fun describe(problem: HostProblem): String = when (problem) {
    is HostProblem.PortTaken -> {
        val which = when (problem.port) {
            HostPort.COMMAND -> "命令口 TCP"
            HostPort.CLOCK -> "时钟口 UDP"
            HostPort.AUDIO -> "音频口 TCP"
        }
        "$which ${problem.number} 被占用了。是不是命令行版的主机、或者另一个 SoundMesh 还开着？"
    }
    is HostProblem.FileUnreadable -> "这个文件放不了：${problem.detail}"
    is HostProblem.SpeakersUnavailable -> "电脑的扬声器打不开：${problem.detail}"
    is HostProblem.AdvertiseFailed -> "没法在局域网里广播这台主机，手机找不到它：${problem.detail}"
    is HostProblem.PlayFailed -> "播放中断了：${problem.detail}"
}

private fun describe(status: SinkStatus): String = when (status.stage) {
    SinkStage.IDLE -> "按开始，自动找房间里的主机。"
    SinkStage.FINDING -> "正在找主机（5 秒）…"
    SinkStage.NOT_FOUND -> when (status.failure) {
        DiscoveryFailure.NO_COMPATIBLE_VERSION -> "找到了主机，但版本对不上，两边要装同一版。"
        DiscoveryFailure.AMBIGUOUS -> "房间里同时有两台主机，先关掉一台。"
        else -> "没有主机应答。主机那边开了吗？两边在同一个网络上吗？"
    }
    SinkStage.OPENING_SPEAKERS -> "正在打开扬声器…"
    SinkStage.SYNCING -> "正在和主机对时…"
    SinkStage.NO_CLOCK -> "主机没有应答时钟，什么都没放。"
    SinkStage.PLAYING -> "正在跟着放。"
    SinkStage.HOST_SILENT -> "主机没在发声，可能停了或者断了。要重新跟，按停止再按开始。"
    SinkStage.FAILED -> "出错了：${status.problem}"
}

private const val POLL_MILLIS = 500L
