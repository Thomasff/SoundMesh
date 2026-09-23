package com.soundmesh.desktop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundmesh.core.BadgeHues
import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.core.PeerBadge
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import com.soundmesh.product.EffectKind
import com.soundmesh.product.RoomState
import com.soundmesh.product.SpatialRoom
import com.soundmesh.desktop.AudioSession
import com.soundmesh.desktop.AudioSessions
import com.soundmesh.desktop.HostPort
import com.soundmesh.desktop.PairingCodeModules
import com.soundmesh.desktop.HostProblem
import com.soundmesh.desktop.HostSession
import com.soundmesh.desktop.HostStatus
import com.soundmesh.desktop.Playhead
import com.soundmesh.desktop.SinkSession
import com.soundmesh.desktop.SinkStage
import com.soundmesh.desktop.SinkStatus
import com.soundmesh.desktop.SoftwareVolume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.math.roundToInt

private enum class Role { HOST, SINK }

@Composable
fun SoundMeshWindow(host: HostSession, sink: SinkSession, sessions: CoroutineDispatcher) {
    var role by remember { mutableStateOf<Role?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        // Scrolls: a roster and a volume line per device outgrow the window in a room of a few.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
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
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    // A program's sound instead of files; picking either one puts the other down.
    var app by remember { mutableStateOf<AudioSession?>(null) }
    var programs by remember { mutableStateOf<List<AudioSession>?>(null) }
    var picked by remember { mutableStateOf("还没选歌") }
    var alsoHere by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    status.problem?.let { Text(describe(it), color = MaterialTheme.colorScheme.error) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = {
            pickSongs().takeIf { it.isNotEmpty() }?.let {
                files = it
                app = null
                picked = if (it.size == 1) it.single().name else "${it.size} 首"
            }
        }, enabled = !status.playing) { Text("选文件") }
        OutlinedButton(onClick = {
            pickFolder()?.let { folder ->
                val songs = songsIn(folder)
                files = songs
                app = null
                picked = if (songs.isEmpty()) "「${folder.name}」里没有能放的歌" else "「${folder.name}」里 ${songs.size} 首"
            }
        }, enabled = !status.playing) { Text("选文件夹") }
        OutlinedButton(onClick = {
            // Asked afresh each time: the mixer's rows come and go with what is playing.
            scope.launch { programs = withContext(sessions) { runCatching { AudioSessions.list() }.getOrDefault(emptyList()) } }
        }, enabled = !status.playing) { Text("抓程序的声音") }
        Text(picked)
    }
    programs?.let { list ->
        if (list.isEmpty()) Text("现在没有程序在音量合成器里出声。先让要抓的程序放起来，再点一次。")
        else Text("抓哪个程序的声音？")
        // One to a line: a browser, a player and a call can all be in the mixer at once.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (program in list) {
                OutlinedButton(onClick = {
                    app = program
                    files = emptyList()
                    programs = null
                    picked = "「${program.name}」的声音"
                }) { Text(program.name + if (program.playing) "（正在出声）" else "") }
            }
            TextButton(onClick = { programs = null }) { Text("算了") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = alsoHere, onCheckedChange = { alsoHere = it }, enabled = !status.playing)
        Text("电脑自己也出声")
    }
    if (status.playing) {
        Button(onClick = { scope.launch(sessions) { host.stopPlaying() } }) { Text("停止") }
        status.playhead?.let { Transport(host, it, status.paused, sessions) }
        status.capturing?.let {
            Text("房间在放「$it」的声音。它在这台电脑上的音量先调到了千分之一，免得比房间早一秒半响出来；停止后调回原样。")
        }
    } else {
        Button(
            onClick = {
                val program = app
                if (program != null) scope.launch(sessions) { host.playApp(program.pid, program.name, alsoHere) }
                else files.takeIf { it.isNotEmpty() }?.let { chosen -> scope.launch(sessions) { host.play(chosen, 0, alsoHere) } }
            },
            enabled = status.open && (files.isNotEmpty() || app != null)
        ) { Text("播放") }
    }
    if (status.heldDown.isNotEmpty()) {
        Text(
            "「${status.heldDown.joinToString("、")}」在音量合成器里还被调低着，它再运行时这里会调回来；" +
                "也可以在 Windows 音量合成器里自己调回。",
            color = MaterialTheme.colorScheme.error
        )
    }
    if (status.skipped.isNotEmpty()) {
        Text("跳过了放不了的：" + status.skipped.joinToString("；"), color = MaterialTheme.colorScheme.error)
    }
    if (status.ended) Text("放完了。")
    Text("手机上的 SoundMesh 在待命时会自动跟上。")
    val addresses = remember { ownAddresses() }
    if (addresses.isNotEmpty()) Text("另一台电脑找不到这里时，填：" + addresses.joinToString("、") { it.address })
    PairCode(host, status.open, addresses, sessions)
    Roster(status)
    HostDrawing(host, status.room, sessions)
    Effects(host, status.room, sessions)
    Volumes(host, status, sessions)
    Diagnostics(
        listOf(
            "音频口" to "${status.sinksOnAudio} 台在收",
            "丢块" to "${status.droppedChunks}",
            "抓声垫静音" to (status.capturePadded?.let { "$it 块" } ?: "—"),
            "本机接缝" to (status.localBand ?: "—"),
            "接缝拆分" to (status.localShares ?: "—")
        )
    )
}

