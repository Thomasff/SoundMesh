package com.soundmesh.product

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.LocalAddress

/**
 * Stage two: a board, not a questionnaire.
 *
 * Reads top to bottom as network, code, handsets, calibration, way out - which is the order
 * somebody setting a room up needs them in, and the order the drawing of 2026-09-15 put them in.
 * What the room will play is not among them: choosing a source is something people do while the
 * music is on, and it lives on the playing stage where it can be heard taking effect.
 * Everything that is wrong says so on the line it is about; the only sentence repeated at the
 * bottom is the one standing between this room and playing.
 *
 * Exactly two things here are drawn solid: the calibration box and the way onto the playing stage.
 * A screen where everything is emphasised has nothing emphasised, and what people were skipping
 * past is the calibration.
 */
@Composable
fun ReadyScreen(state: HomeState, actions: HomeActions) {
    val items = readyList(state)
    if (state.running) {
        // With a room already playing, getting back to the controls is the only thing anybody came
        // here for that the rest of this screen cannot answer, so it goes first.
        Solid(stringResource(R.string.ready_back_to_play), onClick = actions.backToPlaying)
    }
    NetworkLines(state)
    when (state.role) {
        Role.HOST -> PairCodeSection(state, actions)
        // A sink has nothing to hand out: it is the one doing the scanning.
        Role.SINK -> ScanLine(state, actions)
        Role.NONE -> Unit
    }
    RoomRoster(state, actions)
    // Host only. A sink has nothing here it can act on: the room round is started by whoever is
    // holding the room together, and this handset's own output lead is asked about on the one
    // handset that streams what it is playing - which a sink by definition is not. Both used to
    // be drawn anyway, so a sink offered two errands, one of which put a button under somebody's
    // finger that measures nothing and the other of which opened a screen whose every control
    // said to go and press something on the host. The room is run from the host; this screen now
    // says so by having nothing else on it.
    if (state.role == Role.HOST) CalibrateSection(state, actions)
    Problems(items, actions)
    // Said here as well, because the file is chosen from this screen too - off the blocked line
    // above, which is the only way onto the playing stage when nothing has been picked yet.
    state.problem?.let { Note(stringResource(it), Tone.WRONG) }
    if (!state.running) {
        Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Solid(
                stringResource(R.string.ready_go),
                enabled = canStart(items) && !state.starting,
                onClick = actions.play
            )
            // Said again under the button somebody is looking at, rather than only on the line
            // they may already have scrolled past.
            if (!canStart(items)) {
                items.firstOrNull { it.mark == Mark.BLOCK }?.let { Note(readyLineText(it), Tone.WRONG) }
            }
        }
    }
    Ghost(stringResource(R.string.role_change)) { actions.pickRole(Role.NONE) }
}

/**
 * Which networks this handset is on, one line each.
 *
 * Both, because on these handsets both can be up at once, and because which of them a peer used is
 * the first question when a phone will not join. The hotspot is known by having an address that is
 * not the joined one - see [LocalAddress] - rather than by a name, which this app has no
 * permission to read.
 */
@Composable
private fun NetworkLines(state: HomeState) {
    Label(R.string.network_title)
    val hotspot = LocalAddress.ReachedBy.HOTSPOT in state.codeChoices
    Line(first = true) {
        Dot(null, hollow = !hotspot)
        LineName(
            stringResource(if (hotspot) R.string.network_hotspot_on else R.string.network_hotspot_off),
            quiet = !hotspot
        )
    }
    Line {
        Dot(null, hollow = !state.onWifi)
        LineName(
            when {
                state.wifiName != null -> stringResource(R.string.welcome_wifi_name, state.wifiName)
                state.onWifi -> stringResource(R.string.welcome_wifi_connected)
                else -> stringResource(R.string.network_wifi_off)
            },
            quiet = !state.onWifi
        )
    }
}

/** The sink's half of pairing: it scans, and says whether it has. */
@Composable
private fun ScanLine(state: HomeState, actions: HomeActions) {
    Label(R.string.pair_title)
    Line(first = true) {
        Dot(null, hollow = state.paired == null)
        LineName(
            stringResource(if (state.paired != null) R.string.ready_paired else R.string.ready_paired_none),
            quiet = state.paired == null
        )
        Chip(stringResource(R.string.pair_scan), actions.scan)
    }
}

/**
 * The one this whole project is for, and it is drawn as such.
 *
 * It used to be the third of four text buttons under a heading that read "量一量（做过一次就不用
 * 再做了）" - which reads as a nice-to-have somebody has already done. The pair calibration is not
 * here any more either: it moved onto each handset's own row, where the handset it is about is.
 */
