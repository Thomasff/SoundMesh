package com.soundmesh.desktop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.soundmesh.core.BadgeHues
import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.core.PeerBadge
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import com.soundmesh.product.AskBeforeGoing
import com.soundmesh.product.BadgeEdges
import com.soundmesh.product.BeforePlaying
import com.soundmesh.product.BoxTitle
import com.soundmesh.product.ChoiceCard
import com.soundmesh.product.EffectKind
import com.soundmesh.product.FilledChip
import com.soundmesh.product.Framed
import com.soundmesh.product.Ghost
import com.soundmesh.product.Knob
import com.soundmesh.product.PeerCarrying
import com.soundmesh.product.beforePlaying
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
import com.soundmesh.product.RoomReading
import com.soundmesh.product.roomReadings
import com.soundmesh.product.ruleOf
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

/** Which kind of source the host was last given - which cell of 歌曲｜文件夹｜本机的声音 is filled. */
private enum class SourceKind { SONGS, FOLDER, APP }

/**
 * What the host will play next, held above the pages: it is picked on the playing page, and going
 * to 状态 or 设置 and back must not lose it.
 */
private class HostPick {
    var files by mutableStateOf<List<File>>(emptyList())
    // Programs' sound instead of files, or everything's; picking one puts the others down.
    var apps by mutableStateOf<List<AudioSession>>(emptyList())
    var everything by mutableStateOf(false)
    var kind by mutableStateOf<SourceKind?>(null)
    // Kept as how to say it rather than as what was said, so a change of language reaches it.
    var picked by mutableStateOf<(Boolean) -> String>({ Phrases.song_none.of(it) })
    var alsoHere by mutableStateOf(true)
}

/**
 * The one window, drawn out of the handset's own pieces (ui-shared's Look.kt and ThinSlider.kt):
 * small grey labels over hairline rows, one or two solid buttons, notes in small grey type, and
 * colour only where a device is meant.
 *
 * Laid out as the handset is, as pages in the one window rather than one long page: 选角色, then
 * 状态 (the board - network, pairing, devices, calibration), then 正在播放 (what is playing and
 * what to do about it now), with 设置 and the measuring page as places somebody goes and comes
 * back from. ← goes one page up and leaves the music as it is.
 */
