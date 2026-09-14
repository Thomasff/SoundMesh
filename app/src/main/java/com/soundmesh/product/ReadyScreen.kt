package com.soundmesh.product

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.R

/**
 * Stage two: what stands between this handset and a room that plays, one line per thing that is
 * wrong and one place to go about it - see [readyList]. Replaces the flat [HostPanel]/[SinkPanel]
 * page for this stage; those two still draw the playing stage.
 *
 * The button called "和配对的另一台手机对时" used to be the only door onto three unrelated jobs -
 * see [PeerJob] - so the "量一量" section below gives each of them its own line instead of one
 * name that only described the first of them.
 */
@Composable
fun ReadyScreen(state: HomeState, actions: HomeActions) {
    val items = readyList(state)
    Section(R.string.ready_title) {
        for (item in items) ReadyRow(item, actions)
        Button(
            onClick = actions.play,
            enabled = canStart(items),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ready_go))
        }
        if (!canStart(items)) {
            // Said again under the button a person is looking at, rather than only on the row
            // they may already have scrolled past.
            items.firstOrNull { it.mark == Mark.BLOCK }?.let {
                Text(
                    readyLineText(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    when (state.role) {
        Role.HOST -> SongSection(state, actions)
        Role.SINK -> Section(R.string.pair_title) {
            OutlinedButton(onClick = actions.scan, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_scan))
            }
            Text(stringResource(R.string.pair_scan_hint), style = MaterialTheme.typography.bodySmall)
        }
        // Unreachable: routeOf only sends this screen a role that has been picked.
        Role.NONE -> Unit
    }
    Section(R.string.ready_measure) {
        TextButton(onClick = { actions.goto(ReadyGoto.PAIR_CALIBRATE, PeerJob.PAIR) }) {
            Text(stringResource(R.string.goto_pair_one))
        }
        TextButton(onClick = { actions.goto(ReadyGoto.PAIR_CALIBRATE, PeerJob.ROOM) }) {
            Text(stringResource(R.string.goto_room))
        }
        TextButton(onClick = { actions.goto(ReadyGoto.PAIR_CALIBRATE, PeerJob.OVERHEAD) }) {
            Text(stringResource(R.string.goto_overhead))
        }
    }
    // The way back out of the role picked above. The flat page this screen replaces used to carry
    // this button; nothing called it any more once that page was cut apart, which left a person
    // who picked the wrong role with no way back short of clearing the app's data.
    TextButton(onClick = { actions.pickRole(Role.NONE) }) {
        Text(stringResource(R.string.role_change))
    }
}

/**
 * One checklist line: a mark, the sentence [ReadyItem.line] currently states, and where to go
 * about it if anywhere.
 *
 * [ReadyItem.line] swaps with the mark rather than being a stable label for the row - see
 * [ReadyItem] - so it is read fresh here on every call instead of being kept.
 */
@Composable
private fun ReadyRow(item: ReadyItem, actions: HomeActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(markGlyph(item.mark), color = markColour(item.mark))
            Spacer(Modifier.width(8.dp))
            Text(readyLineText(item), style = MaterialTheme.typography.bodyMedium)
            // ready_song / ready_self_lead / ready_paired carry no placeholder of their own, so a
            // non-null detail beside them - a song's name, a measured lead - is said as its own
            // line rather than silently dropped by String.format.
            if (item.line in NO_ARG_LINES) {
                item.detail?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item.goto?.let { goto ->
            TextButton(
                onClick = {
                    actions.goto(goto, if (goto == ReadyGoto.PAIR_CALIBRATE) PeerJob.PAIR else null)
                }
            ) {
                Text(stringResource(gotoLabel(goto)))
            }
        }
    }
}

/** [ReadyItem.line] read the way [ReadyRow] shows it, without the trailing detail line. */
@Composable
private fun readyLineText(item: ReadyItem): String =
    if (item.line in NO_ARG_LINES) stringResource(item.line)
    else item.detail?.let { stringResource(item.line, it) } ?: stringResource(item.line)

/** The lines whose string carries no `%1$s` of its own - see [readyLineText]. */
private val NO_ARG_LINES = setOf(R.string.ready_song, R.string.ready_self_lead, R.string.ready_paired)

private fun markGlyph(mark: Mark): String = when (mark) {
    Mark.OK -> "✓"
    Mark.WARN -> "⚠"
    Mark.BLOCK -> "✗"
}

@Composable
private fun markColour(mark: Mark): Color = when (mark) {
    Mark.OK -> MaterialTheme.colorScheme.onSurfaceVariant
    Mark.WARN -> MaterialTheme.colorScheme.secondary
    Mark.BLOCK -> MaterialTheme.colorScheme.error
}

/** Where a checklist line's own button says it goes. */
@StringRes
private fun gotoLabel(goto: ReadyGoto): Int = when (goto) {
    ReadyGoto.SONG -> R.string.ready_goto_song
    ReadyGoto.SELF_CALIBRATE -> R.string.ready_goto_self
    ReadyGoto.PAIR_CALIBRATE -> R.string.ready_goto_pair
    ReadyGoto.ALLOW_BACKGROUND -> R.string.ready_goto_allow
    ReadyGoto.SCAN -> R.string.ready_goto_scan
    ReadyGoto.SHOW_CODE -> R.string.ready_goto_code
}
