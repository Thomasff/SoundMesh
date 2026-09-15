package com.soundmesh.product

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundmesh.probe.BuildConfig
import com.soundmesh.probe.R

/**
 * Stage three: the room is playing, and everything here is something to do about it right now.
 *
 * One column rather than the three tabs it had until 2026-09-15. The tabs were a way of fitting a
 * screen's worth of Material cards into a screen, and once the furniture went thin - see Look.kt -
 * the whole stage fits. It also fixes what the tabs cost: the room drawing and the volume rows are
 * the same phones said two ways, and on separate tabs you could never see the pair at once.
 *
 * The drawing sits above the volumes on purpose. A room of six handsets is six volume rows, and
 * under them the drawing would be off the bottom of a screen nobody scrolls that far down.
 */
@Composable
fun PlayingScreen(
    state: HomeState,
    actions: HomeActions,
    showDetails: Boolean,
    modifier: Modifier = Modifier
) {
    // The ring that spreads from this handset's own icon the moment play is pressed. Keyed on
    // state.running rather than on a click, so it fires the same way whether this phone started
    // the room or another one just told it to join.
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(state.running) {
        if (!state.running) return@LaunchedEffect
        ripple.snapTo(0f)
        ripple.animateTo(1f, animationSpec = tween(RIPPLE_DURATION_MILLIS))
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        if (state.role == Role.HOST) SourcePicker(state, actions)
        NowPlaying(state)
        PlayControls(state, actions, canPlay = canPlay(state))
        state.room?.let {
            Label(R.string.room_title)
            SpatialPanel(
                it,
                actions.room,
                blockedPeerNames = state.blockedPeerNames,
                // Null rather than the raw value while nothing is playing: the ripple means "just
                // started", and there is nothing on screen for it to mean that beside.
                ripple = ripple.value.takeIf { state.running }
            )
        }
        VolumeLines(state, actions)
        CaptureSilenceLine(state)
        StandbyLine(state, actions)
        // Gated on a setting rather than an on-screen expander: this is the one block here that
        // nobody but a person troubleshooting a room wants to see at all.
        if (showDetails) {
            Label(R.string.details_show)
            StatePanel(state)
            HealthPanel(state.health)
            Note(stringResource(R.string.home_build, BuildConfig.BUILD_MARK))
        }
    }
}

/**
 * The three sources, as one row of choices rather than three full-width buttons.
 *
 * Up here rather than back on the status screen because changing what the room is playing is a
 * thing people do while it is playing. Which one is filled is read off the state - a capture that
 * was consented to, a folder, a file - and not remembered from the button that was pressed.
 */
@Composable
private fun SourcePicker(state: HomeState, actions: HomeActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Choice(stringResource(R.string.song_pick_one), !state.capturing && !state.songIsFolder && state.songName != null, actions.chooseSong)
        Choice(stringResource(R.string.song_pick_many), !state.capturing && state.songIsFolder, actions.chooseFolder)
        Choice(stringResource(R.string.song_pick_capture), state.capturing, actions.captureAudio)
    }
}

/** What is playing, and where in the room's queue it is. */
@Composable
private fun NowPlaying(state: HomeState) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
        Text(
            state.nowPlaying ?: state.songName ?: stringResource(R.string.song_none),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp
        )
        Note(
            stringResource(
                R.string.play_line,
                state.standing.size + 1,
                stringResource(
                    when {
                        state.capturing -> R.string.song_pick_capture
                        state.songIsFolder -> R.string.song_pick_many
                        else -> R.string.song_pick_one
                    }
                )
            )
        )
    }
}

/**
 * Every phone's volume, one thin line each, this handset first.
 *
 * The top line is the whole room and the rest are one handset each - the same split the control
 * has always had, drawn so that the pair can be read together. The number on a handset's line is
 * what that handset says it actually landed on, never what it was told: a row that disagrees with
 * the room's line above it is the only way a stream that refused to move can be seen.
 */
@Composable
private fun VolumeLines(state: HomeState, actions: HomeActions) {
    val room = state.roomVolumePercent
    if (room == null && state.roomVolumes.isEmpty()) return
    Label(
        R.string.room_volume_title,
        trailing = if (state.volumeChanged) stringResource(R.string.room_volume_restore) else null
    )
    if (room != null) {
        VolumeLine(
            name = stringResource(R.string.room_volume_all),
            percent = room,
            colour = MaterialTheme.colorScheme.onSurface,
            strong = true,
            onSet = actions.setRoomVolume
        )
    }
    for (row in state.roomVolumes) {
        VolumeLine(
            name = row.name,
            percent = row.percent,
            colour = BadgePalette.colourOf(
                state.room?.colours?.get(row.peerId),
                MaterialTheme.colorScheme.onSurfaceVariant
            ),
            complaint = when (row.complaint) {
                VolumeComplaint.NONE -> null
                VolumeComplaint.NOT_SAID -> stringResource(R.string.room_volume_row_silent, row.name)
                VolumeComplaint.REFUSED ->
                    stringResource(R.string.room_volume_row_missed, row.name, row.asked ?: 0)
            }
        ) { actions.setHandsetVolume(row.peerId, it) }
    }
    if (state.capturing) Note(stringResource(R.string.room_volume_capturing_hint))
    if (state.volumeChanged) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            Ghost(stringResource(R.string.room_volume_restore), actions.restoreVolume)
        }
    }
}

/** One of a row of choices. Filled when it is the one in force. */
@Composable
internal fun Choice(text: String, chosen: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (chosen) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondary
        )
    }
}

/**
 * Whether this handset can be started, mirrored from what the flat host/sink page used to compute
 * inline for the same button.
 */
private fun canPlay(state: HomeState): Boolean = when (state.role) {
    Role.HOST -> state.capturing || state.songName != null
    Role.SINK -> state.paired != null
    Role.NONE -> false
}

/** How long the just-pressed-play ripple takes to spread out and fade. */
private const val RIPPLE_DURATION_MILLIS = 600