@Composable
internal fun SoundMeshWindow(
    host: HostSession,
    sink: SinkSession,
    sessions: CoroutineDispatcher,
    details: Boolean,
    /** The settings page's body, given the way back; its choices are the caller's to keep. */
    settings: @Composable (onBack: () -> Unit) -> Unit,
    /** What the edge light says, kept by the caller, which draws it round the screen instead when asked. */
    look: EdgeLook?,
    onLook: (EdgeLook?) -> Unit,
    /** 设置's 整个屏幕: the light is round the screen, so none is drawn here. */
    edgeOnScreen: Boolean
) {
    var role by remember { mutableStateOf<Role?>(null) }
    var steppedBack by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Which measuring page is up, and on whom - the handset's calibration screen. Null while none is.
    var measuring by remember { mutableStateOf<Pair<MeasureJob, String?>?>(null) }
    // What is still to be put to whoever pressed 进入播放, the one on screen first - see beforePlaying.
    var asking by remember { mutableStateOf(emptyList<BeforePlaying>()) }
    val pick = remember { HostPick() }
    val scope = rememberCoroutineScope()

    // Read here rather than on the pages, so the edge light and the page both follow what this
    // machine is doing whichever page is up - settings included.
    val hostStatus = if (role == Role.HOST) polled { host.status() } else null
    val sinkStatus = if (role == Role.SINK) polled { sink.status() } else null
    val running = when (role) {
        Role.HOST -> hostStatus?.playing == true
        Role.SINK -> sinkStatus?.stage in SINK_RUNNING
        null -> false
    }
    // The handset's readSession: once the room plays, the room holds the page and the stand-in
    // is given back; once it stops, there is nothing left to have stepped back out of.
    LaunchedEffect(running) {
        if (running) holding = false else steppedBack = false
    }
    // Equal looks change nothing, so this settles after the first composition that says it. Left
    // alone while a role's first reading is still on its way, rather than put out for a moment.
    SideEffect {
        when (role) {
            Role.HOST -> hostStatus?.let { onLook(EdgeLook(it.selfPlace, disconnected = false, playing = it.playing, sink = false)) }
            Role.SINK -> sinkStatus?.let {
                onLook(
                    EdgeLook(
                        it.selfPlace,
                        disconnected = it.stage !in ON_THE_LIST,
                        playing = it.stage == SinkStage.PLAYING || it.stage == SinkStage.HOST_SILENT,
                        sink = true
                    )
                )
            }
            null -> onLook(null)
        }
    }
    // A sink starts looking the moment it is picked, as a handset sink's standby does, and stops
    // only when the role is put down: there is no 开始 to press.
    val pickRole: (Role?) -> Unit = { wanted ->
        role = wanted
        holding = false
        steppedBack = false
        scope.launch(sessions) {
            when (wanted) {
                Role.HOST -> { sink.stop(); host.open() }
                Role.SINK -> { host.close(); sink.start() }
                null -> { host.close(); sink.stop() }
            }
        }
    }
    val stage = stageOf(role, running, steppedBack, holding)
    val stepBack: () -> Unit = {
        when (stage) {
            // Only while something plays: with nothing playing there is nothing to leave running,
            // and a flag left set would keep this machine off the playing page when a room starts.
            Stage.PLAYING -> {
                if (running) steppedBack = true
                holding = false
            }
            Stage.READY -> pickRole(null)
            Stage.WELCOME -> Unit
        }
    }
    val goOnToPlaying = {
        steppedBack = false
        holding = true
    }
    // Unless there is something to ask first - the handset host's 进入播放. Only about the devices:
    // a computer plays nothing on a second output of its own, so it has no lead to have measured.
    val enterPlaying: () -> Unit = {
        asking = if (role != Role.HOST) emptyList() else beforePlaying(
            ownLeadMissing = false,
            peers = hostStatus?.phones.orEmpty().map { PeerCarrying(it.peerId, it.name, it.carrying) }
        )
        if (asking.isEmpty()) goOnToPlaying()
    }

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
        val job = measuring
        when {
            settingsOpen -> settings { settingsOpen = false }
            job != null -> MeasurePane(host, job.first, job.second, sessions, details) {
                // A round nobody is watching is a round nobody wants - the handset screen's
                // onDestroy. Called off on the sessions' thread, and the page goes at once.
                scope.launch(sessions) { runCatching { host.callOffMeasuring() } }
                measuring = null
            }
            else -> {
                val openSettings = { settingsOpen = true }
                val back = if (stage == Stage.WELCOME) null else stepBack
                val title = say(
                    when (stage) {
                        Stage.WELCOME -> Phrases.welcome_title
                        Stage.READY -> Phrases.ready_title
                        Stage.PLAYING -> Phrases.play_title
                    }
                )
                // Above the scroll on the board while the room plays: the one thing there that
                // cannot wait for somebody to scroll back up to it - the handset's rule.
                val pinned: @Composable ColumnScope.() -> Unit = {
                    if (stage == Stage.READY && running) {
                        Column(Modifier.padding(top = 8.dp)) {
                            Solid(say(Phrases.ready_back_to_play)) { steppedBack = false }
                        }
                    }
                }
                Page(title, back, openSettings, pinned = pinned) {
                    when (stage) {
                        Stage.WELCOME -> {
                            RolePicker(onHost = { pickRole(Role.HOST) }, onSink = { pickRole(Role.SINK) })
                            Networks()
                        }
                        Stage.READY -> {
                            when (role) {
                                Role.HOST -> hostStatus?.let { HostReady(host, it, sessions) { job, aimedAt -> measuring = job to aimedAt } }
                                Role.SINK -> sinkStatus?.let { SinkReady(sink, it, sessions) }
                                null -> Unit
                            }
                            if (!running) {
                                Column(Modifier.padding(top = 16.dp)) {
                                    Solid(say(Phrases.ready_go), onClick = enterPlaying)
                                }
                            }
                            Ghost(say(Phrases.role_change)) { pickRole(null) }
                        }
                        Stage.PLAYING -> when (role) {
                            Role.HOST -> hostStatus?.let {
                                // A pick while the room plays puts it down, and whoever picked it
                                // is still on this page - the handset's putDownWhatIsPlaying.
                                HostPlaying(host, it, pick, sessions, details) { holding = true }
                            }
                            Role.SINK -> sinkStatus?.let { SinkPlaying(it, details) }
                            null -> Unit
                        }
                    }
                }
            }
        }
        // Last, so it is over the page, which scrolls. A computer's window is square-cornered.
        if (edge != null) BadgeEdges(edge, glow, round = 0f, keepHandsetWavelength = true)
        // 去校准 drops the rest, as on the handset: back on the board, 进入播放 asks afresh.
        asking.firstOrNull()?.let { ask ->
            AskBeforeGoing(
                text = when (ask) {
                    BeforePlaying.NobodyJoined -> say(Phrases.before_play_nobody)
                    BeforePlaying.OwnLead -> say(Phrases.before_play_own_lead)
                    is BeforePlaying.Uncalibrated -> say(Phrases.before_play_uncalibrated, ask.name)
                },
                cancel = say(Phrases.before_play_cancel),
                fix = if (ask == BeforePlaying.NobodyJoined) null else say(Phrases.before_play_calibrate),
                goOn = say(Phrases.before_play_go),
                onCancel = { asking = emptyList() },
                onFix = {
                    asking = emptyList()
                    if (ask is BeforePlaying.Uncalibrated) measuring = MeasureJob.PAIR to ask.peerId
                },
                onGoOn = {
                    asking = asking.drop(1)
                    if (asking.isEmpty()) goOnToPlaying()
                }
            )
        }
    }
}

