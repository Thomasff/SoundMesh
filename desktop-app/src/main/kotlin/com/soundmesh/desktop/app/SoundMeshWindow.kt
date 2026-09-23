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
import com.soundmesh.desktop.LocalNetworks
import com.soundmesh.desktop.OwnAddress
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
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.math.roundToInt

private enum class Role { HOST, SINK }

@Composable
fun SoundMeshWindow(
    host: HostSession,
    sink: SinkSession,
    sessions: CoroutineDispatcher,
    details: Boolean,
    onOpenSettings: () -> Unit
) {
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
                label = { Text(say(Phrases.role_host)) }
            )
            FilterChip(
                selected = role == Role.SINK,
                onClick = {
                    role = Role.SINK
                    scope.launch(sessions) { host.close() }
                },
                label = { Text(say(Phrases.role_sink)) }
            )
            TextButton(onClick = onOpenSettings) { Text(say(Phrases.settings_open)) }
        }
        when (role) {
            Role.HOST -> HostPane(host, sessions, details)
            Role.SINK -> SinkPane(sink, sessions, details)
            null -> {
                Text(say(Phrases.pc_role_question))
                Networks()
            }
        }
    }
}

@Composable
private fun HostPane(host: HostSession, sessions: CoroutineDispatcher, details: Boolean) {
    val status = polled { host.status() } ?: return
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    // A program's sound instead of files; picking either one puts the other down.
    var app by remember { mutableStateOf<AudioSession?>(null) }
    var programs by remember { mutableStateOf<List<AudioSession>?>(null) }
    // Kept as how to say it rather than as what was said, so a change of language reaches it.
    var picked by remember { mutableStateOf<(Boolean) -> String>({ Phrases.song_none.of(it) }) }
    var alsoHere by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val english = LocalEnglish.current

    status.problem?.let { Text(describe(it), color = MaterialTheme.colorScheme.error) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = {
            pickSongs(Phrases.pc_pick_songs_title.of(english)).takeIf { it.isNotEmpty() }?.let {
                files = it
                app = null
                val count = it.size
                val only = if (count == 1) it.single().name else null
                picked = { e -> only ?: Phrases.pc_songs_count.of(e, count) }
            }
        }, enabled = !status.playing) { Text(say(Phrases.pc_pick_files)) }
        OutlinedButton(onClick = {
            pickFolder(Phrases.pc_pick_folder_title.of(english))?.let { folder ->
                val songs = songsIn(folder)
                files = songs
                app = null
                picked = { e ->
                    if (songs.isEmpty()) Phrases.pc_folder_empty.of(e, folder.name)
                    else Phrases.pc_folder_songs.of(e, folder.name, songs.size)
                }
            }
        }, enabled = !status.playing) { Text(say(Phrases.song_choose_folder)) }
        OutlinedButton(onClick = {
            // Asked afresh each time: the mixer's rows come and go with what is playing.
            scope.launch { programs = withContext(sessions) { runCatching { AudioSessions.list() }.getOrDefault(emptyList()) } }
        }, enabled = !status.playing) { Text(say(Phrases.pc_capture_app)) }
        Text(picked(english))
    }
    programs?.let { list ->
        if (list.isEmpty()) Text(say(Phrases.pc_no_programs))
        else Text(say(Phrases.pc_which_program))
        // One to a line: a browser, a player and a call can all be in the mixer at once.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (program in list) {
                OutlinedButton(onClick = {
                    app = program
                    files = emptyList()
                    programs = null
                    picked = { e -> Phrases.pc_program_sound.of(e, program.name) }
                }) { Text(if (program.playing) say(Phrases.pc_program_playing, program.name) else program.name) }
            }
            TextButton(onClick = { programs = null }) { Text(say(Phrases.pc_never_mind)) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = alsoHere, onCheckedChange = { alsoHere = it }, enabled = !status.playing)
        Text(say(Phrases.pc_play_here_too))
    }
    if (status.playing) {
        Button(onClick = { scope.launch(sessions) { host.stopPlaying() } }) { Text(say(Phrases.play_stop)) }
        status.playhead?.let { Transport(host, it, status.paused, sessions) }
        status.capturing?.let { Text(say(Phrases.pc_capturing, it)) }
    } else {
        Button(
            onClick = {
                val program = app
                if (program != null) scope.launch(sessions) { host.playApp(program.pid, program.name, alsoHere) }
                else files.takeIf { it.isNotEmpty() }?.let { chosen -> scope.launch(sessions) { host.play(chosen, 0, alsoHere) } }
            },
            enabled = status.open && (files.isNotEmpty() || app != null)
        ) { Text(say(Phrases.play_start)) }
    }
    // The handset's word for joining names in a sentence: 、 in Chinese, a comma in English.
    val join = say(Phrases.room_volume_name_join)
    if (status.heldDown.isNotEmpty()) {
        Text(say(Phrases.pc_held_down, status.heldDown.joinToString(join)), color = MaterialTheme.colorScheme.error)
    }
    if (status.skipped.isNotEmpty()) {
        Text(say(Phrases.pc_skipped, status.skipped.joinToString(join)), color = MaterialTheme.colorScheme.error)
    }
    if (status.ended) Text(say(Phrases.pc_ended))
    Text(say(Phrases.pc_phones_follow))
    val addresses = remember { LocalNetworks.list() }
    if (addresses.isNotEmpty()) Text(say(Phrases.pc_type_this, addresses.joinToString(join) { it.address }))
    PairCode(host, status.open, addresses, sessions)
    Roster(status)
    HostDrawing(host, status.room, sessions)
    Effects(host, status.room, sessions)
    Volumes(host, status, sessions)
    Diagnostics(
        details,
        listOf(
            say(Phrases.pc_diag_audio_port) to say(Phrases.pc_diag_receiving, status.sinksOnAudio),
            say(Phrases.pc_diag_dropped) to "${status.droppedChunks}",
            say(Phrases.pc_diag_played_here) to (status.localPlayed?.let { say(Phrases.pc_chunks, it) } ?: "—"),
            say(Phrases.pc_diag_late_here) to (status.localLate?.let { say(Phrases.pc_chunks, it) } ?: "—"),
            say(Phrases.pc_diag_restarts) to (status.jumps?.let { say(Phrases.pc_times, it) } ?: "—"),
            say(Phrases.pc_diag_capture_pad) to (status.capturePadded?.let { say(Phrases.pc_chunks, it) } ?: "—"),
            say(Phrases.pc_diag_seam_here) to (status.localBand ?: "—"),
            say(Phrases.pc_diag_seam_split) to (status.localShares ?: "—")
        )
    )
}

