package com.soundmesh.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.core.CalibrationRole
import com.soundmesh.probe.R

/**
 * What the pair calibration screen draws.
 *
 * [role] comes from the pairing rather than from a picker, so the screen tells the person which
 * handset they are holding instead of asking. A role chosen by hand is a role chosen wrong once,
 * and what that produces is a correction filed against the wrong peer - applied silently on every
 * later session with nothing in the result to notice it by.
 *
 * [stored] is the constant this handset already carries for that peer, and it is the difference
 * between offering to measure and offering to check.
 */
data class PeerCalibrateState(
    val role: CalibrationRole? = null,
    val running: Boolean = false,
    val message: String? = null,
    val stored: Long? = null,
    val observations: Int = 0
)

class PeerCalibrateActions(
    val calibrate: () -> Unit,
    val verify: () -> Unit
)

@Composable
fun PeerCalibrateScreen(state: PeerCalibrateState, actions: PeerCalibrateActions) {
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
        Text(
            stringResource(R.string.pair_calibrate_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Section(R.string.pair_calibrate_what) {
            Text(
                stringResource(R.string.pair_calibrate_intro),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                when (state.role) {
                    CalibrationRole.HOST -> stringResource(R.string.pair_calibrate_role_host)
                    CalibrationRole.SINK -> stringResource(R.string.pair_calibrate_role_sink)
                    null -> stringResource(R.string.pair_calibrate_no_pairing)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            // Only the sink carries a constant: the correction lives on the handset that applies
            // it, filed under the one it follows.
            if (state.role == CalibrationRole.SINK) {
                Text(
                    state.stored?.let {
                        stringResource(R.string.pair_calibrate_stored, it / 1000.0, state.observations)
                    } ?: stringResource(R.string.pair_calibrate_unmeasured),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        if (state.role != null) {
            Section(R.string.pair_calibrate_run) {
                Text(
                    stringResource(R.string.pair_calibrate_quiet),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = actions.calibrate,
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pair_calibrate_start))
                }
                // Only once there is something to check. A verification with nothing stored
                // applies nothing and measures the whole difference again, which reads like a
                // failed check rather than like a pair nobody has measured.
                if (state.role == CalibrationRole.SINK && state.stored != null) {
                    OutlinedButton(
                        onClick = actions.verify,
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.pair_calibrate_verify))
                    }
                    Text(
                        stringResource(R.string.pair_calibrate_verify_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        state.message?.let {
            Section(R.string.pair_calibrate_result) {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