/**
 * Nobody has picked a role yet - the handset's WelcomeScreen: what this is for in one line, then
 * each role as a box with its own sentence, the whole box the click. Every sentence is the
 * handset's own, so the two say the same thing.
 */
@Composable
private fun RolePicker(onHost: () -> Unit, onSink: () -> Unit) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            say(Phrases.welcome_what),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            lineHeight = 30.sp
        )
        Note(say(Phrases.welcome_role))
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoleBox(say(Phrases.role_host), say(Phrases.role_host_hint), onHost)
        RoleBox(say(Phrases.role_sink), say(Phrases.role_sink_hint), onSink)
    }
    Note(say(Phrases.welcome_role_later))
}

@Composable
private fun RoleBox(name: String, hint: String, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick)) {
        Framed {
            BoxTitle(name, strong = true)
            Note(hint)
        }
    }
}

/**
 * The host's board - the handset's ReadyScreen: the code and addresses a device joins by, the
 * devices that have, and the calibration, drawn as the one box on the page that is the point of it.
 * What the room plays is not here: that is changed while it plays, on the playing page.
 */
@Composable
private fun HostReady(
    host: HostSession,
    status: HostStatus,
    sessions: CoroutineDispatcher,
    onMeasure: (MeasureJob, String?) -> Unit
) {
    Note(say(Phrases.pc_phones_follow))
    val addresses = remember { LocalNetworks.toOffer() }
    // The handset's word for joining names in a sentence: 、 in Chinese, a comma in English.
    val join = say(Phrases.room_volume_name_join)
    if (addresses.isNotEmpty()) Note(say(Phrases.pc_type_this, addresses.joinToString(join) { it.address }))
    PairCode(host, status.open, addresses, sessions)
    Roster(status) { peerId -> onMeasure(MeasureJob.PAIR, peerId) }
    // The handset's 位置同步校准, which opens the measuring page rather than starting anything: a
    // minute of chirps is asked for there, with the room's volumes in view.
    Label(say(Phrases.calibrate_section))
    Framed(strong = true) {
        BoxTitle(say(Phrases.goto_room), strong = true)
        Note(say(Phrases.goto_room_hint))
        Column(Modifier.padding(top = 7.dp)) {
            Solid(say(Phrases.goto_room_go), enabled = status.open) { onMeasure(MeasureJob.ROOM, null) }
        }
    }
}

/**
 * The host's playing page - the handset's PlayingScreen: the three sources as one row, what is
 * playing and the way to start or stop it, then the room, its effects and every device's volume.
 * [onPutDown] is called before a pick stops a playing room, so this page stays up.
 */