@Composable
private fun SinkPane(sink: SinkSession, sessions: CoroutineDispatcher, details: Boolean) {
    val status = polled { sink.status() } ?: return
    val scope = rememberCoroutineScope()
    val going = status.stage !in setOf(SinkStage.IDLE, SinkStage.NOT_FOUND, SinkStage.FAILED)

    var address by remember { mutableStateOf("") }

    if (going) {
        Button(onClick = { scope.launch(sessions) { sink.stop() } }) { Text(say(Phrases.play_stop)) }
    } else {
        Button(onClick = { scope.launch(sessions) { sink.start() } }) { Text(say(Phrases.pc_start)) }
        // The way in when discovery finds nothing: the handset scans the host's code there, and
        // this machine has no camera to scan with. A host given this way is not looked for again
        // if it moves - it has no identity to be recognised by.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text(say(Phrases.pc_host_address)) },
                placeholder = { Text(say(Phrases.pc_address_example)) },
                singleLine = true
            )
            OutlinedButton(
                onClick = { scope.launch(sessions) { sink.start(address) } },
                enabled = address.isNotEmpty()
            ) { Text(say(Phrases.pc_dial_address)) }
        }
        val own = remember { LocalNetworks.list() }
        if (LocalNetworks.elsewhere(address, own)) {
            Text(
                say(Phrases.pc_address_elsewhere, address, own.joinToString(say(Phrases.room_volume_name_join)) { it.address }),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge(status.selfId, status.selfPlace)
        Text(say(Phrases.pc_my_number, PeerBadge.numberOf(status.selfId)))
    }
    if (status.volumePercent != SoftwareVolume.FULL) {
        Text(say(Phrases.pc_volume_set, status.volumePercent))
    }
    Text(describe(status))
    status.hostName?.let { name ->
        Text(status.hostAddress?.let { say(Phrases.pc_following_at, name, it) } ?: say(Phrases.pc_following, name))
    }
    status.room?.let { room ->
        Text(say(Phrases.tab_room), style = MaterialTheme.typography.titleSmall)
        RoomDrawing(room, actions = null)
        Text(say(Phrases.pc_room_readonly))
    }
    Diagnostics(
        details,
        listOf(
            say(Phrases.pc_diag_clock) to (status.offsetMillis?.let { String.format("%.3f ms", it) } ?: "—"),
            say(Phrases.pc_diag_arrived) to say(Phrases.pc_chunks, status.arrived),
            say(Phrases.pc_diag_before_clock) to say(Phrases.pc_chunks, status.beforeClock),
            say(Phrases.counter_played) to say(Phrases.pc_chunks, status.played),
            say(Phrases.counter_dropped) to say(Phrases.pc_chunks, status.late),
            say(Phrases.pc_diag_restarts) to say(Phrases.pc_times, status.restarts),
            say(Phrases.pc_diag_shaped) to say(Phrases.pc_chunks, status.shaped),
            say(Phrases.pc_diag_seam) to (status.band ?: "—"),
            say(Phrases.pc_diag_seam_split) to (status.shares ?: "—")
        )
    )
}

