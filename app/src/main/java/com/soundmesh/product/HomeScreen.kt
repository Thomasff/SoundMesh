package com.soundmesh.product

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundmesh.core.PairingCode
import com.soundmesh.core.SessionState
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.PairingCodeImage

/** Which half of the pair this handset is being right now. Not kept across launches - see below. */
enum class Role { NONE, HOST, SINK }

/**
 * Everything the screen draws, and nothing it decides.
 *
 * The role is in here rather than on disk on purpose: it answers "what is this phone being right
 * now", and swapping the two roles and trying again is the most common thing anybody does with
 * this project.
 */
data class HomeState(
    val role: Role = Role.NONE,
    val songName: String? = null,
    /**
     * Whether the host will stream what this phone is playing instead of a file it was handed.
     *
     * True only once the consent dialog has been answered and the projection exists, because a
     * screen that offered to play before then would be offering something that cannot start.
     */
    val capturing: Boolean = false,
    val checking: Boolean = false,
    val problem: Int? = null,
    val pairingPayload: String? = null,
    val paired: PairingCode? = null,
    val running: Boolean = false,
    val sessionState: SessionState? = null,
    val failure: String? = null,
    val counters: List<Counter> = emptyList(),
    val health: Health = Health(null, null, null, null),
    /**
     * The accessibility output's volume, on a host that is capturing and therefore heard on it.
     *
     * Null when nothing is being captured: that output carries nothing then, and a slider for a
     * silent stream is a control with no effect to observe. See [AccessibilityVolume].
     */
    val accessibilityVolume: OutputVolume? = null
)

/** What the screen can ask for. Held as one object so a preview can hand it empty lambdas. */
class HomeActions(
    val pickRole: (Role) -> Unit,
    val chooseSong: () -> Unit,
    val captureAudio: () -> Unit,
    val scan: () -> Unit,
    val play: () -> Unit,
    val stop: () -> Unit,
    val calibrate: () -> Unit,
    val setAccessibilityVolume: (Int) -> Unit
)

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Android 15 draws every app edge to edge, so without this the title sits under the
            // status bar clock. Visible on the Magic6 and not on the X10, which is Android 10.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        when (state.role) {
            Role.NONE -> RolePicker(actions)
            Role.HOST -> HostPanel(state, actions)
            Role.SINK -> SinkPanel(state, actions)
        }
        if (state.role != Role.NONE) {
            StatePanel(state)
            HealthPanel(state.health)
            TextButton(onClick = { actions.pickRole(Role.NONE) }) {
                Text(stringResource(R.string.role_change))
            }
        }
        // Offered whatever this phone is being. The constant it measures belongs to the handset
        // rather than to a role, and it is wanted before the first session rather than during one.
        TextButton(onClick = actions.calibrate) {
            Text(stringResource(R.string.home_calibrate))
        }
    }
}

/**
 * The volume of the output a capturing host is heard on.
 *
 * Here rather than left to the system's own panel because the panel cannot reach it: with a session
 * playing, the volume keys move the media stream. A listener found that out by hand, after
 * reporting that the host sounded quiet.
 */
