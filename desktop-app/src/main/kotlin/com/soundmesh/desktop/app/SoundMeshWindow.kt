package com.soundmesh.desktop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import com.soundmesh.product.BadgeEdges
import com.soundmesh.product.BoxTitle
import com.soundmesh.product.Chip
import com.soundmesh.product.ChoiceCard
import com.soundmesh.product.EffectKind
import com.soundmesh.product.FilledChip
import com.soundmesh.product.Framed
import com.soundmesh.product.Ghost
import com.soundmesh.product.Knob
import com.soundmesh.product.Label
import com.soundmesh.product.Line
import com.soundmesh.product.LineName
import com.soundmesh.product.Note
import com.soundmesh.product.RoomState
import com.soundmesh.product.RoundLine
import com.soundmesh.product.Segment
import com.soundmesh.product.Segmented
import com.soundmesh.product.Solid
import com.soundmesh.product.SpatialRoom
import com.soundmesh.product.Tag
import com.soundmesh.product.Tone
import com.soundmesh.product.Transport
import com.soundmesh.product.TransportIcon
import com.soundmesh.product.VolumeLine
import com.soundmesh.product.badgeColour
import com.soundmesh.product.rememberEdgeGlow
import com.soundmesh.desktop.AudioSession
import com.soundmesh.desktop.AudioSessions
import com.soundmesh.desktop.HostPort
import com.soundmesh.desktop.PairingCodeModules
import com.soundmesh.desktop.HostProblem
import com.soundmesh.desktop.HostSession
import com.soundmesh.desktop.HostStatus
import com.soundmesh.desktop.MeasureJob
import com.soundmesh.probe.sync.Carried
import com.soundmesh.desktop.LocalNetworks
import com.soundmesh.desktop.OwnAddress
import com.soundmesh.desktop.Playhead
import com.soundmesh.desktop.SinkSession
import com.soundmesh.desktop.MicrophoneProblem
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

/**
 * The one window, drawn out of the handset's own pieces (ui-shared's Look.kt and ThinSlider.kt):
 * small grey labels over hairline rows, one or two solid buttons, notes in small grey type, and
 * colour only where a device is meant.
 */
@Composable
internal fun SoundMeshWindow(
    host: HostSession,
    sink: SinkSession,
    sessions: CoroutineDispatcher,
    details: Boolean,
    onOpenSettings: () -> Unit,
    /** Opens the measuring window on [MeasureJob], aimed at one device for a pair. */
    onMeasure: (MeasureJob, String?) -> Unit,
    /** What the edge light says, kept by the caller, which draws it round the screen instead when asked. */
    look: EdgeLook?,
    onLook: (EdgeLook?) -> Unit,
    /** 设置's 整个屏幕: the light is round the screen, so none is drawn here. */
    edgeOnScreen: Boolean
) {
    var role by remember { mutableStateOf<Role?>(null) }
    val scope = rememberCoroutineScope()
    val edge = if (edgeOnScreen) null else look.colour()
    // Told whether there is a colour: with none nothing draws an edge, and nothing wakes every
    // frame to work out how bright it is not.
    val glow = rememberEdgeGlow(
        lit = edge != null,
        disconnected = look?.disconnected == true,
        playing = look?.playing == true,
        everyNanos = EDGE_EVERY_NANOS
    ) { if (look?.sink == true) sink.loudness() else host.loudness() }
    // The edge is laid over the window rather than wrapped round it, so the bands are nodes of
    // their own and the page under them is not redrawn for the light - the handset's layout.
    Box(Modifier.fillMaxSize()) {
        Column(
            // Scrolls: a roster and a volume line per device outgrow the window in a room of a few.
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Segmented(
                    listOf(
                        Segment(say(Phrases.role_host), role == Role.HOST) {
                            role = Role.HOST
                            scope.launch(sessions) { sink.stop(); host.open() }
                        },
                        Segment(say(Phrases.role_sink), role == Role.SINK) {
                            role = Role.SINK
                            scope.launch(sessions) { host.close() }
                        }
                    ),
                    Modifier.weight(1f)
                )
                Chip(say(Phrases.settings_open), onOpenSettings)
            }
            when (role) {
                Role.HOST -> HostPane(host, sessions, details, onMeasure, onLook)
                Role.SINK -> SinkPane(sink, sessions, details, onLook)
                null -> {
                    SideEffect { onLook(null) }
                    Text(say(Phrases.pc_role_question), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    Networks()
                }
            }
        }
        // Last, so it is over the page, which scrolls. A computer's window is square-cornered.
        if (edge != null) BadgeEdges(edge, glow, round = 0f)
    }
}

