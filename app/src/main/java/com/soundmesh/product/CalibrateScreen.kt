package com.soundmesh.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.soundmesh.probe.R

/**
 * What the calibration screen draws.
 *
 * [stored] is the constant this handset already carries, and it is the difference between offering
 * to measure and offering to check: there is nothing to verify on a phone that has never been
 * calibrated, so that button is only there once there is an answer for it to test.
 */
data class CalibrateState(
    val running: Boolean = false,
    val message: String? = null,
    val stored: Long? = null
)

class CalibrateActions(
    val calibrate: () -> Unit,
    val verify: () -> Unit
)

@Composable
fun CalibrateScreen(state: CalibrateState, actions: CalibrateActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.calibrate_title), style = MaterialTheme.typography.headlineMedium)
        Section(R.string.calibrate_what) {
            Text(stringResource(R.string.calibrate_intro), style = MaterialTheme.typography.bodyMedium)
            Text(
                state.stored?.let { stringResource(R.string.calibrate_stored, it / 1000.0) }
                    ?: stringResource(R.string.calibrate_unmeasured),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Section(R.string.calibrate_run) {
            Text(stringResource(R.string.calibrate_quiet), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = actions.calibrate,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.calibrate_start))
            }
            // Only once there is something to check. A verification with nothing stored applies
            // nothing and measures the whole difference again, which reads like a failed check.
            if (state.stored != null) {
                OutlinedButton(
                    onClick = actions.verify,
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.calibrate_verify))
                }
                Text(stringResource(R.string.calibrate_verify_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        state.message?.let {
            Section(R.string.calibrate_result) {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
