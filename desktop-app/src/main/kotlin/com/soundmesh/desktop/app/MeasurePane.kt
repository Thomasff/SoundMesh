package com.soundmesh.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soundmesh.core.PeerBadge
import com.soundmesh.desktop.HostSession
import com.soundmesh.desktop.HostStatus
import com.soundmesh.desktop.MeasureJob
import com.soundmesh.desktop.MeasureStatus
import com.soundmesh.desktop.MicrophoneProblem
import com.soundmesh.product.QUIET_FLOOR_PERCENT
import com.soundmesh.product.RoomCheck
import com.soundmesh.product.RoomState
import com.soundmesh.product.VolumeRow
import com.soundmesh.product.delayLines
import com.soundmesh.product.fitOffer
import com.soundmesh.product.listenerLines
import com.soundmesh.product.measuredLines
import com.soundmesh.product.tooQuietFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The measuring window - the handset host's calibration screen, in its two jobs: 位置同步校准 for
 * the whole room, or one device's pair from its roster row.
 *
 * The room is one step here where the handset has two. Its first step holds the host over the
 * listener's head; at a computer the person is already sitting at the host, so the one round that
 * measures the devices measures the listener as well.
 */
@Composable
internal fun MeasurePane(
    host: HostSession,
    job: MeasureJob,
    aimedAt: String?,
    sessions: CoroutineDispatcher,
    details: Boolean
) {
    val status = polled { host.status() } ?: return
    val measure = status.measure
    val scope = rememberCoroutineScope()
    val rows = volumeRows(status)
    val tooQuiet = tooQuietFor(rows)
    // What the window shows as this job's answer: a round of the other job, or a pair with another
    // device, is somebody else's. A round that is running is shown whichever it is, with the stop
    // that ends it: the window can be opened on another device while one runs.
    val mine = measure.job == job && (job == MeasureJob.ROOM || measure.aimedAt == aimedAt)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (job == MeasureJob.ROOM) say(Phrases.goto_room)
            else say(Phrases.pc_pair_title, aimedAt?.let { name(status, it) } ?: ""),
            style = MaterialTheme.typography.titleMedium
        )
        if (job == MeasureJob.ROOM) Text(say(Phrases.room_calibrate_intro))
        VolumeGate(host, status, rows, tooQuiet, sessions)
        if (measure.running) {
            Running(measure)
        } else if (mine) {
            RoundResult(status, measure, job, tooQuiet) { scope.launch(sessions) { host.measureRoom() } }
        }
        if (mine) measure.microphone?.let { Text(describeHere(it, measure.microphoneDetail), color = MaterialTheme.colorScheme.error) }
        OutlinedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val canStart = status.open && !measure.running && tooQuiet.isEmpty()
                when (job) {
                    MeasureJob.ROOM -> {
                        Text(say(Phrases.pc_room_step), style = MaterialTheme.typography.titleSmall)
                        // The handset's second step, but for its first line: the host is not
                        // carried back to its place, it is where the listener sits.
                        for ((index, line) in listOf(
                            say(Phrases.pc_room_step_1),
                            say(Phrases.room_calibrate_step2_2),
                            say(Phrases.room_calibrate_step2_3)
                        ).withIndex()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(say(Phrases.room_calibrate_line, index + 1))
                                Text(line)
                            }
                        }
                        Button(onClick = { scope.launch(sessions) { host.measureRoom() } }, enabled = canStart) {
                            Text(say(Phrases.room_calibrate_step2_go))
                        }
                    }
                    MeasureJob.PAIR -> {
                        Text(say(Phrases.pair_calibrate_quiet))
                        Button(
                            onClick = { aimedAt?.let { peerId -> scope.launch(sessions) { host.measurePair(peerId) } } },
                            enabled = canStart && aimedAt != null
                        ) { Text(say(Phrases.pair_calibrate_start)) }
                    }
                }
                TooQuietNote(tooQuiet)
            }
        }
        if (measure.running) {
            StopControl(measure, measure.job ?: job) { scope.launch(sessions) { host.callOffMeasuring() } }
        }
        // Under everything rather than above it, as on the handset: it exists only once a round
        // has finished, and while one runs the thing worth reading is the count.
        if (job == MeasureJob.ROOM && mine && measure.roomMeasured) {
            Text(say(Phrases.pair_calibrate_room_drawing), style = MaterialTheme.typography.titleSmall)
            Text(say(Phrases.pair_calibrate_room_drawing_hint))
            HostDrawing(host, status.room, sessions)
            MeasuredLines(host, status.room, details, sessions)
        }
    }
}

/** What each device reads its volume as, this machine first - the handset's VolumeRow list. */
@Composable
private fun volumeRows(status: HostStatus): List<VolumeRow> {
    val self = status.selfId ?: return emptyList()
    return listOf(
        // SoundMesh's own volume: on a computer that is the only one a round plays at.
        VolumeRow(self, say(Phrases.pc_this_computer), status.volumePercent, status.volumePercent, 100, "SoundMesh")
    ) + status.phones.mapNotNull { phone ->
        phone.volumePercent?.let { VolumeRow(phone.peerId, phone.name, it, it, 100, "media") }
    }
}

