package com.soundmesh.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.BuildConfig
import com.soundmesh.probe.R

/** The three tabs of the playing stage's bottom bar. */
enum class PlayTab { ROOM, VOLUME, PLAY }

/**
 * Stage three: the room is playing, and everything on this screen is something to do about it
 * right now.
 *
 * Replaces the flat [HostPanel]/[SinkPanel] page for this stage, the same way [ReadyScreen]
 * already replaced it for [HomeRoute.READY]. That flat page ran four to six screens deep - the
 * pairing code sat past everything else, and every one of a room's handsets spent a whole slider's
 * worth of height on its own volume row - so this stage is cut into three tabs behind a bottom bar
 * instead of one long column.
 *
 * Defaults to [PlayTab.PLAY]: the first thing somebody wants right after pressing start is to stop
 * it again or skip a song, not the room diagram or the volume sliders.
 */
@Composable
fun PlayingScreen(
    state: HomeState,
    actions: HomeActions,
    showDetails: Boolean,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableStateOf(PlayTab.PLAY) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == PlayTab.ROOM,
                    onClick = { tab = PlayTab.ROOM },
                    icon = { Text("◇") },
                    label = { Text(stringResource(R.string.tab_room)) }
                )
                NavigationBarItem(
                    selected = tab == PlayTab.VOLUME,
                    onClick = { tab = PlayTab.VOLUME },
                    icon = { Text("♪") },
                    label = { Text(stringResource(R.string.tab_volume)) }
                )
                NavigationBarItem(
                    selected = tab == PlayTab.PLAY,
                    onClick = { tab = PlayTab.PLAY },
                    icon = { Text("▶") },
                    label = { Text(stringResource(R.string.tab_play)) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (tab) {
                // Not wrapped in another Section: the room drawing already draws its own, and this
                // tab is that one panel filling the whole tab rather than one entry among others.
                PlayTab.ROOM -> state.room?.let { SpatialPanel(it, actions.room) }
                PlayTab.VOLUME -> {
                    state.hostOutputVolume?.let { HostOutputVolumePanel(it) }
                    RoomVolumePanel(state, actions)
                }
                PlayTab.PLAY -> {
                    PlayControls(state, actions, canPlay = canPlay(state))
                    CaptureSilenceLine(state)
                    StandbyLine(state, actions)
                    // The checklist row that used to offer this (ReadyGoto.SHOW_CODE) disappears
                    // the moment the first handset joins - it only shows while standingBy == 0 -
                    // but a room gains its third phone after it has started playing, not before.
                    // Without a standing entry here the pairing code had no way to be shown again.
                    if (state.role == Role.HOST) {
                        OutlinedButton(onClick = actions.showPairCode, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.play_show_code))
                        }
                    }
                }
            }
            // Gated on a setting rather than an on-screen expander: this is the one block on the
            // playing stage nobody but a person troubleshooting a room wants to see at all, so it
            // is turned on or off from Settings rather than tapped open here every time.
            if (showDetails) {
                Section(R.string.details_show) {
                    StatePanel(state)
                    HealthPanel(state.health)
                    Text(
                        stringResource(R.string.home_build, BuildConfig.BUILD_MARK),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/**
 * Whether this handset can be started, mirrored from what [HostPanel]/[SinkPanel] used to compute
 * inline for the same button.
 */
private fun canPlay(state: HomeState): Boolean = when (state.role) {
    Role.HOST -> state.capturing || state.songName != null
    Role.SINK -> state.paired != null
    Role.NONE -> false
}
