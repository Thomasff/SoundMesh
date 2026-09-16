package com.soundmesh.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.R

/**
 * What the output-lead screen draws.
 *
 * [stored] is the constant this handset is playing off, and it is the whole top of the screen: a
 * number and whether there is one. [offered] is a measurement that has finished and has not been
 * adopted - see [landingFor] for when a run produces one rather than simply storing itself.
 *
 * [message] is for runs that came to nothing: a refusal, or a throw. A run that worked says so by
 * changing the number above, not by adding a sentence saying it just did.
 */
data class CalibrateState(
    val running: Boolean = false,
    val message: String? = null,
    val stored: Long? = null,
    val offered: Long? = null
)

class CalibrateActions(
    val calibrate: () -> Unit,
    /** Adopt [CalibrateState.offered] in place of what this handset is playing off. */
    val replace: () -> Unit,
    /** Drop it. What was stored stays stored, which is what happens if nobody presses anything. */
    val keep: () -> Unit,
    val back: () -> Unit
)

/** What a finished measurement leaves behind: what to store now, and what to put to the person. */
data class LeadLanding(val store: Long?, val offer: Long?)

/**
 * Whether a measurement stores itself or waits to be chosen.
 *
 * With nothing stored there is no second number to weigh it against, so asking would be asking
 * somebody to choose between a measurement and nothing at all - and the handset would meanwhile
 * keep playing off a zero it has no reason to prefer. With something stored, the one reason to
 * measure again is that the two might differ, and which of them this handset plays off is then a
 * decision rather than a side effect of pressing start.
 *
 * [keeps] is false for the two runs that are not about the constant: a verification measures what
 * is left over after the stored answer is applied, and a repeatability run measures the reference
 * path against itself. Writing either one where the constant lives would quietly wreck it.
 */
internal fun landingFor(measured: Long?, stored: Long?, keeps: Boolean): LeadLanding = when {
    measured == null || !keeps -> LeadLanding(null, null)
    stored == null || stored == 0L -> LeadLanding(measured, null)
    else -> LeadLanding(null, measured)
}

@Composable
fun CalibrateScreen(state: CalibrateState, actions: CalibrateActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Android 15 draws every app edge to edge, so without this the title sits under the
            // status bar clock. Visible on the Magic6 and not on the X10, which is Android 10.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        // Nothing between blocks: a label carries its own space above it. See Look.kt.
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        PageBar(R.string.calibrate_title, actions.back)
        Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Tag(
                stringResource(
                    if (state.stored != null) R.string.calibrate_have else R.string.calibrate_have_not
                ),
                if (state.stored != null) Tone.GOOD else Tone.QUIET
            )
            state.stored?.let {
                Text(
                    stringResource(R.string.calibrate_value, it / 1000.0),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        // The same sentence the status screen puts under this measurement's entry, off the same
        // string: two wordings for one fact is how a screen ends up arguing with the one before it.
        Note(stringResource(R.string.goto_self_hint))
        Label(R.string.calibrate_again)
        Framed(strong = state.offered != null || state.running) {
            when {
                state.offered != null -> Offer(state, actions)
                state.running -> Measuring()
                else -> {
                    Note(stringResource(R.string.calibrate_quiet))
                    Column(modifier = Modifier.padding(top = 7.dp)) {
                        Solid(stringResource(R.string.calibrate_start), onClick = actions.calibrate)
                    }
                }
            }
        }
        Note(stringResource(R.string.calibrate_retest))
        // Only ever a refusal or a failure - see [CalibrateState.message].
        state.message?.let { Note(it, Tone.WATCH) }
    }
}

/**
 * The two numbers side by side, and the one decision between them.
 *
 * Nothing is stored until [CalibrateActions.replace]: a person who walked away from this screen
 * has left the handset playing off the answer it already had, which is the outcome that cannot be
 * wrong by accident.
 */
@Composable
private fun Offer(state: CalibrateState, actions: CalibrateActions) {
    Line(first = true) {
        LineName(stringResource(R.string.calibrate_fresh))
        state.offered?.let { Tag(stringResource(R.string.calibrate_value, it / 1000.0)) }
    }
    Line {
        LineName(stringResource(R.string.calibrate_in_force), quiet = true)
        Tag(
            state.stored?.let { stringResource(R.string.calibrate_value, it / 1000.0) }
                ?: stringResource(R.string.calibrate_have_not)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Solid(
            stringResource(R.string.calibrate_replace),
            modifier = Modifier.weight(1f),
            onClick = actions.replace
        )
        Ghost(
            stringResource(R.string.calibrate_keep),
            modifier = Modifier.weight(1f),
            onClick = actions.keep
        )
    }
}

/**
 * What is worth reading while a minute and a half of quiet room goes by.
 *
 * No count of seconds, unlike the room round on the other calibration screen. That one is handed
 * the instant its step ends by the thing running it; this one is not, and a number made up here
 * would be believed by the one person in the room who must not move yet.
 */
@Composable
private fun Measuring() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.calibrate_measuring),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        Note(stringResource(R.string.calibrate_measuring_long))
    }
}