/**
 * Every device in the room, one line each, this machine first - the handset host's roster. The
 * badge is on every line here because this window has no drawing of the room to find a colour in.
 */
@Composable
private fun Roster(status: HostStatus) {
    Text(say(Phrases.pc_room_count, status.phones.size + 1), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        status.selfId?.let { Badge(it, status.selfPlace) }
        Text(say(Phrases.pc_self_host))
    }
    if (status.phones.isEmpty()) Text(say(Phrases.pc_no_phones))
    for (phone in status.phones) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(phone.peerId, phone.place, hollow = phone.quiet || phone.stopped)
            Text(phone.name)
        }
        // Quiet first: a quiet one may still be taking the audio, and "not taking it" would be false.
        val note = when {
            phone.quiet -> say(Phrases.roster_quiet)
            phone.stopped -> say(Phrases.pc_not_receiving)
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
    Text(say(Phrases.pc_song_of, playhead.song + 1, playhead.songs, playhead.name))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { scope.launch(sessions) { host.stepSong(-1) } }, enabled = playhead.song > 0) {
            Text(say(Phrases.play_previous))
        }
        OutlinedButton(onClick = { scope.launch(sessions) { host.setPaused(!paused) } }) {
            Text(say(if (paused) Phrases.play_resume else Phrases.play_pause))
        }
        OutlinedButton(onClick = { scope.launch(sessions) { host.stepSong(1) } }, enabled = playhead.song < playhead.songs - 1) {
            Text(say(Phrases.play_next))
        }
    }
    KnobLine(
        say(Phrases.pc_progress),
        playhead.heardMillis.toFloat().coerceAtMost(playhead.durationMillis.toFloat()),
        0f..playhead.durationMillis.toFloat().coerceAtLeast(1f),
        say(Phrases.play_position, clock(playhead.heardMillis), clock(playhead.durationMillis))
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
    Text(say(Phrases.tab_room), style = MaterialTheme.typography.titleSmall)
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
    Text(say(Phrases.pc_drag_icons))
}

/** The handset host's effect list. The words are the handset's. */
@Composable
private fun Effects(host: HostSession, room: RoomState, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    fun send(change: HostSession.() -> Unit) {
        scope.launch(sessions) { host.change() }
    }
    val chosen = EffectKind.entries.first { it.settings.mode == room.mode }
    Text(say(Phrases.room_effect_title), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in EffectKind.entries) {
            val title = EFFECT_TITLES[kind] ?: continue
            FilterChip(selected = kind == chosen, onClick = { send { setEffect(kind) } }, label = { Text(say(title)) })
        }
    }
    when (chosen) {
        EffectKind.UNISON -> {
            Text(say(Phrases.room_effect_unison_line))
            ContentSplit(room, ::send)
        }
        EffectKind.STEREO -> {
            Text(say(Phrases.pc_stereo_line))
            ContentSplit(room, ::send)
        }
        EffectKind.SPIN -> {
            Text(say(Phrases.pc_spin_line))
            KnobLine(
                say(Phrases.room_spin_period),
                room.periodSeconds.toFloat(),
                SHORTEST_SPIN_SECONDS.toFloat()..LONGEST_SPIN_SECONDS.toFloat(),
                say(Phrases.room_spin_seconds, room.periodSeconds)
            ) { send { setSpinSeconds(it.roundToInt()) } }
        }
        EffectKind.PLACE -> Text(say(Phrases.pc_place_line))
    }
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(say(if (open) Phrases.room_fine_hide else Phrases.room_fine)) }
    if (open) {
        KnobLine(say(Phrases.pc_reverb), room.reverb, 0f..1f, percent(room.reverb)) { send { setReverb(it) } }
        // The rotation only, as on the handset: under 自定义声音位置 this number is how far in the
        // dot has been dragged, and a slider beside it would be a second control for one number.
        if (room.mode == SpatialMode.ROTATE) {
            KnobLine(say(Phrases.pc_envelopment), room.envelopment, 0f..SpatialField.MAX_ENVELOPMENT.toFloat(), percent(room.envelopment)) {
                send { setEnvelopment(it) }
            }
            Text(say(Phrases.pc_envelopment_line))
            Text(say(Phrases.room_envelopment_hint))
        }
        if (splitting(room)) {
            KnobLine(say(Phrases.room_split_content), room.separation, 0f..1f, percent(room.separation)) { send { setSeparation(it) } }
        }
    }
}

