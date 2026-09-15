package com.soundmesh.product

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.LocalAddress
import com.soundmesh.probe.sync.PairingCodeImage

/**
 * The code a peer scans, on the status screen and never behind a button.
 *
 * It used to be one tap away, and worse, the tap was a checklist row that disappeared the moment
 * the first handset joined - so a room gaining its third phone had no way to show the code at all.
 * It is the one thing on this screen somebody points another phone's camera at, so it is drawn.
 *
 * The full-screen version stays, because reading one from a metre away is a different job.
 */
@Composable
fun PairCodeSection(state: HomeState, actions: HomeActions) {
    Section(R.string.pair_code_title) {
        // Only where there is a choice to make. A handset with one address up is reachable at it
        // whatever anybody picks, and a control that cannot change the answer is a control that
        // teaches people their taps do nothing.
        if (state.codeChoices.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in state.codeChoices) {
                    FilterChip(
                        selected = state.pairingOffer?.by == choice,
                        onClick = { actions.setCodeNetwork(choice) },
                        label = { Text(stringResource(networkWord(choice))) }
                    )
                }
            }
            Text(
                stringResource(R.string.pair_code_switch_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val offer = state.pairingOffer
        if (offer == null) {
            Text(
                stringResource(R.string.pair_code_nowhere),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            return@Section
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Remembered against the payload rather than the recomposition: encoding one is the
            // most expensive thing this screen does, for a picture that only changes when the
            // address does, and this screen redraws on a poll.
            val code = remember(offer.payload) {
                PairingCodeImage.bitmap(offer.payload, PairingCodeImage.DEFAULT_PIXELS).asImageBitmap()
            }
            Image(code, contentDescription = null, modifier = Modifier.size(INLINE_CODE_SIZE))
            TextButton(onClick = actions.showPairCode) {
                Text(stringResource(R.string.pair_code_enlarge))
            }
        }
        Text(
            stringResource(
                when (offer.by) {
                    LocalAddress.ReachedBy.HOTSPOT -> R.string.pair_code_by_hotspot
                    LocalAddress.ReachedBy.WIFI -> R.string.pair_code_by_wifi
                }
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** What one of the two networks is called where somebody is choosing between them. */
private fun networkWord(by: LocalAddress.ReachedBy): Int = when (by) {
    LocalAddress.ReachedBy.HOTSPOT -> R.string.pair_code_choose_hotspot
    LocalAddress.ReachedBy.WIFI -> R.string.pair_code_choose_wifi
}

/**
 * Big enough to scan from arm's length, small enough to leave the rest of the screen readable.
 *
 * A metre away is what the full-screen version is for - see [PairCodeSection].
 */
private val INLINE_CODE_SIZE = 140.dp