@Composable
private fun HostPlaying(
    host: HostSession,
    status: HostStatus,
    pick: HostPick,
    sessions: CoroutineDispatcher,
    details: Boolean,
    onPutDown: () -> Unit
) {
    var programs by remember { mutableStateOf<List<AudioSession>?>(null) }
    // What is ticked in the list while it is open; the pick changes only at 确定.
    var ticked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var tickedEverything by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val english = LocalEnglish.current

    // Choosing something else while the room plays is what the handset host's putDownWhatIsPlaying
    // does: the room stops, and the new pick waits for 开始. Only once something is actually chosen -
    // a dialog closed on nothing, or the program list opened and left, changes nothing.
    val putDown = {
        if (status.playing) {
            onPutDown()
            scope.launch(sessions) { host.stopPlaying() }
        }
    }

    // Which cell is filled is what was picked, not the last cell pressed: pressing 文件夹 and
    // closing the dialog on nothing leaves the songs that were there.
    Segmented(
        listOf(
            Segment(say(Phrases.song_pick_one), pick.kind == SourceKind.SONGS) {
                pickSongs(Phrases.pc_pick_songs_title.of(english)).takeIf { it.isNotEmpty() }?.let {
                    putDown()
                    pick.files = it
                    pick.apps = emptyList()
                    pick.everything = false
                    pick.kind = SourceKind.SONGS
                    programs = null
                    val count = it.size
                    val only = if (count == 1) it.single().name else null
                    pick.picked = { e -> only ?: Phrases.pc_songs_count.of(e, count) }
                }
            },
            Segment(say(Phrases.song_pick_many), pick.kind == SourceKind.FOLDER) {
                pickFolder(Phrases.pc_pick_folder_title.of(english))?.let { folder ->
                    putDown()
                    val songs = songsIn(folder)
                    pick.files = songs
                    pick.apps = emptyList()
                    pick.everything = false
                    pick.kind = SourceKind.FOLDER
                    programs = null
                    pick.picked = { e ->
                        if (songs.isEmpty()) Phrases.pc_folder_empty.of(e, folder.name)
                        else Phrases.pc_folder_songs.of(e, folder.name, songs.size)
                    }
                }
            },
            Segment(say(Phrases.song_pick_capture), pick.kind == SourceKind.APP) {
                // Ticked as it is being played from, so reopening the list shows what it was.
                ticked = if (pick.kind == SourceKind.APP) pick.apps.map { it.pid }.toSet() else emptySet()
                tickedEverything = pick.kind == SourceKind.APP && pick.everything
                // Asked afresh each time: the mixer's rows come and go with what is playing.
                scope.launch { programs = withContext(sessions) { runCatching { AudioSessions.list() }.getOrDefault(emptyList()) } }
            }
        ),
        Modifier.padding(top = 4.dp)
    )
    programs?.let { list ->
        Note(say(Phrases.pc_which_program))
        // First, and on its own: it takes the programs below and any started later, so it and
        // the programs are one or the other - ticking either unticks the rest.
        Line {
            LineName(say(Phrases.pc_everything))
            Checkbox(checked = tickedEverything, onCheckedChange = { tick ->
                tickedEverything = tick
                if (tick) ticked = emptySet()
            })
        }
        if (list.isEmpty()) Note(say(Phrases.pc_no_programs))
        // One to a line: a browser, a player and a call can all be in the mixer at once.
        for (program in list) {
            Line {
                LineName(if (program.playing) say(Phrases.pc_program_playing, program.name) else program.name)
                Checkbox(checked = program.pid in ticked, onCheckedChange = { tick ->
                    ticked = if (tick) ticked + program.pid else ticked - program.pid
                    if (tick) tickedEverything = false
                })
            }
        }
        // Nothing changes until 确定, so 取消 leaves the room playing what it was.
        // One question with two answers, so side by side rather than stacked - see [Solid].
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ghost(say(Phrases.pc_cancel), modifier = Modifier.weight(1f)) { programs = null }
            val chosen = list.filter { it.pid in ticked }
            Solid(
                say(Phrases.pc_confirm),
                enabled = tickedEverything || chosen.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                putDown()
                pick.everything = tickedEverything
                pick.apps = if (tickedEverything) emptyList() else chosen
                pick.files = emptyList()
                pick.kind = SourceKind.APP
                programs = null
                val join = Phrases.room_volume_name_join
                pick.picked = if (tickedEverything) { e -> Phrases.pc_everything.of(e) }
                else { e -> Phrases.pc_program_sound.of(e, chosen.joinToString(join.of(e)) { it.name }) }
            }
        }
    }
    status.problem?.let { Note(describe(it), Tone.WRONG) }
    // What is playing, big enough to be the page's subject - the handset's NowPlaying.
    Text(
        pick.picked(english),
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp
    )
    Line {
        LineName(say(Phrases.pc_play_here_too))
        Switch(checked = pick.alsoHere, onCheckedChange = { pick.alsoHere = it }, enabled = !status.playing)
    }
    if (status.playing) {
        status.playhead?.let { PlayControls(host, it, status.paused, sessions) }
        Ghost(say(Phrases.play_stop)) { scope.launch(sessions) { host.stopPlaying() } }
    } else {
        Solid(
            say(Phrases.play_start),
            enabled = status.open && !status.measure.running &&
                (pick.files.isNotEmpty() || pick.apps.isNotEmpty() || pick.everything)
        ) {
            val chosen = pick.apps
            val alsoHere = pick.alsoHere
            // What the handsets are told is playing: the programs' own names, or 所有声音.
            val name = if (pick.everything) Phrases.pc_everything.of(english)
            else chosen.joinToString(Phrases.room_volume_name_join.of(english)) { it.name }
            if (pick.everything) scope.launch(sessions) { host.playEverything(name, alsoHere) }
            else if (chosen.isNotEmpty()) scope.launch(sessions) { host.playApps(chosen, name, alsoHere) }
            else pick.files.takeIf { it.isNotEmpty() }?.let { files -> scope.launch(sessions) { host.play(files, 0, alsoHere) } }
        }
        if (status.measure.running) Note(say(Phrases.pc_measuring_now))
    }
    val join = say(Phrases.room_volume_name_join)
    if (status.skipped.isNotEmpty()) Note(say(Phrases.pc_skipped, status.skipped.joinToString(join)), Tone.WRONG)
    if (status.ended) Note(say(Phrases.pc_ended))
    HostDrawing(host, status.room, sessions, rememberRipple(status.playing), status.killedIds)
    MeasuredLines(host, status.room, details, sessions)
    Effects(host, status.room, sessions, details)
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