@Composable
private fun SinkPane(sink: SinkSession, sessions: CoroutineDispatcher) {
    val status = polled { sink.status() } ?: return
    val scope = rememberCoroutineScope()
    val going = status.stage !in setOf(SinkStage.IDLE, SinkStage.NOT_FOUND, SinkStage.FAILED)

    var address by remember { mutableStateOf("") }

    if (going) {
        Button(onClick = { scope.launch(sessions) { sink.stop() } }) { Text("停止") }
    } else {
        Button(onClick = { scope.launch(sessions) { sink.start() } }) { Text("开始") }
        // The way in when discovery finds nothing: the handset scans the host's code there, and
        // this machine has no camera to scan with. A host given this way is not looked for again
        // if it moves - it has no identity to be recognised by.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text("找不到时填主机地址") },
                placeholder = { Text("比如 192.168.0.150") },
                singleLine = true
            )
            OutlinedButton(
                onClick = { scope.launch(sessions) { sink.start(address) } },
                enabled = address.isNotEmpty()
            ) { Text("连这个地址") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge(status.selfId, status.selfPlace)
        Text("本机在主机名单上是 ${PeerBadge.numberOf(status.selfId)} 号")
    }
    if (status.volumePercent != SoftwareVolume.FULL) {
        Text("主机把这台的 SoundMesh 音量调到了 ${status.volumePercent}%（电脑的系统音量没动）。")
    }
    Text(describe(status))
    status.hostName?.let { name -> Text("跟的是：$name" + (status.hostAddress?.let { "（$it）" } ?: "")) }
    status.room?.let { room ->
        Text("房间", style = MaterialTheme.typography.titleSmall)
        RoomDrawing(room, actions = null)
        Text("图是主机那边摆的，这里只能看。带外圈的是本机。")
    }
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

/**
 * Every device in the room, one line each, this machine first - the handset host's roster. The
 * badge is on every line here because this window has no drawing of the room to find a colour in.
 */
@Composable
private fun Roster(status: HostStatus) {
    Text("房间里 ${status.phones.size + 1} 台", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        status.selfId?.let { Badge(it, status.selfPlace) }
        Text("本机（主机）")
    }
    if (status.phones.isEmpty()) Text("还没有手机连上。手机选「当从机」就会自己连过来。")
    for (phone in status.phones) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(phone.peerId, phone.place, hollow = phone.quiet || phone.stopped)
            Text(phone.name)
        }
        // Quiet first: a quiet one may still be taking the audio, and "not taking it" would be false.
        val note = when {
            phone.quiet -> "有一阵子没通信了，可能是息屏了"
            phone.stopped -> "没在收音频，可能掉线了"
            else -> null
        }
        note?.let { Text(it, Modifier.padding(start = 32.dp), color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * 上一首 / 暂停 / 下一首 and where in the song the room is - the handset host's transport row and
 * playhead. Every one of them lands a lead later in the room: the queued audio is thrown away and
 * the new place starts a second and a half on.
 */
@Composable
private fun Transport(host: HostSession, playhead: Playhead, paused: Boolean, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    Text("第 ${playhead.song + 1}/${playhead.songs} 首：${playhead.name}")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { scope.launch(sessions) { host.stepSong(-1) } }, enabled = playhead.song > 0) { Text("上一首") }
        OutlinedButton(onClick = { scope.launch(sessions) { host.setPaused(!paused) } }) { Text(if (paused) "继续" else "暂停") }
        OutlinedButton(onClick = { scope.launch(sessions) { host.stepSong(1) } }, enabled = playhead.song < playhead.songs - 1) { Text("下一首") }
    }
    KnobLine(
        "进度",
        playhead.heardMillis.toFloat().coerceAtMost(playhead.durationMillis.toFloat()),
        0f..playhead.durationMillis.toFloat().coerceAtLeast(1f),
        "${clock(playhead.heardMillis)} / ${clock(playhead.durationMillis)}"
    ) { scope.launch(sessions) { host.seekTo(it.toLong()) } }
}

private fun clock(millis: Long): String {
    val seconds = millis / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * The room drawing, dragged here and sent to the host as it moves.
 *
 * Drawn from what was just done until the host's own status catches up: the status is read twice
 * a second, and an icon that followed the mouse only that often would lag a drag by half a second.
 */
@Composable
private fun HostDrawing(host: HostSession, room: RoomState, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    var local by remember { mutableStateOf<RoomState?>(null) }
    var touchedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(touchedAt) {
        delay(LOCAL_HOLD_MILLIS)
        local = null
    }
    fun change(edit: (RoomState) -> RoomState, send: HostSession.() -> Unit) {
        local = edit(local ?: room)
        touchedAt = System.nanoTime()
        scope.launch(sessions) { host.send() }
    }
    val shown = local?.copy(colours = room.colours, silentIds = room.silentIds) ?: room
    Text("房间", style = MaterialTheme.typography.titleSmall)
    RoomDrawing(
        shown,
        DrawingActions(
            moveIcon = { icon ->
                change({ r -> r.copy(icons = r.icons.map { if (it.peerId == icon.peerId) icon else it }) }) { moveIcon(icon) }
            },
            moveSource = { spot ->
                change({ it.copy(pan = SpatialRoom.panOf(spot), retreat = SpatialRoom.retreatOf(spot), envelopment = SpatialRoom.envelopmentOf(spot)) }) {
                    moveSource(spot)
                }
            },
            togglePart = { peerId -> scope.launch(sessions) { host.togglePart(peerId) } }
        )
    )
    Text("把图标拖到每台实际摆的位置，「你」是听的人，朝上是前方。")
}

/** The handset host's effect list. The words are the handset's. */
@Composable
private fun Effects(host: HostSession, room: RoomState, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    fun send(change: HostSession.() -> Unit) {
        scope.launch(sessions) { host.change() }
    }
    val chosen = EffectKind.entries.first { it.settings.mode == room.mode }
    Text("音效", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in EffectKind.entries) {
            val title = EFFECT_TITLES[kind] ?: continue
            FilterChip(selected = kind == chosen, onClick = { send { setEffect(kind) } }, label = { Text(title) })
        }
    }
    when (chosen) {
        EffectKind.UNISON -> {
            Text("同步放相同的声音。")
            ContentSplit(room, ::send)
        }
        EffectKind.STEREO -> {
            Text("偏左的放左声道，偏右的放右声道，按上面图里的摆位分。")
            ContentSplit(room, ::send)
        }
        EffectKind.SPIN -> {
            Text("声源环绕转动，三台以上效果更好。")
            KnobLine("转一圈", room.periodSeconds.toFloat(), SHORTEST_SPIN_SECONDS.toFloat()..LONGEST_SPIN_SECONDS.toFloat(), "${room.periodSeconds} 秒") {
                send { setSpinSeconds(it.roundToInt()) }
            }
        }
        EffectKind.PLACE -> Text("在上面的房间图里拖那个带圈的点，改变声源位置。")
    }
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "收起细调" else "细调") }
    if (open) {
        KnobLine("回声强度", room.reverb, 0f..1f, "${(room.reverb * 100).roundToInt()}%") { send { setReverb(it) } }
        // The rotation only, as on the handset: under 自定义声音位置 this number is how far in the
        // dot has been dragged, and a slider beside it would be a second control for one number.
        if (room.mode == SpatialMode.ROTATE) {
            KnobLine("包裹感", room.envelopment, 0f..SpatialField.MAX_ENVELOPMENT.toFloat(), "${(room.envelopment * 100).roundToInt()}%") {
                send { setEnvelopment(it) }
            }
            Text("声音转开之后，每台还留多少。往右拉包裹感更强、方向感更弱。")
        }
        if (splitting(room)) {
            KnobLine("分得多彻底", room.separation, 0f..1f, "${(room.separation * 100).roundToInt()}%") { send { setSeparation(it) } }
        }
    }
}

