package com.soundmesh.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import kotlinx.coroutines.delay
import com.soundmesh.core.CalibrationRole
import com.soundmesh.probe.R

/**
 * What the pair calibration screen draws.
 *
 * [role] is the role this phone is already being on the home screen, handed in rather than asked
 * for again here. It cannot be worked out from the pairing file: two handsets that have each
 * scanned the other both hold one, which would make both of them the sink. Null means nobody has
 * said yet, and the screen says so rather than guessing - a guess here files the correction
 * against the wrong peer, where it is applied silently on every later session with nothing in any
 * result to notice it by.
 *
 * [stored] is the constant this handset already carries for that peer, and it is the difference
 * between offering to measure and offering to check.
 *
 * [approximate] is what a room round left behind when nobody had measured this pair. It is shown
 * in place of [stored] rather than beside it, and named differently on the screen, because the
 * one question this screen exists to answer is whether this phone is playing off a measurement
 * or off a guess.
 */
/**
 * What the stop button can honestly mean at this moment.
 *
 * It used to be one button with one sentence - "the handset being measured will finish, and no
 * more will be waited for" - which is a queue's sentence. The pair host is a queue and that is
 * true there. A room is one round, so there is no next handset to stop waiting for, and the
 * sentence pointed at something that was not happening. Worse, whether pressing it did anything
 * at all depended on an invisible line: while the room is gathering it really does call the
 * round off, and once the chirps start nothing this host does reaches the other handsets, because
 * obeying meant leaving the standing channel.
 *
 * So the line is drawn here instead of left for somebody to discover by pressing.
 */
enum class StopOffer {
    /** A sink, or nothing running: a button here would sit and do nothing. */
    NONE,

    /** A pair host serving handset after handset. Stopping means not waiting for the next. */
    QUEUE,

    /** A room still gathering. Calling it off reaches everybody, because nobody has started. */
    ROOM_GATHERING,

    /** A room already chirping. Nothing reaches the other handsets; the button says so. */
    ROOM_UNDER_WAY
}

data class PeerCalibrateState(
    val role: CalibrationRole? = null,
    val running: Boolean = false,
    val stopOffer: StopOffer = StopOffer.NONE,
    val message: String? = null,
    val stored: Long? = null,
    val approximate: Long? = null,
    val observations: Int = 0,
    val outcomes: List<SinkOutcome> = emptyList(),
    /**
     * When the step now running should be over, on [android.os.SystemClock.elapsedRealtime].
     *
     * Null wherever the length is not actually known, and that is the whole discipline of it: a
     * round is the one thing this app asks a person to sit still for, and a number that was made
     * up is worse than "about a minute" - somebody trusts it, moves when it reaches zero, and the
     * run fails for a reason nothing writes down.
     */
    val until: Long? = null,
    /**
     * The room a round just measured, or null until one has.
     *
     * The same room the home screen plays, off the same file - see [StoredRoomDrawing]. It is
     * here because this is the screen a person is standing at when the lengths land, and until
     * now the only sign that anything had been measured was a sentence counting pairs. A drawing
     * is the one form of that answer somebody can check against the room they are standing in.
     */
    val room: RoomState? = null
)

/**
 * Whole seconds still to wait, rounded up, and never below zero.
 *
 * Up rather than down so the final part-second reads as one: a zero on screen while the round is
 * still running is the screen saying it is over when it is not, and the person it is saying that
 * to is the one who then moves.
 */
internal fun secondsLeft(now: Long, until: Long): Int {
    val left = until - now
    if (left <= 0L) return 0
    return ((left + 999L) / 1000L).toInt()
}

/**
 * What one sink's round came to, kept beside the others rather than replacing them.
 *
 * One press now serves handset after handset, so a host has as many answers as there were phones
 * in the room and the last one must not be the only one left. [name] is the short form shown;
 * [sinkId] is the whole one, and the two are kept apart because rows must be told apart by the
 * name that cannot collide.
 */
