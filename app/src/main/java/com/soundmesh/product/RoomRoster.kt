package com.soundmesh.product

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.core.RoomExcuse
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.Carried

/**
 * Every phone in the room, one line each, with what is wrong with it on its own line.
 *
 * This is where the three counts on the checklist stop being enough. "两台没校准" is true and
 * useless: what fixes it is walking over to one particular handset, and a count cannot say which.
 * Same for the microphone permission a round was refused for, and for a handset that has gone
 * quiet - each of them is a fact about one phone.
 *
 * This handset is the first row, always, and is called 本机 rather than named: a person holding
 * it needs to find it in the list before anything else, and its name is on the row above anyway.
 */
@Composable
fun RoomRoster(state: HomeState, actions: HomeActions) {
    Section(R.string.roster_title) {
        SelfRow(state)
        for (row in state.standing) StandingLine(row, actions)
        if (state.role == Role.HOST && state.standing.isEmpty()) {
            Text(
                stringResource(R.string.roster_nobody),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** This handset's own line. No calibration entry: nothing here is measured against itself. */
@Composable
private fun SelfRow(state: HomeState) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BadgeChip(state.selfId ?: "", state.selfPlace, diameter = 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            state.calledHere.ifEmpty { stringResource(R.string.roster_self) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(R.string.roster_self),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One standing handset: what it is called, how it is lined up, and the way to fix that.
 *
 * The entry appears only where there is something to fix. A handset that is already carrying its
 * own measurement says so and offers nothing - which is the whole difference between a status
 * line and a menu, and the reason this screen can be read at a glance rather than worked through.
 */
@Composable
private fun StandingLine(row: StandingRow, actions: HomeActions) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BadgeChip(row.peerId, null, diameter = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(row.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(carryingWord(row.carrying)),
                style = MaterialTheme.typography.bodySmall,
                color = if (row.carrying == Carried.SOMETHING) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
            if (row.carrying != Carried.SOMETHING) {
                TextButton(onClick = { actions.calibratePeer(row.peerId) }) {
                    Text(stringResource(R.string.roster_calibrate))
                }
            }
        }
        // Underneath rather than beside: these are sentences, and a sentence sharing a row with a
        // name and a button is a sentence that wraps into three words per line.
        val notes = buildList {
            if (row.quiet) add(stringResource(R.string.roster_quiet))
            row.excuse?.let { add(stringResource(excuseWord(it))) }
        }
        if (notes.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 30.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (note in notes) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

/**
 * What one handset's answer is called on screen.
 *
 * [Carried.UNSAID] is not [Carried.NOTHING] and is not written as if it were: it is a build from
 * before that message, and telling somebody it carries nothing would send them to recalibrate a
 * handset that is very likely already fine.
 */
@StringRes
private fun carryingWord(carrying: Carried): Int = when (carrying) {
    Carried.SOMETHING -> R.string.roster_calibrated
    Carried.APPROXIMATE -> R.string.roster_approximate
    Carried.NOTHING -> R.string.roster_uncalibrated
    Carried.UNSAID -> R.string.roster_unsaid
}

/**
 * The room's words for a refusal, on the host's screen this time.
 *
 * The same mapping `PeerCalibrateActivity.reasonFor` makes, and for the same reason it is made at
 * the reading end: a sentence arriving over the wire from the handset that just refused to work
 * is a sentence nobody checked on its way to a screen.
 */
@StringRes
private fun excuseWord(excuse: RoomExcuse): Int = when (excuse) {
    RoomExcuse.NO_MICROPHONE -> R.string.excuse_no_microphone
    RoomExcuse.SLOW_LINK -> R.string.excuse_slow_link
    RoomExcuse.CLOCK_NOT_CONVERGED -> R.string.excuse_clock_not_converged
    RoomExcuse.BUSY -> R.string.excuse_busy
    RoomExcuse.ASLEEP -> R.string.excuse_asleep
}