/** 分开放 - the handset's three segments, and what goes with a split once one is chosen. */
@Composable
private fun ContentSplit(room: RoomState, send: (HostSession.() -> Unit) -> Unit) {
    val split = room.separation > 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("分开放")
        FilterChip(selected = !split, onClick = { send { setSplit(null) } }, label = { Text("不分开") })
        FilterChip(
            selected = split && room.splitAxis == SplitAxis.MIDDLE_SIDES,
            onClick = { send { setSplit(SplitAxis.MIDDLE_SIDES) } },
            label = { Text("人声和伴奏") }
        )
        FilterChip(
            selected = split && room.splitAxis == SplitAxis.LOW_HIGH,
            onClick = { send { setSplit(SplitAxis.LOW_HIGH) } },
            label = { Text("低音和高音") }
        )
    }
    if (!split) return
    if (room.splitAxis == SplitAxis.LOW_HIGH) {
        KnobLine(
            "分界点",
            room.crossoverHz,
            SpatialField.LOWEST_CROSSOVER_HZ.toFloat()..SpatialField.HIGHEST_CROSSOVER_HZ.toFloat(),
            "${room.crossoverHz.roundToInt()} Hz 以下算低音"
        ) { send { setCrossoverHz(it) } }
    }
    Text("点房间图里的图标，换它放哪一半（图标下面写着）。")
    Text("此功能仅对部分音乐效果好，取决于音频。")
}