@Composable
private fun HostPane(
    host: HostSession,
    sessions: CoroutineDispatcher,
    details: Boolean,
    onMeasure: (MeasureJob, String?) -> Unit,
    onLook: (EdgeLook) -> Unit
) {
    val status = polled { host.status() } ?: return
    // Equal looks change nothing, so this settles after the first composition that says it.
    SideEffect { onLook(EdgeLook(status.selfPlace, disconnected = false, playing = status.playing, sink = false)) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    // A program's sound instead of files; picking either one puts the other down.
    var app by remember { mutableStateOf<AudioSession?>(null) }
    var programs by remember { mutableStateOf<List<AudioSession>?>(null) }
    // Kept as how to say it rather than as what was said, so a change of language reaches it.
    var picked by remember { mutableStateOf<(Boolean) -> String>({ Phrases.song_none.of(it) }) }
    var alsoHere by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val english = LocalEnglish.current

    // Choosing something else while the room plays is what the handset host's putDownWhatIsPlaying
    // does: the room stops, and the new pick waits for 开始. Only once something is actually chosen -
    // a dialog closed on nothing, or the program list opened and left, changes nothing.
    val putDown = { if (status.playing) scope.launch(sessions) { host.stopPlaying() } }

    status.problem?.let { Note(describe(it), Tone.WRONG) }
    Label(say(Phrases.song_title))
    Line(first = true) {
        LineName(picked(english))
        // There while the room plays as well, as on the handset: changing what the room is
        // playing is a thing people do while it is playing.
        Chip(say(Phrases.pc_pick_files)) {
            pickSongs(Phrases.pc_pick_songs_title.of(english)).takeIf { it.isNotEmpty() }?.let {
                putDown()
                files = it
                app = null
                val count = it.size
                val only = if (count == 1) it.single().name else null
                picked = { e -> only ?: Phrases.pc_songs_count.of(e, count) }
            }
        }
        Chip(say(Phrases.song_choose_folder)) {
            pickFolder(Phrases.pc_pick_folder_title.of(english))?.let { folder ->
                putDown()
                val songs = songsIn(folder)
                files = songs
                app = null
                picked = { e ->
                    if (songs.isEmpty()) Phrases.pc_folder_empty.of(e, folder.name)
                    else Phrases.pc_folder_songs.of(e, folder.name, songs.size)
                }
            }
        }
        Chip(say(Phrases.pc_capture_app)) {
            // Asked afresh each time: the mixer's rows come and go with what is playing.
            scope.launch { programs = withContext(sessions) { runCatching { AudioSessions.list() }.getOrDefault(emptyList()) } }
        }
    }
    programs?.let { list ->
        Note(say(if (list.isEmpty()) Phrases.pc_no_programs else Phrases.pc_which_program))
        // One to a line: a browser, a player and a call can all be in the mixer at once.
        for (program in list) {
            Line {
                LineName(if (program.playing) say(Phrases.pc_program_playing, program.name) else program.name)
                Chip(say(Phrases.pc_use_program)) {
                    putDown()
                    app = program
                    files = emptyList()
                    programs = null
                    picked = { e -> Phrases.pc_program_sound.of(e, program.name) }
                }
            }
        }
        Tag(say(Phrases.pc_never_mind), onClick = { programs = null })
    }
    Line {
        LineName(say(Phrases.pc_play_here_too))
        Switch(checked = alsoHere, onCheckedChange = { alsoHere = it }, enabled = !status.playing)
    }
    if (status.playing) {
        status.playhead?.let { PlayControls(host, it, status.paused, sessions) }
        Ghost(say(Phrases.play_stop)) { scope.launch(sessions) { host.stopPlaying() } }
        status.capturing?.let { Note(say(Phrases.pc_capturing, it)) }
    } else {
        Solid(
            say(Phrases.play_start),
            enabled = status.open && !status.measure.running && (files.isNotEmpty() || app != null)
        ) {
            val program = app
            if (program != null) scope.launch(sessions) { host.playApp(program.pid, program.name, alsoHere) }
            else files.takeIf { it.isNotEmpty() }?.let { chosen -> scope.launch(sessions) { host.play(chosen, 0, alsoHere) } }
        }
        if (status.measure.running) Note(say(Phrases.pc_measuring_now))
    }
    // The handset's word for joining names in a sentence: 、 in Chinese, a comma in English.
    val join = say(Phrases.room_volume_name_join)
    if (status.heldDown.isNotEmpty()) Note(say(Phrases.pc_held_down, status.heldDown.joinToString(join)), Tone.WRONG)
    if (status.skipped.isNotEmpty()) Note(say(Phrases.pc_skipped, status.skipped.joinToString(join)), Tone.WRONG)
    if (status.ended) Note(say(Phrases.pc_ended))
    Note(say(Phrases.pc_phones_follow))
    val addresses = remember { LocalNetworks.list() }
    if (addresses.isNotEmpty()) Note(say(Phrases.pc_type_this, addresses.joinToString(join) { it.address }))
    PairCode(host, status.open, addresses, sessions)
    Roster(status) { peerId -> onMeasure(MeasureJob.PAIR, peerId) }
    // The handset home screen's 位置同步校准, which opens the calibration screen rather than
    // starting anything: a minute of chirps is asked for there, with the room's volumes in view.
    // Drawn as the handset draws it, as the one box on the screen that is the point of it.
    Label(say(Phrases.calibrate_section))
    Framed(strong = true) {
        BoxTitle(say(Phrases.goto_room), strong = true)
        Note(say(Phrases.goto_room_hint))
        Column(Modifier.padding(top = 7.dp)) {
            Solid(say(Phrases.goto_room_go), enabled = status.open) { onMeasure(MeasureJob.ROOM, null) }
        }
    }
    HostDrawing(host, status.room, sessions, rememberRipple(status.playing), status.killedIds)
    MeasuredLines(host, status.room, details, sessions)
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
private fun SinkPane(sink: SinkSession, sessions: CoroutineDispatcher, details: Boolean, onLook: (EdgeLook) -> Unit) {
    val status = polled { sink.status() } ?: return
    SideEffect {
        onLook(
            EdgeLook(
                status.selfPlace,
                disconnected = status.stage !in ON_THE_LIST,
                playing = status.stage == SinkStage.PLAYING || status.stage == SinkStage.HOST_SILENT,
                sink = true
            )
        )
    }
    val scope = rememberCoroutineScope()
    val going = status.stage !in setOf(SinkStage.IDLE, SinkStage.NOT_FOUND, SinkStage.FAILED)

    var address by remember { mutableStateOf("") }

    Column(Modifier.padding(top = 10.dp)) {
        if (going) {
            Solid(say(Phrases.play_stop)) { scope.launch(sessions) { sink.stop() } }
        } else {
            Solid(say(Phrases.pc_start)) { scope.launch(sessions) { sink.start() } }
        }
    }
    if (!going) {
        // The way in when discovery finds nothing: the handset scans the host's code there, and
        // this machine has no camera to scan with. A host given this way is not looked for again
        // if it moves - it has no identity to be recognised by.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text(say(Phrases.pc_host_address)) },
                placeholder = { Text(say(Phrases.pc_address_example)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Ghost(say(Phrases.pc_dial_address), enabled = address.isNotEmpty(), modifier = Modifier.width(DIAL_WIDTH)) {
                scope.launch(sessions) { sink.start(address) }
            }
        }
        val own = remember { LocalNetworks.list() }
        if (LocalNetworks.elsewhere(address, own)) {
            Note(say(Phrases.pc_address_elsewhere, address, own.joinToString(say(Phrases.room_volume_name_join)) { it.address }), Tone.WRONG)
        }
    }
    Line(first = true) {
        Badge(status.selfId, status.selfPlace)
        LineName(say(Phrases.pc_my_number, PeerBadge.numberOf(status.selfId)))
    }
    if (status.volumePercent != SoftwareVolume.FULL) Note(say(Phrases.pc_volume_set, status.volumePercent))
    Text(describe(status), style = MaterialTheme.typography.bodyMedium)
    status.hostName?.let { name ->
        Note(status.hostAddress?.let { say(Phrases.pc_following_at, name, it) } ?: say(Phrases.pc_following, name))
    }
    // Outside the room's own block: the host's rule can land a moment after the speakers open,
    // and the ring starts from when this machine started playing, as the handset's does.
    val ripple = rememberRipple(status.stage == SinkStage.PLAYING)
    status.room?.let { room ->
        Label(say(Phrases.tab_room))
        RoomDrawing(room, actions = null, ripple = ripple)
        Note(say(Phrases.pc_room_readonly))
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

/** Wide enough for 连这个地址 beside an address box that takes the rest of the row. */
private val DIAL_WIDTH = 132.dp

/**
 * Every device in the room, one line each, this machine first - the handset host's roster. The
 * badge is on every line here because this window has no drawing of the room to find a colour in.
 */
@Composable
private fun Roster(status: HostStatus, onCalibrate: (String) -> Unit) {
    Label(say(Phrases.pc_room_count, status.phones.size + 1))
    Line(first = true) {
        status.selfId?.let { Badge(it, status.selfPlace) }
        LineName(say(Phrases.pc_self_host))
    }
    if (status.phones.isEmpty()) Note(say(Phrases.pc_no_phones))
    for (phone in status.phones) {
        val quiet = phone.quiet || phone.stopped
        Line {
            Badge(phone.peerId, phone.place, hollow = quiet)
            LineName(phone.name, quiet = quiet)
            // The handset host's roster line: how it is lined up, and the one errand that fixes
            // it. Settled lines carry no chip, and the word itself is the way to measure again.
            Tag(
                say(carryingWord(phone.carrying)),
                carryingTone(phone.carrying),
                onClick = if (phone.carrying == Carried.SOMETHING) {
                    { onCalibrate(phone.peerId) }
                } else {
                    null
                }
            )
            if (phone.carrying != Carried.SOMETHING) {
                FilledChip(say(Phrases.roster_calibrate)) { onCalibrate(phone.peerId) }
            }
        }
        // Quiet first: a quiet one may still be taking the audio, and "not taking it" would be false.
        val notes = listOfNotNull(
            when {
                phone.quiet -> say(Phrases.roster_quiet)
                phone.stopped -> say(Phrases.pc_not_receiving)
                else -> null
            },
            phone.excuse?.let { describe(it) }
        )
        if (notes.isNotEmpty()) {
            Column(Modifier.padding(start = 32.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (note in notes) Note(note, Tone.WATCH)
            }
        }
    }
}

/** How the roster word reads - the handset's carryingTone. */
private fun carryingTone(carrying: Carried): Tone = when (carrying) {
    Carried.SOMETHING -> Tone.GOOD
    Carried.APPROXIMATE -> Tone.QUIET
    Carried.NOTHING -> Tone.WATCH
    Carried.UNSAID -> Tone.QUIET
}

/**
 * Where in the song the room is and 上一首 / 暂停 / 下一首 - the handset host's playhead and
 * transport row, in its order and with its drawn buttons. Every one of them lands a lead later in
 * the room: the queued audio is thrown away and the new place starts a second and a half on.
 */
@Composable
private fun PlayControls(host: HostSession, playhead: Playhead, paused: Boolean, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    Note(say(Phrases.pc_song_of, playhead.song + 1, playhead.songs, playhead.name))
    Progress(playhead) { scope.launch(sessions) { host.seekTo(it) } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.weight(1f))
        Transport(TransportIcon.PREVIOUS, enabled = playhead.song > 0) { scope.launch(sessions) { host.stepSong(-1) } }
        Transport(if (paused) TransportIcon.PLAY else TransportIcon.PAUSE, enabled = true, filled = true) {
            scope.launch(sessions) { host.setPaused(!paused) }
        }
        Transport(TransportIcon.NEXT, enabled = playhead.song < playhead.songs - 1) { scope.launch(sessions) { host.stepSong(1) } }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * Where the song is, and a way to send the room somewhere else in it - the handset's PlayheadPanel:
 * a Material slider as there, with the time heard under its left end and the length under its right.
 * Sent once on letting go, since every seek is a lead of silence in the room.
 */
@Composable
private fun Progress(playhead: Playhead, seek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = playhead.durationMillis.coerceAtLeast(1L)
    val heard = dragging?.toLong() ?: playhead.heardMillis.coerceAtMost(duration)
    Slider(
        value = heard.toFloat(),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { seek(it.toLong()) }
            dragging = null
        },
        valueRange = 0f..duration.toFloat()
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clock(heard), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(clock(playhead.durationMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
internal fun HostDrawing(
    host: HostSession,
    room: RoomState,
    sessions: CoroutineDispatcher,
    ripple: Float? = null,
    killed: Set<String> = emptySet()
) {
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
    Label(say(Phrases.tab_room))
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
        ),
        ripple,
        killed
    )
    Note(say(Phrases.pc_drag_icons))
}

/**
 * The handset host's effect list: one card each, and the chosen one opens to say what it does and
 * hold what moves it. The words are the handset's, apart from the lines that say what an effect
 * does, which are written for a room that is not only handsets.
 */
@Composable
private fun Effects(host: HostSession, room: RoomState, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    fun send(change: HostSession.() -> Unit) {
        scope.launch(sessions) { host.change() }
    }
    val chosen = EffectKind.entries.first { it.settings.mode == room.mode }
    Label(say(Phrases.room_effect_title))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (kind in EffectKind.entries) {
            val title = EFFECT_TITLES[kind] ?: continue
            ChoiceCard(say(title), kind == chosen, onClick = { send { setEffect(kind) } }) {
                when (kind) {
                    EffectKind.UNISON -> {
                        EffectLine(say(Phrases.room_effect_unison_line))
                        ContentSplit(room, ::send)
                    }
                    EffectKind.STEREO -> {
                        EffectLine(say(Phrases.pc_stereo_line))
                        ContentSplit(room, ::send)
                    }
                    EffectKind.SPIN -> {
                        EffectLine(say(Phrases.pc_spin_line))
                        HeldKnob(
                            say(Phrases.room_spin_period),
                            room.periodSeconds.toFloat(),
                            SHORTEST_SPIN_SECONDS.toFloat()..LONGEST_SPIN_SECONDS.toFloat(),
                            { Phrases.room_spin_seconds.of(it.english, it.value.roundToInt()) }
                        ) { send { setSpinSeconds(it.roundToInt()) } }
                    }
                    EffectKind.PLACE -> EffectLine(say(Phrases.pc_place_line))
                }
            }
        }
    }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        Ghost(say(if (open) Phrases.room_fine_hide else Phrases.room_fine)) { open = !open }
    }
    if (open) {
        HeldKnob(say(Phrases.pc_reverb), room.reverb, 0f..1f, ::percent) { send { setReverb(it) } }
        // The rotation only, as on the handset: under 自定义声音位置 this number is how far in the
        // dot has been dragged, and a slider beside it would be a second control for one number.
        if (room.mode == SpatialMode.ROTATE) {
            HeldKnob(say(Phrases.pc_envelopment), room.envelopment, 0f..SpatialField.MAX_ENVELOPMENT.toFloat(), ::percent) {
                send { setEnvelopment(it) }
            }
            Note(say(Phrases.pc_envelopment_line))
            Note(say(Phrases.room_envelopment_hint))
        }
        if (splitting(room)) {
            HeldKnob(say(Phrases.room_split_content), room.separation, 0f..1f, ::percent) { send { setSeparation(it) } }
        }
    }
}

/** What a chosen effect does, under its name in its card - the handset's EffectRow line. */
@Composable
private fun EffectLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What a knob's readout is made from: the value it shows, in the language it is said in. */
private class Reading(val value: Float, val english: Boolean)

/** A knob's 0-to-1 value as the handset writes it beside the knob. */
private fun percent(reading: Reading): String = Phrases.room_knob_percent.of(reading.english, (reading.value * 100).roundToInt())

/**
 * The handset's knob, sent as it turns as on the handset - these are turned while listening - and
 * drawn from where it was just turned to until the host's own status catches up, as [HostDrawing]
 * is: the status is read twice a second, and a dot that followed the mouse only that often would
 * jump back and forth under it.
 */
@Composable
private fun HeldKnob(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: (Reading) -> String,
    onSet: (Float) -> Unit
) {
    var local by remember { mutableStateOf<Float?>(null) }
    var touchedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(touchedAt) {
        delay(LOCAL_HOLD_MILLIS)
        local = null
    }
    val shown = local ?: value
    Knob(title, shown, readout(Reading(shown, LocalEnglish.current)), range) {
        local = it
        touchedAt = System.nanoTime()
        onSet(it)
    }
}

/** 分开放 - the handset's three segments, and what goes with a split once one is chosen. */
@Composable
private fun ContentSplit(room: RoomState, send: (HostSession.() -> Unit) -> Unit) {
    val split = room.separation > 0f
    Note(say(Phrases.room_split_label))
    Segmented(
        listOf(
            Segment(say(Phrases.room_split_none), !split) { send { setSplit(null) } },
            Segment(say(Phrases.room_split_voice), split && room.splitAxis == SplitAxis.MIDDLE_SIDES) {
                send { setSplit(SplitAxis.MIDDLE_SIDES) }
            },
            Segment(say(Phrases.room_split_bass), split && room.splitAxis == SplitAxis.LOW_HIGH) {
                send { setSplit(SplitAxis.LOW_HIGH) }
            }
        )
    )
    if (!split) return
    if (room.splitAxis == SplitAxis.LOW_HIGH) {
        HeldKnob(
            say(Phrases.pc_crossover),
            room.crossoverHz,
            SpatialField.LOWEST_CROSSOVER_HZ.toFloat()..SpatialField.HIGHEST_CROSSOVER_HZ.toFloat(),
            { Phrases.pc_crossover_reading.of(it.english, it.value.roundToInt()) }
        ) { send { setCrossoverHz(it) } }
    }
    Note(say(Phrases.pc_parts_click))
    Note(say(Phrases.room_split_limits))
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
 * Every device's volume, one line each, under a room line that levels them all - the handset
 * host's volume lines, each dot in its device's colour. Only SoundMesh's sound moves on a
 * computer; a handset moves its own media volume, as it always has.
 */
@Composable
private fun Volumes(host: HostSession, status: HostStatus, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    Label(
        say(Phrases.tab_volume),
        trailing = if (status.volumeTouched) say(Phrases.room_volume_restore) else null,
        onTrailing = if (status.volumeTouched) {
            { scope.launch(sessions) { host.restoreVolume() } }
        } else {
            null
        }
    )
    VolumeLine(
        name = say(Phrases.room_volume_all),
        percent = status.roomVolumePercent ?: status.volumePercent,
        colour = MaterialTheme.colorScheme.onSurface,
        strong = true
    ) { scope.launch(sessions) { host.setRoomVolume(it) } }
    VolumeLine(
        name = say(Phrases.pc_this_computer),
        percent = status.volumePercent,
        colour = badgeColour(status.selfPlace, MaterialTheme.colorScheme.onSurfaceVariant)
    ) { scope.launch(sessions) { host.setOwnVolume(it) } }
    for (phone in status.phones) {
        val reported = phone.volumePercent
        if (reported == null) {
            Line {
                LineName(phone.name, quiet = true)
                Tag(say(Phrases.pc_no_volume_yet))
            }
            continue
        }
        VolumeLine(
            name = phone.name,
            percent = reported,
            colour = badgeColour(phone.place, MaterialTheme.colorScheme.onSurfaceVariant)
        ) { scope.launch(sessions) { host.setDeviceVolume(phone.peerId, it) } }
        // What it was told, where that is not where it came to - a handset's volume moves in
        // fifteen coarse steps. The row itself draws what the device says, as on the handset.
        phone.askedPercent?.takeIf { it != reported }?.let { Note(say(Phrases.pc_percent_now, it, reported)) }
    }
}

/**
 * A device's number on its colour - the handsets' BadgeChip. Hollow and grey for one that has
 * stopped, as the handset draws it: the colour is kept for devices that are there.
 */
@Composable
internal fun Badge(peerId: String, place: Int?, hollow: Boolean = false) {
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
    Label(say(Phrases.settings_diagnostics))
    for ((index, row) in rows.withIndex()) {
        Line(first = index == 0) {
            LineName(row.first, quiet = true)
            Text(row.second, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
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
internal fun <T> polled(read: () -> T): T? {
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
    Label(say(Phrases.network_title))
    if (list.isEmpty()) {
        Note(say(Phrases.pc_no_network), Tone.WRONG)
        return
    }
    for ((index, own) in list.withIndex()) {
        Line(first = index == 0) {
            LineName(own.adapter)
            Tag(own.address)
        }
    }
    Note(say(Phrases.pc_networks_hint))
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
    Label(say(Phrases.pc_code_title))
    if (addresses.size > 1) {
        // Adapter over address in each cell: a cell holds two lines, and the adapter's name is
        // what somebody picks by.
        Segmented(addresses.map { address -> Segment("${address.adapter}\n${address.address}", address == chosen) { chosen = address } })
    }
    val where = chosen
    if (where == null) {
        Note(say(Phrases.pc_code_nowhere), Tone.WRONG)
        return
    }
    val code = payload ?: return
    val modules = remember(code) { PairingCodeModules.of(code) }
    Canvas(Modifier.padding(top = 6.dp).size(PAIR_CODE_SIZE).background(Color.White)) {
        val cell = size.width / modules.size
        for (y in modules.indices) {
            for (x in modules[y].indices) {
                if (modules[y][x]) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
    Note(say(Phrases.pc_code_how))
    Note(say(Phrases.pc_code_address, where.address, where.adapter))
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
    } + roundLines(status)
    SinkStage.OPENING_SPEAKERS -> say(Phrases.pc_sink_opening)
    SinkStage.SYNCING -> say(Phrases.pc_sink_syncing)
    SinkStage.PLAYING -> say(Phrases.pc_sink_playing)
    SinkStage.HOST_SILENT -> say(Phrases.pc_sink_host_silent)
    SinkStage.FAILED -> say(Phrases.pc_sink_failed, status.problem)
    SinkStage.MEASURING -> status.round?.let { describe(it) } ?: say(Phrases.pc_sink_measuring)
}

/**
 * What the last round came to, and why this machine could not record one, each on a line of its
 * own under the standing-by one - kept there after the round, so it lives until somebody reads it.
 */
@Composable
private fun roundLines(status: SinkStatus): String =
    (status.round?.let { "\n" + describe(it) } ?: "") +
        (status.microphone?.let { "\n" + describe(it, status.microphoneDetail) } ?: "")

/**
 * A round's sentence in the handset's words, except the few whose handset wording names a phone or
 * a button this machine does not have - those are the pc_round_ ones beside them.
 */
@Composable
internal fun describe(line: RoundLine): String = when (line) {
    is RoundLine.Failed -> say(Phrases.pair_calibrate_failed, line.code)
    RoundLine.NoPairing -> say(Phrases.pair_calibrate_no_pairing)
    RoundLine.Clock -> say(Phrases.pair_calibrate_clock)
    is RoundLine.SlowLink -> say(Phrases.pc_round_slow_link, line.medianMs, line.maxMs)
    RoundLine.CalledOff -> say(Phrases.pair_calibrate_room_called_off)
    RoundLine.Running -> say(Phrases.pc_round_running)
    is RoundLine.RoomSinkDone -> say(Phrases.pc_round_room_done, line.handsets, line.readable)
    is RoundLine.RoomSinkApproximate ->
        say(Phrases.pc_round_room_approximate, line.handsets, line.readable, line.keptMs)
    is RoundLine.MeasuredOnly -> say(Phrases.pair_calibrate_measured_only, line.ms)
    is RoundLine.Kept -> say(Phrases.pair_calibrate_kept, line.reason)
    is RoundLine.Verified -> say(Phrases.pair_calibrate_verified, line.ms)
    is RoundLine.NotFolded -> say(Phrases.pair_calibrate_not_folded, line.ms)
    is RoundLine.Done -> say(Phrases.pair_calibrate_done, line.ms, line.observations)
    RoundLine.Waiting -> say(Phrases.pair_calibrate_waiting)
    RoundLine.WaitingAimed -> say(Phrases.pair_calibrate_waiting_aimed)
    RoundLine.AimedGone -> say(Phrases.pair_calibrate_aimed_gone)
    RoundLine.RoomToldNobody -> say(Phrases.pair_calibrate_room_told_nobody)
    is RoundLine.RoomWaiting -> say(Phrases.pair_calibrate_room_waiting, line.told, line.seconds)
    is RoundLine.RoomJoined -> say(Phrases.pair_calibrate_room_joined, line.joined, line.told)
    RoundLine.RoomCalledOffHere -> say(Phrases.pair_calibrate_room_called_off_here)
    RoundLine.Stopping -> say(Phrases.pair_calibrate_stopping)
    is RoundLine.RoomDone -> say(Phrases.pair_calibrate_room_done, line.handsets, line.measured, line.pairs)
    is RoundLine.HostDone -> say(Phrases.pair_calibrate_host_done, line.ms, line.metres)
    is RoundLine.DistanceDone -> say(Phrases.pair_calibrate_distance_done, line.metres)
    is RoundLine.WrongSink -> say(Phrases.pair_calibrate_wrong_sink, "${PeerBadge.numberOf(line.peerId)}")
    is RoundLine.Excused -> describe(line.excuse)
}

/**
 * What one device's constant is called on the roster - the handset's carryingWord. UNSAID is a
 * device too old to say, not one that carries nothing.
 */
private fun carryingWord(carrying: Carried): Phrase = when (carrying) {
    Carried.SOMETHING -> Phrases.roster_calibrated
    Carried.APPROXIMATE -> Phrases.roster_approximate
    Carried.NOTHING -> Phrases.roster_uncalibrated
    Carried.UNSAID -> Phrases.roster_unsaid
}

/** The handset's words for why a device kept out of a round (RoomRoster.excuseWord). */
@Composable
internal fun describe(excuse: RoomExcuse): String = when (excuse) {
    RoomExcuse.NO_MICROPHONE -> say(Phrases.excuse_no_microphone)
    RoomExcuse.SLOW_LINK -> say(Phrases.excuse_slow_link)
    RoomExcuse.CLOCK_NOT_CONVERGED -> say(Phrases.excuse_clock_not_converged)
    RoomExcuse.BUSY -> say(Phrases.excuse_busy)
    RoomExcuse.ASLEEP -> say(Phrases.excuse_asleep)
}

@Composable
internal fun describe(problem: MicrophoneProblem, detail: String?): String = when (problem) {
    MicrophoneProblem.NO_DEVICE -> say(Phrases.pc_mic_no_device)
    MicrophoneProblem.DENIED -> say(Phrases.pc_mic_denied)
    MicrophoneProblem.NOT_48K -> say(Phrases.pc_mic_not_48k, detail)
    MicrophoneProblem.OTHER -> say(Phrases.pc_mic_other, detail)
}

private const val POLL_MILLIS = 500L