/** A knob's 0-to-1 value as the handset writes it beside the knob. */
@Composable
private fun percent(fraction: Float): String = say(Phrases.room_knob_percent, (fraction * 100).roundToInt())

/** 分开放 - the handset's three segments, and what goes with a split once one is chosen. */
@Composable
private fun ContentSplit(room: RoomState, send: (HostSession.() -> Unit) -> Unit) {
    val split = room.separation > 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(say(Phrases.room_split_label))
        FilterChip(selected = !split, onClick = { send { setSplit(null) } }, label = { Text(say(Phrases.room_split_none)) })
        FilterChip(
            selected = split && room.splitAxis == SplitAxis.MIDDLE_SIDES,
            onClick = { send { setSplit(SplitAxis.MIDDLE_SIDES) } },
            label = { Text(say(Phrases.room_split_voice)) }
        )
        FilterChip(
            selected = split && room.splitAxis == SplitAxis.LOW_HIGH,
            onClick = { send { setSplit(SplitAxis.LOW_HIGH) } },
            label = { Text(say(Phrases.room_split_bass)) }
        )
    }
    if (!split) return
    if (room.splitAxis == SplitAxis.LOW_HIGH) {
        KnobLine(
            say(Phrases.pc_crossover),
            room.crossoverHz,
            SpatialField.LOWEST_CROSSOVER_HZ.toFloat()..SpatialField.HIGHEST_CROSSOVER_HZ.toFloat(),
            say(Phrases.pc_crossover_reading, room.crossoverHz.roundToInt())
        ) { send { setCrossoverHz(it) } }
    }
    Text(say(Phrases.pc_parts_click))
    Text(say(Phrases.room_split_limits))
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
    EffectKind.UNISON to Phrases.room_effect_unison,
    EffectKind.STEREO to Phrases.room_effect_stereo,
    EffectKind.SPIN to Phrases.room_effect_spin,
    EffectKind.PLACE to Phrases.room_effect_place
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
        Text(say(Phrases.tab_volume), style = MaterialTheme.typography.titleSmall)
        if (status.volumeTouched) {
            TextButton(onClick = { scope.launch(sessions) { host.restoreVolume() } }) { Text(say(Phrases.room_volume_restore)) }
        }
    }
    VolumeLine(say(Phrases.room_volume_all), status.roomVolumePercent ?: status.volumePercent) {
        scope.launch(sessions) { host.setRoomVolume(it) }
    }
    VolumeLine(say(Phrases.pc_this_computer), status.volumePercent) { scope.launch(sessions) { host.setOwnVolume(it) } }
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
                percent == null -> say(Phrases.pc_no_volume_yet)
                reported != null && reported != percent -> say(Phrases.pc_percent_now, percent, reported)
                else -> say(Phrases.room_knob_percent, percent)
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

