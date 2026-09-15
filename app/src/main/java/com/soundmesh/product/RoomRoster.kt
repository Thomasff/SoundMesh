package com.soundmesh.product

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.core.RoomExcuse
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.Carried

/**
 * Every phone in the room, one line each, with what is wrong with it on its own line.
 *
 * This is where the three counts on the checklist stopped being enough. "两台没校准" is true and
 * useless: what fixes it is walking over to one particular handset, and a count cannot say which.
 * Same for the microphone permission a round was refused for, and for a handset that has gone
 * quiet - each of them is a fact about one phone.
 *
 * This handset is the first row, always, and is called 本机 rather than named: whoever is holding
 * it has to find it in the list before anything else.
 */
@Composable
fun RoomRoster(state: HomeState, actions: HomeActions) {
    Label(
        R.string.roster_title,
        trailing = stringResource(R.string.roster_count, state.standing.size + 1)
    )
    Line(first = true) {
        Dot(state.selfPlace)
        LineName(state.calledHere.ifEmpty { stringResource(R.string.roster_self) })
        Tag(stringResource(if (state.role == Role.HOST) R.string.role_host else R.string.role_sink))
    }
    for (row in state.standing) StandingLine(row, actions)
    if (state.role == Role.HOST && state.standing.isEmpty()) {
        Column(modifier = Modifier.padding(top = 6.dp)) {
            Note(stringResource(R.string.roster_nobody))
        }
    }
}

/**
 * One standing handset: what it is called, how it is lined up, and the way to fix that.
 *
 * The entry appears only where there is something to fix. A handset already carrying its own
 * measurement says so and offers nothing - which is the whole difference between a status line and
 * a menu, and the reason this screen can be read at a glance rather than worked through.
 */
@Composable
private fun StandingLine(row: StandingRow, actions: HomeActions) {
    Line {
        Dot(null, hollow = row.quiet)
        LineName(row.name, quiet = row.quiet)
        Tag(stringResource(carryingWord(row.carrying)), carryingTone(row.carrying))
        if (row.carrying != Carried.SOMETHING) {
            Chip(stringResource(R.string.roster_calibrate)) { actions.calibratePeer(row.peerId) }
        }
    }
    // Underneath rather than beside: these are sentences, and a sentence sharing a row with a name
    // and a button is a sentence that wraps into three words a line.
    val notes = buildList {
        if (row.quiet) add(stringResource(R.string.roster_quiet))
        row.excuse?.let { add(stringResource(excuseWord(it))) }
    }
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (note in notes) Note(note, Tone.WATCH)
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
 * How loudly to say it.
 *
 * A handset carrying nothing is tens of milliseconds out and audible across a room; one on a room
 * round is about a millisecond out and can wait for a quiet minute. Two different sizes of problem
 * get two different colours - see [Tone].
 */
private fun carryingTone(carrying: Carried): Tone = when (carrying) {
    Carried.SOMETHING -> Tone.GOOD
    Carried.APPROXIMATE -> Tone.QUIET
    Carried.NOTHING -> Tone.WATCH
    Carried.UNSAID -> Tone.QUIET
}

/**
 * The room's words for a refusal, on the host's screen this time.
 *
 * The same mapping `PeerCalibrateActivity.reasonFor` makes, and for the same reason it is made at
 * the reading end: a sentence arriving over the wire from the handset that just refused to work is
 * a sentence nobody checked on its way to a screen.
 */
@StringRes
private fun excuseWord(excuse: RoomExcuse): Int = when (excuse) {
    RoomExcuse.NO_MICROPHONE -> R.string.excuse_no_microphone
    RoomExcuse.SLOW_LINK -> R.string.excuse_slow_link
    RoomExcuse.CLOCK_NOT_CONVERGED -> R.string.excuse_clock_not_converged
    RoomExcuse.BUSY -> R.string.excuse_busy
    RoomExcuse.ASLEEP -> R.string.excuse_asleep
}