/**
 * The sink's board - the handset sink's ReadyScreen: which networks this machine is on, whether it
 * has found its host, and the way in by address when it has not. Looking for the host started when
 * the role was picked and goes on by itself.
 */
@Composable
private fun SinkReady(sink: SinkSession, status: SinkStatus, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    Networks()
    Label(say(Phrases.pair_title))
    Line(first = true) {
        Badge(status.selfId, status.selfPlace)
        LineName(say(Phrases.pc_my_number, PeerBadge.numberOf(status.selfId)))
    }
    Text(describe(status), style = MaterialTheme.typography.bodyMedium)
    status.hostName?.let { name ->
        Note(status.hostAddress?.let { say(Phrases.pc_following_at, name, it) } ?: say(Phrases.pc_following, name))
    }
    if (status.stage in NOT_YET_REACHED) {
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
                // The looking under way is stopped first: a session follows one host at a time.
                scope.launch(sessions) { sink.stop(); sink.start(address) }
            }
        }
        val own = remember { LocalNetworks.list() }
        if (LocalNetworks.elsewhere(address, own)) {
            Note(say(Phrases.pc_address_elsewhere, address, own.joinToString(say(Phrases.room_volume_name_join)) { it.address }), Tone.WRONG)
        }
    }
    if (status.volumePercent != SoftwareVolume.FULL) Note(say(Phrases.pc_volume_set, status.volumePercent))
}

/** Before a host has been reached, when an address typed in by hand is the other way in. */
private val NOT_YET_REACHED = setOf(SinkStage.IDLE, SinkStage.FINDING, SinkStage.NOT_FOUND, SinkStage.REACHING, SinkStage.FAILED)

/** The sink's playing page: where it has got to, whom it follows, and the host's room, read only. */
@Composable
private fun SinkPlaying(status: SinkStatus, details: Boolean) {
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
    if (status.phones.isEmpty()) Note(say(Phrases.roster_nobody))
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
    if (shown.mode == SpatialMode.PAN) SourceReadout(shown)
}

/**
 * Where the source dot has been put, in words the drawing cannot say - the handset's
 * SourceReadout: how much of the sense of direction a dot inside the ring has given up, and that a
 * dot pulled outside it with no reverb only gets quieter. What the dot is and how to move it is
 * already in 自定义声音位置's card, so the handset's first line is not repeated here.
 */
@Composable
private fun SourceReadout(room: RoomState) {
    val inside = SpatialRoom.envelopmentFor(room.retreat, room.envelopment)
    when {
        room.retreat > 0f -> Unit
        inside > 0f -> Note(say(Phrases.pc_source_inside, (inside / SpatialField.MAX_ENVELOPMENT.toFloat() * 100f).roundToInt()))
        else -> Note(say(Phrases.pc_source_here))
    }
    if (room.retreat > 0f && room.reverb <= 0f) Note(say(Phrases.pc_source_dry))
}