/** The numbers behind 设置's 显示诊断细节, and nothing at all while it is off - the handset's rule. */
@Composable
private fun Diagnostics(shown: Boolean, rows: List<Pair<String, String>>) {
    if (!shown) return
    Text(say(Phrases.settings_diagnostics), style = MaterialTheme.typography.titleSmall)
    for ((label, value) in rows) {
        Row {
            Text(label, Modifier.width(96.dp))
            Text(value, fontFamily = FontFamily.Monospace)
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
private fun pickSongs(title: String): List<File> {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    // Windows ignores the filter; the pattern in the file name box is what filters there.
    dialog.file = SONG_EXTENSIONS.joinToString(";") { "*.$it" }
    dialog.filenameFilter = FilenameFilter { _, name -> isSong(name) }
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files.toList()
}

/** A folder, through Swing's chooser: the AWT dialog cannot pick a folder on Windows. */
private fun pickFolder(title: String): File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** The songs directly in [folder], by name. Not the folders inside it. */
private fun songsIn(folder: File): List<File> =
    folder.listFiles { file -> file.isFile && isSong(file.name) }.orEmpty().sortedBy { it.name.lowercase() }

/**
 * Which networks this machine is on, before a role is picked - the handset's network lines. Read
 * again every few seconds: a cable or a WiFi can come and go while the window is open.
 */
@Composable
private fun Networks() {
    var addresses by remember { mutableStateOf<List<OwnAddress>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            addresses = withContext(Dispatchers.IO) { LocalNetworks.list() }
            delay(NETWORKS_POLL_MILLIS)
        }
    }
    val list = addresses ?: return
    Text(say(Phrases.network_title), style = MaterialTheme.typography.titleSmall)
    if (list.isEmpty()) {
        Text(say(Phrases.pc_no_network), color = MaterialTheme.colorScheme.error)
        return
    }
    for (own in list) Text("${own.adapter}  ${own.address}")
    Text(say(Phrases.pc_networks_hint))
}

private const val NETWORKS_POLL_MILLIS = 3_000L

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
    Text(say(Phrases.pc_code_title), style = MaterialTheme.typography.titleSmall)
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
        Text(say(Phrases.pc_code_nowhere), color = MaterialTheme.colorScheme.error)
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
    Text(say(Phrases.pc_code_how))
    Text(say(Phrases.pc_code_address, where.address, where.adapter))
}

/** Scanned from arm's length off a laptop screen, and small enough to leave the pane readable. */
private val PAIR_CODE_SIZE = 200.dp

@Composable
private fun describe(problem: HostProblem): String = when (problem) {
    is HostProblem.PortTaken -> {
        val which = when (problem.port) {
            HostPort.COMMAND -> Phrases.pc_port_command
            HostPort.CLOCK -> Phrases.pc_port_clock
            HostPort.AUDIO -> Phrases.pc_port_audio
            HostPort.SPATIAL -> Phrases.pc_port_spatial
        }
        say(Phrases.pc_port_taken, say(which), problem.number)
    }
    is HostProblem.FileUnreadable -> say(Phrases.pc_file_unreadable, problem.detail)
    is HostProblem.SpeakersUnavailable -> say(Phrases.pc_speakers, problem.detail)
    is HostProblem.AdvertiseFailed -> say(Phrases.pc_advertise, problem.detail)
    is HostProblem.PlayFailed -> say(Phrases.pc_play_failed, problem.detail)
    is HostProblem.CaptureFailed -> say(Phrases.pc_capture_failed, problem.app, problem.detail)
}

@Composable
private fun describe(status: SinkStatus): String = when (status.stage) {
    SinkStage.IDLE -> say(Phrases.pc_sink_idle)
    SinkStage.FINDING -> say(Phrases.pc_sink_finding)
    SinkStage.NOT_FOUND -> when (status.failure) {
        DiscoveryFailure.NO_COMPATIBLE_VERSION -> say(Phrases.pc_sink_wrong_version)
        DiscoveryFailure.AMBIGUOUS -> say(Phrases.pc_sink_two_hosts)
        else -> say(Phrases.pc_sink_no_answer)
    }
    // Not "found": a host typed in by address was never found, and may not be there at all.
    SinkStage.REACHING -> say(Phrases.pc_sink_reaching)
    SinkStage.STANDING_BY -> say(Phrases.pc_sink_standing) + when (status.problem) {
        null -> ""
        SinkSession.NO_CLOCK_PROBLEM -> "\n" + say(Phrases.pc_sink_no_clock_last)
        else -> "\n" + say(Phrases.pc_sink_missed_last, status.problem)
    }
    SinkStage.OPENING_SPEAKERS -> say(Phrases.pc_sink_opening)
    SinkStage.SYNCING -> say(Phrases.pc_sink_syncing)
    SinkStage.PLAYING -> say(Phrases.pc_sink_playing)
    SinkStage.HOST_SILENT -> say(Phrases.pc_sink_host_silent)
    SinkStage.FAILED -> say(Phrases.pc_sink_failed, status.problem)
}

private const val POLL_MILLIS = 500L