/**
 * One number for the whole room and every device's own answer to it - the handset's VolumeGate.
 * A round nobody can hear is not worth running, and a device that did not move shows only in what
 * it read back.
 */
@Composable
private fun VolumeGate(
    host: HostSession,
    status: HostStatus,
    rows: List<VolumeRow>,
    tooQuiet: List<String>,
    sessions: CoroutineDispatcher
) {
    if (rows.isEmpty()) return
    val scope = rememberCoroutineScope()
    Text(say(Phrases.room_volume_gate_hint))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(say(Phrases.room_volume_unify), style = MaterialTheme.typography.titleSmall)
        Text(say(Phrases.room_volume_unify_advice))
    }
    VolumeLine(say(Phrases.room_volume_all), status.roomVolumePercent ?: status.volumePercent) {
        scope.launch(sessions) { host.setRoomVolume(it) }
    }
    Text(say(Phrases.room_volume_now), style = MaterialTheme.typography.titleSmall)
    val places = status.phones.associate { it.peerId to it.place } + (status.selfId to status.selfPlace)
    for (row in rows) {
        val quiet = row.percent < QUIET_FLOOR_PERCENT
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(row.peerId, places[row.peerId])
            Text(row.name)
            Text(
                say(if (quiet) Phrases.room_volume_too_quiet_row else Phrases.room_volume_level, row.percent),
                color = if (quiet) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (tooQuiet.isNotEmpty()) {
        Text(
            say(
                if (tooQuiet.size == 1) Phrases.room_volume_too_quiet_one else Phrases.room_volume_too_quiet_some,
                tooQuiet.joinToString(say(Phrases.room_volume_name_join)),
                QUIET_FLOOR_PERCENT
            ),
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** Why the start is grey, said under it rather than only beside the rows. */
@Composable
private fun TooQuietNote(tooQuiet: List<String>) {
    if (tooQuiet.isEmpty()) return
    Text(say(Phrases.room_volume_gate_shut, tooQuiet.size, QUIET_FLOOR_PERCENT), color = MaterialTheme.colorScheme.error)
}

/** 测量进行中: keep quiet, how long is left, and where the round has got to. */
@Composable
private fun Running(measure: MeasureStatus) {
    Text(say(Phrases.room_calibrate_now), style = MaterialTheme.typography.titleSmall)
    OutlinedCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(say(Phrases.room_calibrate_quiet), fontWeight = FontWeight.Bold)
            measure.untilLocalNanos?.let { HoldStill(it) }
            measure.line?.let { Text(describe(it)) }
        }
    }
}

/**
 * The count to the end of the recording, ticking on its own - the handset's HoldStill. The window
 * redraws on a change of status, and a count read only then does not move (the desktop sink's
 * countdown, removed 09-23 for exactly that). At zero it says the arithmetic is running, which
 * takes seconds and would otherwise read as a hang.
 */
@Composable
private fun HoldStill(untilNanos: Long) {
    var left by remember(untilNanos) { mutableStateOf(secondsLeft(untilNanos)) }
    LaunchedEffect(untilNanos) {
        while (left > 0) {
            delay(TICK_MILLIS)
            left = secondsLeft(untilNanos)
        }
    }
    Text(if (left > 0) say(Phrases.room_calibrate_left, left) else say(Phrases.calibrate_computing))
}

/** Whole seconds still to wait, rounded up and never below zero - the handset's secondsLeft. */
private fun secondsLeft(untilNanos: Long): Int {
    val left = (untilNanos - System.nanoTime()) / 1_000_000L
    if (left <= 0L) return 0
    return ((left + 999L) / 1000L).toInt()
}

private const val TICK_MILLIS = 500L

/** What the round came to, in the place the running box was, and measuring again beside it. */
@Composable
private fun RoundResult(
    status: HostStatus,
    measure: MeasureStatus,
    job: MeasureJob,
    tooQuiet: List<String>,
    again: () -> Unit
) {
    if (measure.line == null && measure.heard.isEmpty()) return
    Text(say(Phrases.pair_calibrate_result), style = MaterialTheme.typography.titleSmall)
    OutlinedCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            measure.line?.let { Text(describe(it), fontWeight = FontWeight.Medium) }
            for ((peerId, line) in measure.heard) {
                Text(say(Phrases.pair_calibrate_sink_line, name(status, peerId), describe(line)))
            }
            if (job == MeasureJob.ROOM && measure.roomMeasured) {
                OutlinedButton(onClick = again, enabled = tooQuiet.isEmpty()) { Text(say(Phrases.pc_room_again)) }
            }
        }
    }
}

