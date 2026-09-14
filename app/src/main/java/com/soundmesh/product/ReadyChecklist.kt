package com.soundmesh.product

import androidx.annotation.StringRes
import com.soundmesh.probe.R

/**
 * How badly a line is wrong.
 *
 * [WARN] and [BLOCK] are not degrees of the same thing, they are different questions. WARN says
 * "this will sound worse than it should"; BLOCK says "a phone in this room will be silent and you
 * will not know why". Only the second one is worth refusing to start over.
 */
enum class Mark { OK, WARN, BLOCK }

/** Where the button at the end of a line takes you. */
enum class ReadyGoto { SONG, SELF_CALIBRATE, PAIR_CALIBRATE, ALLOW_BACKGROUND, SCAN, SHOW_CODE }

/**
 * One line of the checklist: a mark, a sentence, the number behind it, and somewhere to go.
 *
 * [goto] is null only on an [Mark.OK] line. A line that is wrong and has nowhere to go is a
 * notification wearing a checklist's clothes, and the test holds this.
 *
 * [line] is not a stable id for a row - it swaps with [mark] ("选好歌了" becomes "还没选歌"),
 * because the whole point of the checklist is that each line states the current situation. Do
 * not cache it against the row it came from; read it fresh every time.
 */
data class ReadyItem(
    val mark: Mark,
    @StringRes val line: Int,
    val detail: String?,
    val goto: ReadyGoto?
)

/**
 * Everything standing between this handset and a room that plays.
 *
 * Empty with no role picked: there is nothing to be ready for until this phone knows what it is
 * being, and the welcome screen is what asks.
 */
internal fun readyList(state: HomeState): List<ReadyItem> = when (state.role) {
    Role.NONE -> emptyList()
    Role.HOST -> hostList(state)
    Role.SINK -> sinkList(state)
}

/** Whether the start button is live. Only [Mark.BLOCK] stops it - see [Mark]. */
internal fun canStart(items: List<ReadyItem>): Boolean = items.none { it.mark == Mark.BLOCK }

private fun hostList(state: HomeState): List<ReadyItem> = buildList {
    // Capturing is a source: a host streaming another app has something to play with no file
    // picked, and treating that as "no song" would put the capture feature behind a locked door.
    val hasSource = state.capturing || state.songName != null
    add(
        ReadyItem(
            mark = if (hasSource) Mark.OK else Mark.BLOCK,
            line = if (hasSource) R.string.ready_song else R.string.ready_song_missing,
            detail = state.songName,
            goto = if (hasSource) null else ReadyGoto.SONG
        )
    )
    add(selfLead(state))
    add(
        ReadyItem(
            mark = if (state.standingBy > 0) Mark.OK else Mark.BLOCK,
            line = if (state.standingBy > 0) R.string.ready_standing else R.string.ready_standing_none,
            detail = if (state.standingBy > 0) state.standingBy.toString() else null,
            goto = if (state.standingBy > 0) null else ReadyGoto.SHOW_CODE
        )
    )
    if (state.uncalibrated > 0) {
        add(
            ReadyItem(
                mark = Mark.WARN,
                line = R.string.ready_uncalibrated,
                detail = state.uncalibrated.toString(),
                goto = ReadyGoto.PAIR_CALIBRATE
            )
        )
    }
    addAll(blocked(state))
}

private fun sinkList(state: HomeState): List<ReadyItem> = buildList {
    val paired = state.paired != null
    add(
        ReadyItem(
            mark = if (paired) Mark.OK else Mark.BLOCK,
            line = if (paired) R.string.ready_paired else R.string.ready_paired_none,
            detail = null,
            goto = if (paired) null else ReadyGoto.SCAN
        )
    )
    add(selfLead(state))
    addAll(blocked(state))
}

/**
 * This handset's own output lead.
 *
 * Warn rather than block: an unmeasured handset plays, it just plays early. Blocking would mean
 * nobody can hear the thing at all before doing the measurement, and the measurement is exactly
 * the part people put off.
 */
private fun selfLead(state: HomeState): ReadyItem {
    val lead = state.selfCalibrated
    return ReadyItem(
        mark = if (lead != null) Mark.OK else Mark.WARN,
        line = if (lead != null) R.string.ready_self_lead else R.string.ready_self_lead_missing,
        detail = lead?.let { String.format(null as java.util.Locale?, "%.1f", it) },
        goto = if (lead != null) null else ReadyGoto.SELF_CALIBRATE
    )
}

/**
 * The handsets that will be killed the moment their screen is left, this one included.
 *
 * One line each rather than a count, because what fixes it is walking over to a particular phone,
 * and a count cannot say which one.
 */
private fun blocked(state: HomeState): List<ReadyItem> = buildList {
    if (!state.backgroundAllowed) {
        add(
            ReadyItem(
                mark = Mark.BLOCK,
                line = R.string.ready_blocked,
                detail = null,
                goto = ReadyGoto.ALLOW_BACKGROUND
            )
        )
    }
    for (name in state.blockedPeerNames) {
        add(
            ReadyItem(
                mark = Mark.BLOCK,
                line = R.string.ready_blocked,
                detail = name,
                goto = ReadyGoto.ALLOW_BACKGROUND
            )
        )
    }
}