/** One knob, sent on letting go like the volume sliders. */
@Composable
private fun KnobLine(name: String, value: Float, range: ClosedFloatingPointRange<Float>, reading: String, onSet: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.width(96.dp))
        Slider(
            value = dragging ?: value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let(onSet)
                dragging = null
            },
            valueRange = range,
            modifier = Modifier.width(240.dp)
        )
        Text(reading, fontFamily = FontFamily.Monospace)
    }
}

/** The handset's titles for the effects. */
private val EFFECT_TITLES = mapOf(
    EffectKind.UNISON to "同步齐奏",
    EffectKind.STEREO to "双声道",
    EffectKind.SPIN to "旋转",
    EffectKind.PLACE to "自定义声音位置"
)

/** How long the window draws its own drag before going back to the host's word for the room. */
private const val LOCAL_HOLD_MILLIS = 1_000L

/** The handset's range for one circuit (SpatialPanel's SHORTEST_ and LONGEST_SPIN_SECONDS). */
private const val SHORTEST_SPIN_SECONDS = 2
private const val LONGEST_SPIN_SECONDS = 20

/**
 * Every device's volume, one line each, under a room slider that levels them all - the handset
 * host's volume lines. Only SoundMesh's sound moves on a computer; a handset moves its own
 * media volume, as it always has.
 */
@Composable
private fun Volumes(host: HostSession, status: HostStatus, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("音量", style = MaterialTheme.typography.titleSmall)
        if (status.volumeTouched) {
            TextButton(onClick = { scope.launch(sessions) { host.restoreVolume() } }) { Text("全部恢复") }
        }
    }
    VolumeLine("全部", status.roomVolumePercent ?: status.volumePercent) { scope.launch(sessions) { host.setRoomVolume(it) } }
    VolumeLine("本机", status.volumePercent) { scope.launch(sessions) { host.setOwnVolume(it) } }
    for (phone in status.phones) {
        VolumeLine(phone.name, phone.askedPercent ?: phone.volumePercent, reported = phone.volumePercent) {
            scope.launch(sessions) { host.setDeviceVolume(phone.peerId, it) }
        }
    }
}