@Composable
private fun CalibrateSection(state: HomeState, actions: HomeActions) {
    // Drawn on the host alone - see the call site.
    Label(R.string.calibrate_section)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Framed(strong = true) {
            BoxTitle(stringResource(R.string.goto_room), strong = true)
            Note(stringResource(R.string.goto_room_hint))
            Column(modifier = Modifier.padding(top = 7.dp)) {
                Solid(stringResource(R.string.goto_room_go)) {
                    actions.goto(ReadyGoto.PAIR_CALIBRATE, PeerJob.ROOM)
                }
            }
        }
        Framed {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoxTitle(stringResource(R.string.goto_self))
                // Shown even where it is not used, which is most of the time - see selfLead for
                // where it is. Somebody who measured it once and comes back for the number should
                // find it rather than an empty place where it was.
                state.selfCalibrated?.let {
                    Tag(
                        stringResource(
                            R.string.goto_self_done,
                            String.format(null as java.util.Locale?, "%.1f", it)
                        ),
                        Tone.GOOD
                    )
                }
            }
            Note(stringResource(R.string.goto_self_hint))
            Column(modifier = Modifier.padding(top = 7.dp)) {
                Ghost(
                    stringResource(
                        if (state.selfCalibrated != null) R.string.goto_self_again else R.string.goto_self_go
                    )
                ) { actions.goto(ReadyGoto.SELF_CALIBRATE, null) }
            }
        }
    }
}

/**
 * Whatever is still wrong and is not already said on a line of its own.
 *
 * Filtered rather than listed whole: the song, the pairing and the handsets each have a line above
 * that states their own case, and saying it twice on one screen is how a board turns back into a
 * questionnaire. What is left is the power-saving block and the output lead, neither of which has
 * anywhere else to be.
 */
@Composable
private fun Problems(items: List<ReadyItem>, actions: HomeActions) {
    val left = items.filter { it.mark != Mark.OK && it.line !in SAID_ELSEWHERE }
    if (left.isEmpty()) return
    Label(R.string.ready_title)
    for ((index, item) in left.withIndex()) {
        Line(first = index == 0) {
            Text(
                markGlyph(item.mark),
                style = MaterialTheme.typography.labelMedium,
                color = markColour(item.mark)
            )
            LineName(readyLineText(item))
            item.goto?.let { goto ->
                Chip(stringResource(gotoLabel(goto))) {
                    actions.goto(goto, if (goto == ReadyGoto.PAIR_CALIBRATE) PeerJob.PAIR else null)
                }
            }
        }
    }
}

/** The lines this screen already draws somewhere better, in the words of whatever states them. */
private val SAID_ELSEWHERE = setOf(
    R.string.ready_song,
    R.string.ready_paired, R.string.ready_paired_none,
    R.string.ready_standing, R.string.ready_standing_none,
    R.string.ready_uncalibrated
)

/** [ReadyItem.line] read the way it is shown, with its number folded in where the string takes one. */
@Composable
internal fun readyLineText(item: ReadyItem): String =
    if (item.line in NO_ARG_LINES) {
        val head = stringResource(item.line)
        item.detail?.let { detail ->
            if (item.line == R.string.ready_self_lead) {
                head + " " + stringResource(R.string.ready_self_lead_value, detail)
            } else {
                "$head $detail"
            }
        } ?: head
    } else {
        item.detail?.let { stringResource(item.line, it) } ?: stringResource(item.line)
    }

/** The lines whose string carries no `%1$s` of its own - see [readyLineText]. */
private val NO_ARG_LINES = setOf(R.string.ready_song, R.string.ready_self_lead, R.string.ready_paired)

private fun markGlyph(mark: Mark): String = when (mark) {
    Mark.OK -> "✓"
    Mark.WARN -> "!"
    Mark.BLOCK -> "✕"
}

@Composable
private fun markColour(mark: Mark) = when (mark) {
    Mark.OK -> MaterialTheme.colorScheme.onSurfaceVariant
    Mark.WARN -> MaterialTheme.colorScheme.secondary
    Mark.BLOCK -> MaterialTheme.colorScheme.error
}

/** Where a line's own button says it goes. */
@StringRes
private fun gotoLabel(goto: ReadyGoto): Int = when (goto) {
    ReadyGoto.SONG -> R.string.ready_goto_song
    ReadyGoto.SELF_CALIBRATE -> R.string.ready_goto_self
    ReadyGoto.PAIR_CALIBRATE -> R.string.ready_goto_pair
    ReadyGoto.ALLOW_BACKGROUND -> R.string.ready_goto_allow
    ReadyGoto.SCAN -> R.string.ready_goto_scan
    ReadyGoto.SHOW_CODE -> R.string.ready_goto_code
}