data class SinkOutcome(val sinkId: String, val name: String, val text: String)

class PeerCalibrateActions(
    val calibrate: () -> Unit,
    val verify: () -> Unit,
    val forget: () -> Unit,
    val stop: () -> Unit,
    /** Every pair in the room out of one window, instead of one pair at a time. */
    val measureRoom: () -> Unit,
    /** The same round with this handset held above somebody's head, which measures them. */
    val measureOverhead: () -> Unit,
    /** A finger moving one handset on the drawing, which is a person saying where it is. */
    val moveIcon: (RoomIcon) -> Unit,
    /** The drawing moved onto the lengths just measured, offered rather than done. */
    val fitRoom: () -> Unit
)

/**
 * The countdown, ticking on its own rather than on whatever else redraws this screen.
 *
 * Keyed on the instant it counts to, so a new step replaces the count instead of continuing the
 * last one's. It stops at zero rather than going negative: what comes next is a message, and a
 * screen counting downwards past zero is one that has lost track of its own run.
 */
@Composable
private fun HoldStill(until: Long) {
    var left by remember(until) {
        mutableIntStateOf(secondsLeft(SystemClock.elapsedRealtime(), until))
    }
    LaunchedEffect(until) {
        while (left > 0) {
            // Twice a second, so the number never sits on a stale value for most of a second.
            delay(TICK_MILLIS)
            left = secondsLeft(SystemClock.elapsedRealtime(), until)
        }
    }
    if (left > 0) {
        Text(
            stringResource(R.string.pair_calibrate_hold_still, left),
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

private const val TICK_MILLIS = 500L

@Composable
fun PeerCalibrateScreen(state: PeerCalibrateState, actions: PeerCalibrateActions) {
    // One tap of friction, because there is no undo and the constant on the other side of it is
    // the average of every run so far. Local to the screen: nothing outside it needs to know that
    // somebody is halfway through deciding.
    var confirmingForget by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Android 15 draws every app edge to edge, so without this the title sits under the
            // status bar clock. Visible on the Magic6 and not on the X10, which is Android 10.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.pair_calibrate_title),
            style = MaterialTheme.typography.headlineMedium
        )
        // Above everything else while a round runs, because it is the only thing on this screen
        // that is about the next few seconds. It used to be at the bottom with the results, which
        // on a phone means a person being asked to sit still has to scroll to find out how long
        // for.
        if (state.running) {
            Section(R.string.pair_calibrate_now) {
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                state.until?.let { HoldStill(it) }
            }
        }
        Section(R.string.pair_calibrate_what) {
            Text(
                stringResource(R.string.pair_calibrate_intro),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                when (state.role) {
                    CalibrationRole.HOST -> stringResource(R.string.pair_calibrate_role_host)
                    CalibrationRole.SINK -> stringResource(R.string.pair_calibrate_role_sink)
                    null -> stringResource(R.string.pair_calibrate_no_role)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            // Only the sink carries a constant: the correction lives on the handset that applies
            // it, filed under the one it follows.
            if (state.role == CalibrationRole.SINK) {
                Text(
                    state.stored?.let {
                        stringResource(R.string.pair_calibrate_stored, it / 1000.0, state.observations)
                    } ?: state.approximate?.let {
                        stringResource(R.string.pair_calibrate_approximate, it / 1000.0)
                    } ?: stringResource(R.string.pair_calibrate_unmeasured),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        if (state.role != null) {
            Section(R.string.pair_calibrate_run) {
                Text(
                    stringResource(R.string.pair_calibrate_quiet),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = actions.calibrate,
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pair_calibrate_start))
                }
                // What this button can mean depends on what is running, and a sink has nothing
                // to stop at all - its run is one round with nothing after it, so a button there
                // would sit and do nothing, which is worse than no button at all. See [StopOffer].
                if (state.stopOffer != StopOffer.NONE) {
                    val room = state.stopOffer != StopOffer.QUEUE
                    OutlinedButton(
                        onClick = actions.stop,
                        // Greyed rather than hidden: a button that disappears mid-round reads as
                        // a screen that lost its place. Greyed with a sentence beside it is the
                        // answer to the question somebody is about to ask by pressing it.
                        enabled = state.stopOffer != StopOffer.ROOM_UNDER_WAY,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (room) R.string.pair_calibrate_room_call_off
                                else R.string.pair_calibrate_stop
                            )
                        )
                    }
                    Text(
                        stringResource(
                            when (state.stopOffer) {
                                StopOffer.ROOM_GATHERING -> R.string.pair_calibrate_room_call_off_hint
                                StopOffer.ROOM_UNDER_WAY -> R.string.pair_calibrate_room_under_way_hint
                                else -> R.string.pair_calibrate_stop_hint
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Only once there is something to check. A verification with nothing stored
                // applies nothing and measures the whole difference again, which reads like a
                // failed check rather than like a pair nobody has measured.
                if (state.role == CalibrationRole.SINK && state.stored != null) {
                    OutlinedButton(
                        onClick = actions.verify,
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.pair_calibrate_verify))
                    }
                    Text(
                        stringResource(R.string.pair_calibrate_verify_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                    // The only way back out of a pair that has jammed: a run folds into an
                    // existing constant only when it passed, and the first run is exempt, so a
                    // bad first run is stored whole and every later run then fails against it.
                    // Offered under exactly the condition the verify button is, because both
                    // mean the same thing - there is a stored constant, and this handset is the
                    // side that carries it.
                    OutlinedButton(
                        onClick = { confirmingForget = true },
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.pair_calibrate_forget))
                    }
                    Text(
                        stringResource(R.string.pair_calibrate_forget_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (state.role != null) {
            Section(R.string.pair_calibrate_room_title) {
                Text(
                    stringResource(R.string.pair_calibrate_room_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = actions.measureRoom,
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pair_calibrate_room_start))
                }
                // Only the host, because only the host is held: it is the handset that gathers
                // the room and the only one that files anything, so a sink pressing this would
                // be holding a phone over their head for a number nothing writes down.
                if (state.role == CalibrationRole.HOST) {
                    Text(
                        stringResource(R.string.pair_calibrate_overhead_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = actions.measureOverhead,
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.pair_calibrate_overhead_start))
                    }
                }
            }
        }
        if (confirmingForget && state.stored != null) {
            AlertDialog(
                onDismissRequest = { confirmingForget = false },
                title = { Text(stringResource(R.string.pair_calibrate_forget_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.pair_calibrate_forget_body,
                            state.stored / 1000.0,
                            state.observations
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingForget = false
                        actions.forget()
                    }) {
                        Text(stringResource(R.string.pair_calibrate_forget_yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingForget = false }) {
                        Text(stringResource(R.string.pair_calibrate_forget_no))
                    }
                }
            )
        }
        // Under the buttons rather than above them, because it only exists once a round has
        // finished - and while one is running the thing worth reading is the countdown.
        state.room?.let { room ->
            Section(R.string.pair_calibrate_room_drawing) {
                Text(
                    stringResource(R.string.pair_calibrate_room_drawing_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                // No offer to go and measure the listener: this screen is where that round is
                // run, and the button for it is a few lines above.
                MeasuredRoom(room, RoomMapActions(actions.moveIcon, actions.fitRoom, null))
            }
        }
        // Not while a round runs: it is said at the top then, and the same sentence in two places
        // reads as two things having happened.
        val said = state.message.takeIf { !state.running }
        if (said != null || state.outcomes.isNotEmpty()) {
            Section(R.string.pair_calibrate_result) {
                said?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
                // Only the host ever fills this: the sink measures one host and says so above.
                state.outcomes.forEach {
                    Text(
                        stringResource(R.string.pair_calibrate_sink_line, it.name, it.text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