/**
 * One slider. It says what it was set to and, where the device reported somewhere else - a
 * handset's volume moves in fifteen coarse steps - where it actually came to.
 */
@Composable
private fun VolumeLine(name: String, percent: Int?, reported: Int? = null, onSet: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.width(96.dp))
        Slider(
            value = dragging ?: (percent ?: SoftwareVolume.FULL).toFloat(),
            onValueChange = { dragging = it },
            // Sent once on letting go rather than on every step of a drag, as the handset does.
            onValueChangeFinished = {
                dragging?.let { onSet(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..SoftwareVolume.FULL.toFloat(),
            enabled = percent != null,
            modifier = Modifier.width(240.dp)
        )
        Text(
            when {
                percent == null -> "还没报音量"
                reported != null && reported != percent -> "$percent%（现在 $reported%）"
                else -> "$percent%"
            },
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * A device's number on its colour - the handsets' BadgeChip. Hollow and grey for one that has
 * stopped, as the handset draws it: the colour is kept for devices that are there.
 */
@Composable
private fun Badge(peerId: String, place: Int?, hollow: Boolean = false) {
    val hue = place?.let { BadgeHues.argb.getOrNull(it) }?.let { Color(it) }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val fill = hue ?: MaterialTheme.colorScheme.primary
    val label = when {
        hollow -> grey
        hue == null -> MaterialTheme.colorScheme.onPrimary
        BadgeHues.whiteLabel[place!!] -> Color.White
        else -> Color.Black
    }
    Box(
        Modifier.size(24.dp).then(
            if (hollow) Modifier.border(1.5.dp, grey, CircleShape) else Modifier.background(fill, CircleShape)
        ),
        contentAlignment = Alignment.Center
    ) {
        Text("${PeerBadge.numberOf(peerId)}", fontSize = 11.sp, color = label)
    }
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

private val SONG_EXTENSIONS = listOf("mp3", "m4a", "aac", "flac", "wma", "wav")

private fun isSong(name: String) = name.substringAfterLast(".").lowercase() in SONG_EXTENSIONS

/** One song or several, played in the order picked. */
private fun pickSongs(): List<File> {
    val dialog = FileDialog(null as Frame?, "选歌（可以多选）", FileDialog.LOAD)
    // Windows ignores the filter; the pattern in the file name box is what filters there.
    dialog.file = SONG_EXTENSIONS.joinToString(";") { "*.$it" }
    dialog.filenameFilter = FilenameFilter { _, name -> isSong(name) }
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files.toList()
}

/** A folder, through Swing's chooser: the AWT dialog cannot pick a folder on Windows. */
private fun pickFolder(): File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser().apply {
        dialogTitle = "选一个放歌的文件夹"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** The songs directly in [folder], by name. Not the folders inside it. */
private fun songsIn(folder: File): List<File> =
    folder.listFiles { file -> file.isFile && isSong(file.name) }.orEmpty().sortedBy { it.name.lowercase() }

/**
 * The addresses a sink on this network could type in, read once when the pane opens. Every
 * interface that is up, a tunnel's included: which one the other machine shares is not something
 * this side can tell.
 */
private fun ownAddresses(): List<OwnAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nic ->
            nic.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { OwnAddress(it.hostAddress, nic.displayName ?: nic.name) }
        }
}.getOrDefault(emptyList())

/** One of this machine's addresses, with the name Windows gives the adapter it is on. */
private data class OwnAddress(val address: String, val adapter: String)

/**
 * The code a handset scans when it cannot find this host by itself - the handset host's
 * PairCodeSection. The code can carry one address and this machine often has several (WiFi, a
 * cable, a tunnel), with no way to tell from here which one the handset shares; so with more than
 * one they are offered by adapter, and the one in the code is written under it, which is what
 * makes a wrong pick visible.
 */
@Composable
private fun PairCode(host: HostSession, open: Boolean, addresses: List<OwnAddress>, sessions: CoroutineDispatcher) {
    var chosen by remember { mutableStateOf(addresses.firstOrNull()) }
    var payload by remember { mutableStateOf<String?>(null) }
    // Again once the role is open: the pane is drawn before the host has an id to put in it.
    LaunchedEffect(chosen, open) { payload = chosen?.let { withContext(sessions) { host.pairingCode(it.address) } } }
    Text("给手机扫的码", style = MaterialTheme.typography.titleSmall)
    if (addresses.size > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (address in addresses) {
                FilterChip(
                    selected = address == chosen,
                    onClick = { chosen = address },
                    label = { Text("${address.adapter}  ${address.address}") }
                )
            }
        }
    }
    val where = chosen
    if (where == null) {
        Text("这台电脑现在没有局域网地址，手机扫不到。", color = MaterialTheme.colorScheme.error)
        return
    }
    val code = payload ?: return
    val modules = remember(code) { PairingCodeModules.of(code) }
    Canvas(Modifier.size(PAIR_CODE_SIZE).background(Color.White)) {
        val cell = size.width / modules.size
        for (y in modules.indices) {
            for (x in modules[y].indices) {
                if (modules[y][x]) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
    Text("手机选「当从机」，找不到这里时扫这个码。")
    Text("码里是 ${where.address}（${where.adapter}），扫码的手机要连在这个网上。")
}

/** Scanned from arm's length off a laptop screen, and small enough to leave the pane readable. */
private val PAIR_CODE_SIZE = 200.dp

private fun describe(problem: HostProblem): String = when (problem) {
    is HostProblem.PortTaken -> {
        val which = when (problem.port) {
            HostPort.COMMAND -> "命令口 TCP"
            HostPort.CLOCK -> "时钟口 UDP"
            HostPort.AUDIO -> "音频口 TCP"
            HostPort.SPATIAL -> "空间口 TCP"
        }
        "$which ${problem.number} 被占用了。是不是命令行版的主机、或者另一个 SoundMesh 还开着？"
    }
    is HostProblem.FileUnreadable -> "这个文件放不了：${problem.detail}"
    is HostProblem.SpeakersUnavailable -> "电脑的扬声器打不开：${problem.detail}"
    is HostProblem.AdvertiseFailed -> "没法在局域网里广播这台主机，手机找不到它：${problem.detail}"
    is HostProblem.PlayFailed -> "播放中断了：${problem.detail}"
    is HostProblem.CaptureFailed -> "抓不到「${problem.app}」的声音（要 Windows 10 21H2 或更新）：${problem.detail}"
}

private fun describe(status: SinkStatus): String = when (status.stage) {
    SinkStage.IDLE -> "按开始，自动找房间里的主机。"
    SinkStage.FINDING -> "正在找主机（5 秒）…"
    SinkStage.NOT_FOUND -> when (status.failure) {
        DiscoveryFailure.NO_COMPATIBLE_VERSION -> "找到了主机，但版本对不上，两边要装同一版。"
        DiscoveryFailure.AMBIGUOUS -> "房间里同时有两台主机，先关掉一台。"
        else -> "没有主机应答。主机那边开了吗？两边在同一个网络上吗？也可以在下面直接填主机地址。"
    }
    // Not "found": a host typed in by address was never found, and may not be there at all.
    SinkStage.REACHING -> "正在连主机（每 3 秒试一次）…"
    SinkStage.STANDING_BY -> "待命中：主机一放就跟着放。" + when (status.problem) {
        null -> ""
        SinkSession.NO_CLOCK_PROBLEM -> "\n上一次主机没有应答时钟，没跟上。"
        else -> "\n上一次没跟上：${status.problem}"
    }
    SinkStage.OPENING_SPEAKERS -> "正在打开扬声器…"
    SinkStage.SYNCING -> "正在和主机对时…"
    SinkStage.PLAYING -> "正在跟着放。"
    SinkStage.HOST_SILENT -> "主机没在发声，可能断了。它再放的时候这台会自己跟上。"
    SinkStage.FAILED -> "出错了：${status.problem}"
}

private const val POLL_MILLIS = 500L
