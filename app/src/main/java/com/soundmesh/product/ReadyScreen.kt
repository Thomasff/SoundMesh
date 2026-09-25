package com.soundmesh.product

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Everything that is wrong says so on the line it is about, and says it once. Nothing here refuses
 * to let anybody through: the board reports, the playing stage decides what it can start.
 *
 * Exactly two things here are drawn solid: the calibration box and the way onto the playing stage.
 * A screen where everything is emphasised has nothing emphasised, and what people were skipping
 * past is the calibration.
 */
@Composable
fun ReadyScreen(state: HomeState, actions: HomeActions) {
    val items = readyList(state)
    // The way back to a playing room is not drawn here any more. It is pinned above this whole
    // screen - see HomeScreen - because it was the one thing on the board that has to be reachable
    // from wherever somebody has scrolled to, and a scrolling screen carries it away.
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
    // Said here as well, because a file can be chosen from this screen too - off the song line
    // above - and whatever went wrong with that pick has to land where the pick was made.
    state.problem?.let { Note(stringResource(it), Tone.WRONG) }
    if (!state.running) {
        Column(modifier = Modifier.padding(top = 16.dp)) {
            // It goes to the playing stage and starts nothing. Whether a song has been picked used
            // to decide whether this button worked at all, which put the one screen that can pick
            // a song behind the one condition that needs picking one - and a room streaming another
            // app, or a sink, never needs a file at all. What is still wrong is on its own line
            // above; none of it is a reason to refuse somebody the controls.
            Solid(stringResource(R.string.ready_go), onClick = actions.enterPlaying)
        }
    }
    Ghost(stringResource(R.string.role_change)) { actions.pickRole(Role.NONE) }
}

/**
 * Which networks this handset is on - two lines on a host, one on a sink.
 *
 * A host runs both and is asked about both: on these handsets an access point and a joined network
 * can be up at once, and which of the two a peer used is the first question when a phone will not
 * join. The hotspot is known by having an address that is not the joined one - see [LocalAddress] -
 * rather than by a name, which this app has no permission to read.
 *
 * A sink has one question, and it is not which of the two: a phone joining its host's hotspot and a
 * phone joining a router look identical from inside Android. So it gets one line that covers both,
 * and no name - the name was never the thing being asked about, and reading it costs a location
 * permission this app deliberately does not hold.
 *
 * What that line may not be read off is [HomeState.onWifi] alone, which is "joined somebody else's
 * network" and nothing else. A handset serving the hotspot can be a sink - reported 09-21, working
 * - and that one said 未连接 WiFi/热点 while it was the network every other phone in the room was
 * on. See [onANetwork].
 */
@Composable
private fun NetworkLines(state: HomeState) {
    Label(R.string.network_title)
    if (state.role == Role.SINK) {
        val on = onANetwork(state)
        Line(first = true) {
            Dot(null, hollow = !on)
            LineName(
                stringResource(if (on) R.string.network_joined else R.string.network_not_joined),
                quiet = !on
            )
        }
        return
    }
    val hotspot = servingAnAccessPoint(state)
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
            stringResource(if (state.onWifi) R.string.network_wifi_on else R.string.network_wifi_off),
            quiet = !state.onWifi
        )
    }
}

/**
 * Whether this handset is serving an access point of its own.
 *
 * Off the code choices rather than off a name: an address that is not the joined one is this
 * handset's own access point, and the interface it sits on carries no such thing - the X10 runs
 * its hotspot on wlan0. See [LocalAddress.ReachedBy].
 */
internal fun servingAnAccessPoint(state: HomeState): Boolean =
    LocalAddress.ReachedBy.HOTSPOT in state.codeChoices

/**
 * Whether this handset is on a network the room could be on - joined one, or serving one.
 *
 * The second half is the one that was missing. [HomeState.onWifi] answers "did this phone join
 * somebody else's network", which is false on the phone holding the hotspot however many other
 * phones are on it.
 */
internal fun onANetwork(state: HomeState): Boolean = state.onWifi || servingAnAccessPoint(state)

/**
 * The sink's half of pairing: whether it is talking to a host, and the way to fix it if not.
 *
 * Off [HomeState.onStandby], the live line, rather than [HomeState.paired], the host this handset
 * will dial when it can. The stored one answers a different question and answers it for days: it
 * survives the host closing the app, which is deliberate - it is how the room comes back by itself
 * - and it is set by a background search that this screen does not watch. Both halves of that were
 * on the phones on 09-21: a sink already in the host's room still saying 正在自动找主机, and a sink
 * with no host left on the network still claiming one. Nothing but re-picking the role moved either.
 *
 * The live reading was on this screen the whole time and agreed with neither: the standby line below
 * said 还没连上主机 and the edge glow was out. Parts of one screen contradicting each other is how
 * this was found, twice, and the wrong one both times was the one asserting off a stored field.
 */
@Composable
private fun ScanLine(state: HomeState, actions: HomeActions) {
    Label(R.string.pair_title)
    Line(first = true) {
        Dot(null, hollow = !state.onStandby)
        LineName(
            stringResource(if (state.onStandby) R.string.ready_paired else R.string.ready_paired_none),
            quiet = !state.onStandby
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
        // Only until it has been measured. It is measured once per handset and never again, so on
        // every launch after that this box was an errand nobody has, sitting on the one screen
        // whose job is to say what still needs doing. The way back to it is the settings screen,
        // where a thing you do once and then look up belongs - see SettingsScreen.
        if (state.selfCalibrated == null) {
            Framed {
                BoxTitle(stringResource(R.string.goto_self))
                Note(stringResource(R.string.goto_self_hint))
                Column(modifier = Modifier.padding(top = 7.dp)) {
                    Ghost(stringResource(R.string.goto_self_go)) {
                        actions.goto(ReadyGoto.SELF_CALIBRATE, null)
                    }
                }
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
    val left = boardProblems(items)
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

/** What [Problems] draws: whatever is wrong and is not said anywhere better. */
internal fun boardProblems(items: List<ReadyItem>): List<ReadyItem> =
    items.filter { it.mark != Mark.OK && it.line !in SAID_ELSEWHERE }

/** The lines this screen already draws somewhere better, in the words of whatever states them. */
private val SAID_ELSEWHERE = setOf(
    // Both halves of the song line. The source is picked on the playing page, where it is heard
    // taking effect, and nothing on this board is a way to pick one - so its missing half drew a
    // ✕ 还没选歌 above 进入播放 on every phone that had never played a song, which is exactly the
    // phone somebody is setting up for the first time. Seen on a new phone, 2026-09-25.
    R.string.ready_song, R.string.ready_song_missing,
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