/** The stop button and the one sentence true of it now - the handset's StopControl. */
@Composable
private fun StopControl(measure: MeasureStatus, job: MeasureJob, stop: () -> Unit) {
    OutlinedButton(onClick = stop) {
        Text(say(if (job == MeasureJob.ROOM) Phrases.pair_calibrate_room_call_off else Phrases.pair_calibrate_stop))
    }
    val hint = when {
        job == MeasureJob.ROOM && measure.underWay -> Phrases.pair_calibrate_room_under_way_hint
        job == MeasureJob.ROOM -> Phrases.pair_calibrate_room_call_off_hint
        measure.underWay -> Phrases.pair_calibrate_under_way
        else -> null
    }
    hint?.let { Text(say(it)) }
}

/** What a device calls itself on the roster, or its number when it has never said. */
private fun name(status: HostStatus, peerId: String): String =
    status.phones.firstOrNull { it.peerId == peerId }?.name ?: "${PeerBadge.numberOf(peerId)}"

/** This machine's microphone, from the host's side: nobody else is told, so nothing says "the host". */
@Composable
private fun describeHere(problem: MicrophoneProblem, detail: String?): String =
    if (problem == MicrophoneProblem.NO_DEVICE) say(Phrases.pc_mic_no_device_host) else describe(problem, detail)

/**
 * The room as it has been measured, under a drawing of it - the handset's MeasuredRoom: what
 * disagrees with the drawing, the distances to the listener and between the devices, the offer to
 * move the drawing onto them, and whether the near devices are being held back.
 */
@Composable
internal fun MeasuredLines(host: HostSession, room: RoomState, details: Boolean, sessions: CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    Text(say(Phrases.room_place_evenly))
    // Said and never acted on: a measurement can say how far apart two icons are, not which is
    // which - and it has to be settled before the fit, which is what fitOffer refuses on.
    RoomCheck.contradiction(room.icons, room.measuredMetres)?.let { (farther, nearer) ->
        Text(
            say(Phrases.room_disagrees, badgeWords(farther, room.colours[farther]), badgeWords(nearer, room.colours[nearer])),
            color = MaterialTheme.colorScheme.error
        )
    }
    val listener = listenerLines(room.icons, room.listenerMetres)
    if (listener.isEmpty()) {
        if (room.icons.size >= 2) Text(say(Phrases.room_listener_unmeasured))
    } else {
        Text(say(Phrases.room_listener_measured), style = MaterialTheme.typography.titleSmall)
        Readings(listener.map { (peerId, metres) -> listOf(peerId) to "%.2f".format(metres) }, room.colours)
        Text(say(Phrases.pc_listener_assumed))
    }
    val measured = measuredLines(room.icons, room.measuredMetres)
    if (measured.isNotEmpty()) {
        Text(say(Phrases.room_measured), style = MaterialTheme.typography.titleSmall)
        Readings(measured.map { (names, metres) -> listOf(names.first, names.second) to "%.2f".format(metres) }, room.colours)
    }
    if (room.fitted) {
        Text(say(Phrases.room_fit_done))
    } else if (fitOffer(room) != null) {
        Button(onClick = { scope.launch(sessions) { host.fitRoom() } }) { Text(say(Phrases.room_fit)) }
    }
    val delays = delayLines(room.icons, room.metresPerUnit)
    if (delays.isNotEmpty()) {
        OutlinedButton(onClick = { scope.launch(sessions) { host.setDelayCompensation(!room.delayCompensation) } }) {
            Text(say(if (room.delayCompensation) Phrases.room_delay_on else Phrases.room_delay_off))
        }
        if (room.delayCompensation && details) {
            Text(say(Phrases.room_arrival_delay), style = MaterialTheme.typography.titleSmall)
            Readings(delays.map { (peerId, millis) -> listOf(peerId) to "%.1f".format(millis) }, room.colours)
        }
    }
}

/** One measured number per entry, named by the badges it is about - the handset's Readings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Readings(rows: List<Pair<List<String>, String>>, colours: Map<String, Int>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((peerIds, written) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (peerId in peerIds) Badge(peerId, colours[peerId])
                Text(written, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A device in a sentence: its number and its colour's name - the handset's badgeWords. */
@Composable
internal fun badgeWords(peerId: String, place: Int?): String {
    val number = PeerBadge.numberOf(peerId)
    val colour = place?.let { BADGE_NAMES.getOrNull(it) } ?: return say(Phrases.badge_number_only, number)
    return say(Phrases.badge_in_words, number, say(colour))
}

/** The handset's names for the twelve colours, in BadgeHues' order. */
private val BADGE_NAMES = listOf(
    Phrases.badge_red, Phrases.badge_orange, Phrases.badge_yellow, Phrases.badge_lime,
    Phrases.badge_green, Phrases.badge_teal, Phrases.badge_cyan, Phrases.badge_sky,
    Phrases.badge_blue, Phrases.badge_purple, Phrases.badge_magenta, Phrases.badge_pink
)
