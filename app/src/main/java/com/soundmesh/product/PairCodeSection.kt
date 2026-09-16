package com.soundmesh.product

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.HostPairingCode
import com.soundmesh.probe.sync.LocalAddress
import com.soundmesh.probe.sync.PairingCodeImage

/**
 * The code a peer scans, on the status screen and never behind a button.
 *
 * It used to be one tap away, and worse, the tap was a checklist row that disappeared the moment
 * the first handset joined - so a room gaining its third phone had no way to show the code at all.
 * It is the one thing on this screen somebody points another phone's camera at, so it is drawn,
 * and its height is fixed so that a long list of handsets underneath never pushes it off.
 *
 * Tapping it opens the full-screen version, because reading one from a metre away is another job.
 */
@Composable
fun PairCodeSection(state: HomeState, actions: HomeActions) {
    Label(R.string.pair_code_title)
    // Only where there is a choice to make. A handset with one address up is reachable at it
    // whatever anybody picks, and a control that cannot change the answer teaches people their
    // taps do nothing.
    if (state.codeChoices.size > 1) {
        Segmented(
            state.codeChoices.map { choice ->
                Segment(stringResource(networkWord(choice)), state.pairingOffer?.by == choice) {
                    actions.setCodeNetwork(choice)
                }
            },
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Note(stringResource(R.string.pair_code_switch_warning))
    }
    val offer = state.pairingOffer
    if (offer == null) {
        Note(stringResource(R.string.pair_code_nowhere), Tone.WRONG)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Remembered against the payload rather than the recomposition: encoding one is the most
        // expensive thing this screen does, for a picture that only changes when the address does,
        // and this screen redraws on a poll.
        val code = remember(offer.payload) {
            PairingCodeImage.bitmap(offer.payload, PairingCodeImage.DEFAULT_PIXELS).asImageBitmap()
        }
        Image(
            code,
            contentDescription = null,
            modifier = Modifier.size(CODE_SIZE).clickable(onClick = actions.showPairCode)
        )
        Text(
            stringResource(R.string.pair_code_how),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(
                when (offer.by) {
                    LocalAddress.ReachedBy.HOTSPOT -> R.string.pair_code_by_hotspot
                    LocalAddress.ReachedBy.WIFI -> R.string.pair_code_by_wifi
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

/** What one of the two networks is called where somebody is choosing between them. */
private fun networkWord(by: LocalAddress.ReachedBy): Int = when (by) {
    LocalAddress.ReachedBy.HOTSPOT -> R.string.pair_code_choose_hotspot
    LocalAddress.ReachedBy.WIFI -> R.string.pair_code_choose_wifi
}

/**
 * The pairing code, held up to a camera from a metre away, and nothing else.
 *
 * Its own screen rather than a block on the checklist: the checklist is meant to be read close up
 * and scrolled, and the one thing worth doing to a code that is about to be scanned is making it
 * as large as the screen allows and removing everything a scroll could hide it behind.
 *
 * Two places open it: tapping the code on the status screen, and [ShowCodeActivity], which is the
 * same screen reached by name from the tools. One spelling rather than two, for the same reason
 * [HostPairingCode] is one place - the one thing a code cannot afford is to be almost right.
 *
 * [offer] is null exactly when [HomeState.pairingOffer] is: no address this handset could be
 * reached on, or two of them with nothing said about which. Said out loud rather than drawn as an
 * empty screen, because a phone with no network and a phone with two of them show the same
 * nothing, and only one of them is worth walking over to fix.
 *
 * The network the code is good on is written under it, and that is not decoration. A handset
 * running its access point while joined to a network has two addresses and the code can only
 * carry one; the app cannot know which of them the phone doing the scanning can see, so the only
 * thing that makes a wrong pick visible is saying out loud which one this is.
 */
@Composable
fun PairCodeScreen(offer: HostPairingCode.Offer?, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BoxTitle(stringResource(R.string.pair_code_fullscreen), strong = true)
        if (offer == null) {
            Note(stringResource(R.string.pair_code_nowhere), Tone.WRONG)
        } else {
            // Remembered against the payload rather than the recomposition, for the same reason
            // the status screen's own code does it: encoding one is the most expensive thing this
            // screen does, for a picture that only changes when the address does.
            val code = remember(offer.payload) {
                PairingCodeImage.bitmap(offer.payload, PairingCodeImage.DEFAULT_PIXELS).asImageBitmap()
            }
            Image(
                code,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
            )
            Note(
                stringResource(
                    when (offer.by) {
                        LocalAddress.ReachedBy.HOTSPOT -> R.string.pair_code_by_hotspot
                        LocalAddress.ReachedBy.WIFI -> R.string.pair_code_by_wifi
                    }
                )
            )
        }
        Ghost(stringResource(R.string.back), modifier = Modifier.padding(top = 22.dp), onClick = onBack)
    }
}

/**
 * Big enough to scan from arm's length, small enough to leave the rest of the screen readable.
 *
 * A metre away is what the full-screen version is for - see [PairCodeSection].
 */
private val CODE_SIZE = 148.dp