@Composable
private fun AccessibilityVolumePanel(volume: OutputVolume, actions: HomeActions) {
    Section(R.string.volume_title) {
        if (!volume.settable) {
            Text(stringResource(R.string.volume_locked), style = MaterialTheme.typography.bodyMedium)
            return@Section
        }
        Text(
            stringResource(R.string.volume_level, volume.level, volume.max),
            style = MaterialTheme.typography.bodyLarge
        )
        Slider(
            value = volume.level.toFloat(),
            onValueChange = { actions.setAccessibilityVolume(it.toInt()) },
            valueRange = 0f..volume.max.toFloat(),
            steps = (volume.max - 1).coerceAtLeast(0)
        )
        Text(stringResource(R.string.volume_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RolePicker(actions: HomeActions) {
    Section(R.string.role_pick) {
        Button(onClick = { actions.pickRole(Role.HOST) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.role_host))
        }
        Text(stringResource(R.string.role_host_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { actions.pickRole(Role.SINK) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.role_sink))
        }
        Text(stringResource(R.string.role_sink_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HostPanel(state: HomeState, actions: HomeActions) {
    Section(R.string.song_title) {
        Text(
            when {
                state.checking -> stringResource(R.string.song_checking)
                state.capturing -> stringResource(R.string.song_capturing)
                state.songName != null -> state.songName
                else -> stringResource(R.string.song_none)
            },
            style = MaterialTheme.typography.bodyLarge
        )
        state.problem?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedButton(onClick = actions.chooseSong, enabled = !state.checking) {
            Text(stringResource(R.string.song_choose))
        }
        OutlinedButton(onClick = actions.captureAudio, enabled = !state.checking && !state.capturing) {
            Text(stringResource(R.string.song_capture))
        }
        if (state.capturing) {
            Text(stringResource(R.string.song_capture_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
    state.accessibilityVolume?.let { AccessibilityVolumePanel(it, actions) }
    Section(R.string.pair_code) {
        if (state.pairingPayload == null) {
            Text(stringResource(R.string.pair_no_address))
        } else {
            // Remembered against the payload rather than the recomposition: the screen redraws five
            // times a second and encoding a QR code that often would be the most expensive thing on
            // it, for a picture that changes only when the address does.
            val code = remember(state.pairingPayload) {
                PairingCodeImage.bitmap(state.pairingPayload, PairingCodeImage.DEFAULT_PIXELS).asImageBitmap()
            }
            Image(code, contentDescription = null, modifier = Modifier.size(240.dp))
            Text(state.pairingPayload, style = MaterialTheme.typography.bodySmall)
        }
    }
    PlayControls(state, actions, canPlay = state.capturing || state.songName != null)
}

@Composable
private fun SinkPanel(state: HomeState, actions: HomeActions) {
    Section(R.string.pair_title) {
        Text(
            state.paired?.let {
                stringResource(R.string.pair_with, it.hostId, it.address, it.chunkPort)
            } ?: stringResource(R.string.pair_none),
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedButton(onClick = actions.scan) { Text(stringResource(R.string.pair_scan)) }
        Text(stringResource(R.string.pair_scan_hint), style = MaterialTheme.typography.bodySmall)
    }
    PlayControls(state, actions, canPlay = state.paired != null)
}

@Composable
private fun PlayControls(state: HomeState, actions: HomeActions, canPlay: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = actions.play,
            enabled = canPlay && !state.running && !state.checking,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.play_start))
        }
        OutlinedButton(onClick = actions.stop, enabled = state.running, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.play_stop))
        }
    }
}

@Composable
private fun StatePanel(state: HomeState) {
    Section(R.string.state_title) {
        Text(
            if (!state.running && state.failure != null) {
                stringResource(R.string.state_failed, state.failure)
            } else {
                stringResource(StateWording.of(state.sessionState))
            },
            style = MaterialTheme.typography.titleMedium
        )
        for (counter in state.counters) Reading(stringResource(counter.label), counter.value)
    }
}

@Composable
private fun HealthPanel(health: Health) {
    val unknown = stringResource(R.string.health_unknown)
    Section(R.string.health_title) {
        Reading(
            stringResource(R.string.health_battery),
            health.batteryPercent?.let { "$it%" } ?: unknown
        )
        Reading(
            stringResource(R.string.health_charging),
            health.charging?.let { if (it) "✓" else "—" } ?: unknown
        )
        Reading(
            stringResource(R.string.health_temperature),
            health.celsius?.let { String.format(null as java.util.Locale?, "%.1f °C", it) } ?: unknown
        )
        Reading(
            stringResource(R.string.health_thermal),
            health.thermalStatus?.let { stringResource(StateWording.thermal(it)) } ?: unknown
        )
    }
}

/** One label and one number, the shape every readable line on this screen has. */
@Composable
private fun Reading(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
internal fun Section(title: Int, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