/**
 * The handset host's effect list: one card each, and the chosen one opens to say what it does and
 * hold what moves it. The words are the handset's, apart from the lines that say what an effect
 * does, which are written for a room that is not only handsets.
 */
@Composable
private fun Effects(host: HostSession, room: RoomState, sessions: CoroutineDispatcher, details: Boolean) {
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
    // Under the list rather than inside a card, as on the handset: they are about the room.
    if (details) LiveReadings(room)
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

/**
 * The handset host's 实时读数: what the rule asks of each device right now, bigger where the sound
 * should be - so that "the sound seems to come from over there" can be checked against the app's
 * own answer. Read off this machine's clock, which here is the host clock the rule runs on. Only
 * with 显示诊断细节 on, and worked out five times a second, as often as the handset's screen does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveReadings(room: RoomState) {
    val readings by produceState(emptyList<RoomReading>(), room) {
        while (true) {
            value = runCatching { ruleOf(room) }.getOrNull()?.let { roomReadings(it, System.nanoTime()) }.orEmpty()
            delay(READINGS_EVERY_MILLIS)
        }
    }
    if (readings.isEmpty()) return
    Label(say(Phrases.room_live))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        for (reading in readings) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge(reading.peerId, room.colours[reading.peerId])
                Text(say(Phrases.room_live_row, reading.loudness), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private const val READINGS_EVERY_MILLIS = 200L

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
            addresses = withContext(Dispatchers.IO) { LocalNetworks.toOffer() }
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
    // Where it opens full size: the screen that was clicked on, which is the one this window is on.
    var wholeScreen by remember { mutableStateOf<java.awt.Rectangle?>(null) }
    CodePicture(modules, Modifier.padding(top = 6.dp).size(PAIR_CODE_SIZE).clickable {
        wholeScreen = java.awt.MouseInfo.getPointerInfo()?.device?.defaultConfiguration?.bounds
    })
    Note(say(Phrases.pc_code_how))
    Note(say(Phrases.pc_code_address, where.address))
    wholeScreen?.let { screen ->
        CodeScreen(modules, say(Phrases.pc_code_address, where.address), screen) { wholeScreen = null }
    }
}

/** The code's modules, black on white, filling [modifier]'s square. */
@Composable
private fun CodePicture(modules: Array<BooleanArray>, modifier: Modifier) {
    Canvas(modifier.background(Color.White)) {
        val cell = size.width / modules.size
        for (y in modules.indices) {
            for (x in modules[y].indices) {
                if (modules[y][x]) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/**
 * The code held up to a camera from a metre away, and nothing else - the handset's
 * PairCodeScreen, over the whole of [screen]. The address it carries is written under it, as in
 * the pane, since a wrong pick of adapter is only visible that way. 返回 or Esc closes it.
 */
@Composable
private fun CodeScreen(modules: Array<BooleanArray>, address: String, screen: java.awt.Rectangle, onClose: () -> Unit) {
    // Carried over by hand: what the window is themed and worded in belongs to the one it opened from.
    val colours = MaterialTheme.colorScheme
    val english = LocalEnglish.current
    Window(
        onCloseRequest = onClose,
        title = say(Phrases.pair_code_fullscreen),
        icon = AppIcon.painter,
        undecorated = true,
        alwaysOnTop = true,
        // AWT's units here are the window's own dp - see ScreenEdges.
        state = rememberWindowState(
            position = WindowPosition(screen.x.dp, screen.y.dp),
            size = DpSize(screen.width.dp, screen.height.dp)
        ),
        onKeyEvent = { if (it.key == Key.Escape) { onClose(); true } else false }
    ) {
        LaunchedEffect(window) { AppIcon.dress(window) }
        CompositionLocalProvider(LocalEnglish provides english) {
            MaterialTheme(colorScheme = colours) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val side = minOf(maxWidth, maxHeight) * 0.7f
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            BoxTitle(say(Phrases.pair_code_fullscreen), strong = true)
                            CodePicture(modules, Modifier.size(side))
                            Note(address)
                            Ghost(say(Phrases.back), onClick = onClose)
                        }
                    }
                }
            }
        }
    }
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
    SinkStage.STANDING_BY -> say(Phrases.standby_sink) + when (status.problem) {
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
