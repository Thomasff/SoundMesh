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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import kotlinx.coroutines.delay
import com.soundmesh.core.CalibrationRole
import com.soundmesh.probe.R

/**
 * What the stop button means at this moment.
 *
 * The line that matters is not which job is running, it is whether anything has started making a
 * sound yet. It is not the line between a button that works and one that does not - both sides
 * stop the round - it is the line between calling something off and throwing something away.
 *
 * Drawn here rather than left for somebody to discover by pressing.
 */
enum class StopOffer {
    /** A sink, or nothing running: a button here would sit and do nothing. */
    NONE,

    /** Still gathering. Nobody has started, so there is nothing yet to lose. */
    BEFORE_SOUND,

    /** Already chirping. Pressing it silences the room and discards the round. */
    UNDER_WAY
}

/**
 * What the peer calibration screen draws.
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
     * What every handset standing with this one is at, this one first.
     *
     * Only the host fills it - it is the handset holding the standing line everybody reports on -
     * and it is what the gate above the steps is judged on. See [tooQuietFor].
     */
    val volumes: List<VolumeRow> = emptyList(),
    /** A host no sink is standing with: nothing starts a round. Read with [volumes]; never true on a sink. */
    val alone: Boolean = false,
    /** Which colour each handset is drawn in, off the saved drawing. Empty before a room is one. */
    val colours: Map<String, Int> = emptyMap(),
    /**
     * Which of the two steps of the position calibration is the one to do now.
     *
     * The order is not a preference: while the handset is held over somebody's head it is not in
     * the place it plays from, so that round cannot measure how far apart the phones are; and once
     * it is back in its place, nobody is holding it to measure where the listener sits. So the
     * screen walks through them rather than offering both at once, which is what the four separate
     * measurement buttons used to do.
     */
    val step: Int = 1,
    /**
     * Which step the result now on screen came from, or null while no round has finished here.
     *
     * A step that has been done can be worth doing again - somebody was still walking to their
     * chair, a door was open, a phone was in the wrong hand - and the moment anybody knows that is
     * the moment they read the result. So the second chance is offered beside the result rather
     * than in the steps: the walk-through below only goes forwards, and by the time the first
     * step's answer is on screen it is already asking for the second.
     */
    val redo: Int? = null,
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
    val room: RoomState? = null,
    /**
     * Which page of the position calibration is on screen: 1 the room's volume, 2 where the
     * listener sits, 3 where the phones stand. One thing to look at per page, with the way on at
     * the bottom - reported on 2026-09-25 that a single screen holding all three left somebody
     * not knowing what to read first. Only the host walks through pages.
     */
    val page: Int = 1,
    /** Whether the listener's step was measured or skipped on this visit, which opens page 3. */
    val stepOneDone: Boolean = false,
    /** Whether the phones were measured on this visit, which is what 完成 waits for. */
    val stepTwoDone: Boolean = false,
    /** Which step the last round on this visit measured, or null before one ran. See [resultBelongsOn]. */
    val ran: Int? = null,
    /** A sound check is running: one chirp each, nothing kept, and no stop. */
    val checking: Boolean = false,
    /** What the last sound check said against each device it did not hear, by id. */
    val unheard: Map<String, String> = emptyMap(),
    /** Why the last sound check never got as far as a chirp, or null. */
    val checkFailed: String? = null,
    /**
     * Whether the last sound check heard every device it was judging, and no volume has moved
     * since. A volume moved after a pass is a level nobody checked.
     */
    val checkPassed: Boolean = false,
    /** Whether a pair round has been served on this visit, which lights 完成 on the pair screen. */
    val pairDone: Boolean = false
)

/** Whether the forward button at the bottom of [page] can be pressed. */
internal fun roomForwardOpen(
    page: Int,
    running: Boolean,
    tooQuiet: List<String>,
    stepOneDone: Boolean,
    stepTwoDone: Boolean
): Boolean = !running && when (page) {
    1 -> tooQuiet.isEmpty()
    2 -> stepOneDone
    else -> stepTwoDone
}

/** Whether 上一步 can be pressed: never on the first page, and never away from a round that runs. */
internal fun roomBackOpen(page: Int, running: Boolean): Boolean = !running && page > 1

/**
 * Whether a round's answer is drawn on [page]: on the page of the step it measured, so the
 * listener's result is not read as the phones' one page later. With no round yet, whatever is
 * said is said where the person is.
 */
internal fun resultBelongsOn(page: Int, ran: Int?): Boolean = ran == null || ran == page - 1

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
 * A pair round measures one handset, but the screen it lands on is not cleared between rounds, so
 * somebody who measured two in a row still has both answers. [name] is what that handset calls
 * itself; [sinkId] is the whole one, and the two are kept apart because rows must be told apart by
 * the name that cannot collide.
 */
data class SinkOutcome(val sinkId: String, val name: String, val text: String)

class PeerCalibrateActions(
    val calibrate: () -> Unit,
    /** Every device chirps once and whoever this host did not hear is named. Files nothing. */
    val soundCheck: () -> Unit,
    val verify: () -> Unit,
    val forget: () -> Unit,
    val stop: () -> Unit,
    /** Every pair in the room out of one window, instead of one pair at a time. */
    val measureRoom: () -> Unit,
    /** The same round with this handset held above somebody's head, which measures them. */
    val measureOverhead: () -> Unit,
    /** Past the step that measures where the listener sits, without measuring it. */
    val skipStep: () -> Unit,
    /** One number for the whole room, this handset included. */
    val setRoomVolume: (Int) -> Unit,
    /** One handset on its own, as its row on the playing screen does. */
    val setHandsetVolume: (peerId: String, percent: Int) -> Unit,
    /** To another page of the position calibration. See [PeerCalibrateState.page]. */
    val toPage: (Int) -> Unit,
    /** A finger moving one handset on the drawing, which is a person saying where it is. */
    val moveIcon: (RoomIcon) -> Unit,
    /** The drawing moved onto the lengths just measured, offered rather than done. */
    val fitRoom: () -> Unit,
    val back: () -> Unit
)

/**
 * The countdown, ticking on its own rather than on whatever else redraws this screen.
 *
 * Keyed on the instant it counts to, so a new step replaces the count instead of continuing the
 * last one's. It stops at zero rather than going negative: what comes next is a message, and a
 * screen counting downwards past zero is one that has lost track of its own run.
 *
 * At zero it says so instead of going blank. The instant this counts to is the end of the
 * recording, not the end of the step - what follows is several seconds of correlating, and while
 * that ran every word on this screen still said "hold still, keep quiet" with nothing moving
 * anywhere. Reported on a four-handset room, 2026-09-18: it reads as a hang, and somebody who
 * decides a run has hung picks the phone up, which is the one thing the step cannot survive.
 */
@Composable
internal fun HoldStill(until: Long, text: Int, style: TextStyle) {
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
    // The count is its own sign of life while it moves. What follows it is not: the recording has
    // stopped, the room may talk again, and the arithmetic takes several seconds with nothing on
    // screen changing at all - so that half sweeps and the count does not. See [Modifier.sweeping].
    //
    // In the quiet colour rather than the box's own. The band lights text up to onSurface, and
    // onSurface is what text inside a box is drawn in, so a sweep from that colour to itself drew
    // nothing at all - reported 2026-09-25 as a computing line with no light on it.
    if (left > 0) Text(stringResource(text, left), style = style)
    else {
        val quiet = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            stringResource(R.string.calibrate_computing),
            modifier = Modifier.sweeping(quiet),
            style = style,
            color = quiet
        )
    }
}

private const val TICK_MILLIS = 500L

@Composable
fun PeerCalibrateScreen(state: PeerCalibrateState, job: PeerJob, actions: PeerCalibrateActions) {
    // One tap of friction, because there is no undo and the constant on the other side of it is
    // the average of every run so far. Local to the screen: nothing outside it needs to know that
    // somebody is halfway through deciding.
    var confirmingForget by remember { mutableStateOf(false) }
    val paged = job == PeerJob.ROOM && state.role == CalibrationRole.HOST
    // The system's back walks the pages as 上一步 does, and leaves only from the first.
    BackHandler(enabled = paged && roomBackOpen(state.page, state.running)) {
        actions.toPage(state.page - 1)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Android 15 draws every app edge to edge, so without this the title sits under the
            // status bar clock. Visible on the Magic6 and not on the X10, which is Android 10.
            .safeDrawingPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            // Nothing between blocks: a label carries its own space above it. See Look.kt.
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            PageBar(
                if (job == PeerJob.PAIR) R.string.pair_calibrate_title else R.string.goto_room,
                actions.back
            )
            // The two jobs are two different screens that happen to be run by the same activity. The
            // room one is the product's own calibration, walked through in order; the pair one is a
            // single measurement between two named handsets, reached from that handset's own row.
            if (job == PeerJob.PAIR) PairBody(state, actions) { confirmingForget = true }
            else RoomBody(state, actions)
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
            // Not on the volume page, which is the upper half of the old single screen and no more.
            if (!paged || state.page != 1) state.room?.let { room ->
                val map = RoomMapActions(actions.moveIcon, actions.fitRoom, null)
                // What to do with it and the button that does it, above the drawing rather than
                // under its lengths: here the fit is the point of looking, not an afterthought.
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Note(stringResource(R.string.room_drawing_drag))
                    FitOffer(room, map)
                }
                // No offer to go and measure the listener: this screen is where that round is run,
                // and the button for it is a few lines above.
                MeasuredRoom(room, map, offerFit = false)
            }
            // The pair job keeps its result here, at the end of a screen whose whole body is two
            // buttons - the words land a finger's width under the button that was pressed. The room
            // job does not: see the box RoomBody draws in the running box's own place.
            if (job == PeerJob.PAIR) RoundResult(state)
        }
        if (paged) RoomPageButtons(state, actions)
        // The pair stays one page; its way out is the same button at the same place.
        if (job == PeerJob.PAIR && state.role == CalibrationRole.HOST) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Solid(
                    stringResource(R.string.room_calibrate_done),
                    enabled = state.pairDone && !state.running,
                    onClick = actions.back
                )
            }
        }
    }
}

/**
 * 上一步 and 下一步 - 完成 on the last page - held under the scrolling part so they are always
 * where the eye ends. Grey rather than hidden when they cannot be pressed: see [roomForwardOpen].
 */
@Composable
private fun RoomPageButtons(state: PeerCalibrateState, actions: PeerCalibrateActions) {
    val forward = roomForwardOpen(
        state.page,
        state.running,
        tooQuietFor(state.volumes),
        state.stepOneDone,
        state.stepTwoDone
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.page > 1) {
            Ghost(
                stringResource(R.string.room_calibrate_prev),
                enabled = roomBackOpen(state.page, state.running),
                modifier = Modifier.weight(1f)
            ) { actions.toPage(state.page - 1) }
        }
        if (state.page < 3) {
            Solid(
                stringResource(R.string.room_calibrate_next),
                enabled = forward,
                modifier = Modifier.weight(1f)
            ) { actions.toPage(state.page + 1) }
        } else {
            Solid(
                stringResource(R.string.room_calibrate_done),
                enabled = forward,
                modifier = Modifier.weight(1f),
                onClick = actions.back
            )
        }
    }
}

/**
 * What the round came to, in the ordinary flow of the screen.
 *
 * Not drawn while a round runs: it is said with the countdown then, and the same sentence in two
 * places reads as two things having happened.
 *
 * Which leaves this block showing whatever `message` held when the round stopped. That is the
 * answer only because [PeerCalibrateActivity.record] clears the running line as it files one -
 * before it did, this read "校准中：两台都别碰" above the numbers, every single time.
 */
@Composable
private fun RoundResult(state: PeerCalibrateState) {
    val said = state.message.takeIf { !state.running }
    if (said == null && state.outcomes.isEmpty()) return
    Label(R.string.pair_calibrate_result)
    said?.let { Note(it) }
    // Only the host ever fills this: the sink measures one host and says so above.
    state.outcomes.forEach {
        Note(stringResource(R.string.pair_calibrate_sink_line, it.name, it.text))
    }
}

/**
 * The same result, in the place the person is already looking.
 *
 * The room job's screen is long - a volume gate, two steps of three lines each, and a drawing of
 * the room - and the result used to be under all of it. So a round finished, the box that had
 * said 测量进行中 vanished, and the screen went back to looking exactly as it had before anybody
 * pressed anything, with the answer several screens below the fold. Reported 2026-09-19.
 *
 * Drawn in that box's own slot, framed the same way, because it is the answer to the thing that
 * box was doing: the strong edge moves from the round to what the round found, and there is never
 * a moment when both are on the screen.
 */
@Composable
private fun RoomRoundResult(
    state: PeerCalibrateState,
    tooQuiet: List<String>,
    actions: PeerCalibrateActions
) {
    val said = state.message
    if (said == null && state.outcomes.isEmpty()) return
    Label(R.string.pair_calibrate_result)
    Framed(strong = true) {
        said?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        state.outcomes.forEach {
            Note(stringResource(R.string.pair_calibrate_sink_line, it.name, it.text))
        }
        // The second chance, and the only place it is offered. Outlined rather than filled
        // because the screen is not asking for it: what it costs to press by accident is a minute
        // and a number that gets measured again. Named by its number rather than "this step",
        // because the step it runs is the one this result came from and the step highlighted
        // below is usually the other one - see [PeerCalibrateState.redo].
        state.redo?.let { which ->
            Column(
                modifier = Modifier.padding(top = 7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Ghost(
                    stringResource(R.string.room_calibrate_again, which),
                    enabled = roundCanStart(false, tooQuiet, state.alone),
                    onClick = if (which == 1) actions.measureOverhead else actions.measureRoom
                )
                TooQuietNote(tooQuiet)
                // The one direction the two steps depend on each other in, said where the press
                // happens. Where the phones stand is what the second step measures; where the
                // person sat is what the first measures, from those phones - so phones that moved
                // make the first step's answer describe a room that is gone, while a first step
                // redone on phones that did not move leaves the second exactly as true as it was.
                if (which == 2) Note(stringResource(R.string.room_calibrate_step2_again_note))
            }
        }
    }
}

/**
 * The product's own calibration: two rounds, in the one order they work in.
 *
 * What it replaced was four buttons named after what the code does - "量自己慢多少", "和某一台对时",
 * "几台一起量距离", "量我坐在哪" - three of which nobody who had not read the source could tell
 * apart, and none of which said that two of them had to be done in a particular order.
 */
@Composable
private fun RoomBody(state: PeerCalibrateState, actions: PeerCalibrateActions) {
    val tooQuiet = tooQuietFor(state.volumes)
    // The host walks it a page at a time: the volume, then each step on its own. A sink has one
    // page with no button on it, as before.
    val page = if (state.role == CalibrationRole.HOST) state.page else 0
    if (page <= 1) {
        Column(modifier = Modifier.padding(top = 6.dp)) {
            Note(stringResource(R.string.room_calibrate_intro))
        }
    }
    if (page == 1) {
        VolumeGate(state, tooQuiet, actions)
        return
    }
    if (page == 0 || state.running || resultBelongsOn(page, state.ran)) RoundBox(state, tooQuiet, actions)
    when (state.role) {
        // Only the host is held over anybody's head: it is the handset that gathers the room and
        // the only one that files what a round measured, so a sink doing this would be holding a
        // phone in the air for a number nothing writes down.
        CalibrationRole.HOST -> if (page == 2) {
            Step(
                number = 1,
                title = R.string.room_calibrate_step1,
                lines = listOf(
                    R.string.room_calibrate_step1_1,
                    R.string.room_calibrate_step1_2,
                    R.string.room_calibrate_step1_3
                ),
                now = state.step == 1
            ) {
                Solid(
                    stringResource(R.string.room_calibrate_step1_go),
                    enabled = roundCanStart(state.running, tooQuiet, state.alone),
                    onClick = actions.measureOverhead
                )
                TooQuietNote(tooQuiet)
                Ghost(stringResource(R.string.room_calibrate_skip), onClick = actions.skipStep)
                Note(stringResource(R.string.room_calibrate_skip_note))
            }
        } else {
            Step(
                number = 2,
                title = R.string.room_calibrate_step2,
                lines = listOf(
                    R.string.room_calibrate_step2_1,
                    R.string.room_calibrate_step2_2,
                    R.string.room_calibrate_step2_3
                ),
                now = state.step == 2
            ) {
                Solid(
                    stringResource(R.string.room_calibrate_step2_go),
                    enabled = roundCanStart(state.running, tooQuiet, state.alone),
                    onClick = actions.measureRoom
                )
                TooQuietNote(tooQuiet)
            }
        }
        // A sink has no button here at all. The round is one command from the host and every
        // handset standing on its line joins it; a start button on this side told nobody, which
        // is a button that looks like the way to begin and is not.
        CalibrationRole.SINK -> Column(modifier = Modifier.padding(top = 12.dp)) {
            Framed { Note(stringResource(R.string.room_calibrate_sink)) }
        }
        null -> Column(modifier = Modifier.padding(top = 12.dp)) {
            Framed { Note(stringResource(R.string.pair_calibrate_no_role), Tone.WATCH) }
        }
    }
    StopControl(state, PeerJob.ROOM, actions)
}

/** The round that is running, or what the last one came to, in the one box both are drawn in. */
@Composable
private fun RoundBox(state: PeerCalibrateState, tooQuiet: List<String>, actions: PeerCalibrateActions) {
    if (state.running) {
        Label(R.string.room_calibrate_now)
        Framed(strong = true) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.room_calibrate_quiet),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                state.until?.let {
                    HoldStill(it, R.string.room_calibrate_left, MaterialTheme.typography.bodyMedium)
                }
                // Swept only while there is no count beside it, which is the same question as
                // whether anything is audible: the count is handed over by the thing that fires
                // the chirps. Before it arrives this line is a room waiting for handsets to dial
                // in, and it is the only thing on the screen. See [Modifier.sweeping].
                state.message?.let { Note(it, waiting = state.until == null) }
            }
        }
    } else {
        RoomRoundResult(state, tooQuiet, actions)
    }
}

/**
 * One number for the whole room, and every handset's own answer to it underneath.
 *
 * The rows are not decoration. Every handset in a round has to be heard by every other one, so the
 * one thing that stops a round being worth running is a phone nobody can hear - and the only way
 * to see that a stream refused to move is to draw what it read back beside what it was asked for.
 *
 * No level is chosen for anybody. How loud a room has to be is a thing only somebody standing in
 * it knows; what this refuses is the settings that cannot work at any room size.
 */
@Composable
private fun VolumeGate(
    state: PeerCalibrateState,
    tooQuiet: List<String>,
    actions: PeerCalibrateActions
) {
    if (state.volumes.isEmpty()) return
    Note(stringResource(R.string.room_volume_gate_hint))
    // The advice rides on the label rather than in the note above it, because the note is about
    // the whole gate and this is about the one slider under this word. A reading and not a chip:
    // there is nothing to tap, the number is a suggestion, and how loud a room has to be is still
    // only known by somebody standing in it.
    Label(R.string.room_volume_unify, trailing = stringResource(R.string.room_volume_unify_advice))
    VolumeLine(
        name = stringResource(R.string.room_volume_all),
        percent = state.volumes.first().percent,
        colour = MaterialTheme.colorScheme.onSurface,
        strong = true,
        onSet = actions.setRoomVolume
    )
    Label(R.string.room_volume_now)
    // Each one its own slider, the playing screen's row: the phone that is too quiet is set from
    // here rather than walked to. Drawn in the error colour while it is under the floor, which is
    // what the 太小 tag on the old read-only row said.
    for (row in state.volumes) {
        val quiet = row.percent < QUIET_FLOOR_PERCENT
        VolumeLine(
            name = row.name,
            percent = row.percent,
            colour = if (quiet) MaterialTheme.colorScheme.error
            else BadgePalette.colourOf(state.colours[row.peerId], MaterialTheme.colorScheme.onSurfaceVariant),
            complaint = state.unheard[row.peerId]
        ) { actions.setHandsetVolume(row.peerId, it) }
    }
    SoundCheckButton(state, actions)
    if (tooQuiet.isNotEmpty()) {
        Note(
            stringResource(
                R.string.room_volume_too_quiet,
                tooQuiet.joinToString(stringResource(R.string.room_volume_name_join)),
                QUIET_FLOOR_PERCENT
            ),
            Tone.WRONG
        )
    }
}

/**
 * 试音, straight under the rows it answers on. Optional: nothing waits for it, and 下一步 asks
 * only for the volume. Outlined for that reason, and grey only while something runs or nobody has
 * joined to be heard. The line under it sweeps while the check runs, which is all the check says
 * about itself; what it found is on the rows.
 */
@Composable
private fun SoundCheckButton(state: PeerCalibrateState, actions: PeerCalibrateActions) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Ghost(
            stringResource(R.string.sound_check),
            enabled = !state.running && !state.alone,
            onClick = actions.soundCheck
        )
        // A pass is said on the same line, after its colon: there is nothing on the rows to say it.
        val hint = stringResource(R.string.sound_check_hint)
        Note(
            if (state.checkPassed) hint + stringResource(R.string.sound_check_passed) else hint,
            waiting = state.checking
        )
        state.checkFailed?.let { Note(it, Tone.WRONG) }
    }
}

/** Why the button above it is grey, said under the button rather than only next to the rows. */
@Composable
private fun TooQuietNote(tooQuiet: List<String>) {
    if (tooQuiet.isEmpty()) return
    Note(stringResource(R.string.room_volume_gate_shut, tooQuiet.size, QUIET_FLOOR_PERCENT), Tone.WRONG)
}

/**
 * One step of the walk-through: its number, what it is for, what to do, and its controls.
 *
 * [now] is what makes it a walk-through rather than a list. The step that is not the one to do is
 * drawn quiet, so there is never a moment where two starts are on screen and the order between
 * them is something to work out.
 *
 * A step that is not the one to do carries no controls at all, not even an outlined second chance:
 * running a step again is offered beside that step's own result, which is what somebody reads to
 * decide the room was not ready. See [RoomRoundResult].
 */
@Composable
private fun Step(
    number: Int,
    title: Int,
    lines: List<Int>,
    now: Boolean,
    controls: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Framed(strong = now) {
            Tag(
                stringResource(R.string.room_calibrate_step, number),
                if (now) Tone.GOOD else Tone.QUIET
            )
            BoxTitle(stringResource(title), strong = now)
            for ((index, line) in lines.withIndex()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Tag(stringResource(R.string.room_calibrate_line, index + 1))
                    Note(stringResource(line))
                }
            }
            if (now) {
                Column(
                    modifier = Modifier.padding(top = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    controls()
                }
            }
        }
    }
}

/**
 * The stop button, and the one sentence that is true of it at this moment.
 *
 * See [StopOffer] for why the sentence changes. It used to go grey once the chirps began, because
 * that was honest: nothing this host said reached the other handsets any more. What it costs is
 * a person standing in a room that has started making the wrong measurement with no way to end
 * it, so the channel the handsets never left is used to say so instead, and the button stays live.
 */
@Composable
private fun StopControl(state: PeerCalibrateState, job: PeerJob, actions: PeerCalibrateActions) {
    if (state.stopOffer == StopOffer.NONE) return
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Ghost(
            stringResource(
                if (job == PeerJob.ROOM) R.string.pair_calibrate_room_call_off
                else R.string.pair_calibrate_stop
            ),
            onClick = actions.stop
        )
        // The one sentence the pair screen carries, and only once the chirps are going: pressing
        // it then throws away a measurement that is most of the way done, which is worth saying
        // where pressing it during the wait costs nothing. See [PairBody] for why there is
        // nothing else in small type on this screen.
        val hint = when {
            job == PeerJob.ROOM && state.stopOffer == StopOffer.UNDER_WAY ->
                R.string.pair_calibrate_room_under_way_hint
            job == PeerJob.ROOM -> R.string.pair_calibrate_room_call_off_hint
            state.stopOffer == StopOffer.UNDER_WAY -> R.string.pair_calibrate_under_way
            else -> null
        }
        hint?.let { Note(stringResource(it)) }
    }
}

/**
 * One measurement between this handset and one named other, reached from that handset's own row.
 *
 * One instruction and one button. It used to open with four paragraphs explaining what a fixed
 * emission offset is, which side stores the constant, and what the other person has to press -
 * none of which the person standing here can act on, and all of which sat between them and the one
 * thing they were sent to do. What is left is the sentence that describes the next minute, the
 * button that starts it, and what it came to.
 *
 * Nothing starts on its own. The chip on the roster line opens this screen and stops there: a
 * minute of chirps in a room nobody has been asked to quieten is a minute wasted, and the asking
 * is what this screen is.
 */
@Composable
private fun PairBody(
    state: PeerCalibrateState,
    actions: PeerCalibrateActions,
    onForget: () -> Unit
) {
    // Above everything else while a round runs, because it is the only thing on this screen
    // that is about the next few seconds. Not for a sound check, which says itself under its
    // own button.
    if (state.running && !state.checking) {
        Label(R.string.room_calibrate_now)
        // The first phase of a pair round is the one this was asked for: dialling the other
        // handset and then agreeing a clock with it, sixteen seconds during which nothing is
        // heard and nothing on the screen moves. See [Modifier.sweeping].
        state.message?.let { Note(it, waiting = state.until == null) }
        // The same two lines the room walk-through shows, because it is the same minute of the
        // same request. Two wordings for one instruction is two chances to write one of them
        // badly and no way to notice which screen somebody read.
        state.until?.let {
            Note(stringResource(R.string.room_calibrate_quiet))
            HoldStill(it, R.string.room_calibrate_left, MaterialTheme.typography.titleMedium)
        }
    }
    if (state.role == null) {
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Framed { Note(stringResource(R.string.pair_calibrate_no_role), Tone.WATCH) }
        }
        return
    }
    // The same gate the room round carries, and the same one for the same reason: a pair is two
    // handsets each listening for the other, so a phone nobody can hear is exactly as fatal here
    // as it is in a room of four. It was only ever on the other screen because that is the screen
    // it was written for.
    val tooQuiet = tooQuietFor(state.volumes)
    if (state.role == CalibrationRole.HOST) VolumeGate(state, tooQuiet, actions)
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Framed {
            // Where the two phones go, in bold, and the whole sentence sweeping all the time: it is
            // the one thing on this screen somebody has to do before pressing, and it was being
            // read past. Asked for 2026-09-25.
            LeadNote(
                stringResource(R.string.pair_calibrate_quiet_lead),
                stringResource(R.string.pair_calibrate_quiet),
                waiting = true
            )
            Column(
                modifier = Modifier.padding(top = 7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Solid(
                    stringResource(R.string.pair_calibrate_start),
                    enabled = roundCanStart(state.running, tooQuiet, state.alone),
                    onClick = actions.calibrate
                )
                TooQuietNote(tooQuiet)
                // Only once there is something to check. A verification with nothing stored applies
                // nothing and measures the whole difference again, which reads like a failed check
                // rather than like a pair nobody has measured.
                if (state.role == CalibrationRole.SINK && state.stored != null) {
                    Ghost(stringResource(R.string.pair_calibrate_verify), onClick = actions.verify)
                    // The only way back out of a pair that has jammed: a run folds into an existing
                    // constant only when it passed, and the first run is exempt, so a bad first run
                    // is stored whole and every later run then fails against it.
                    Ghost(stringResource(R.string.pair_calibrate_forget), onClick = onForget)
                }
            }
        }
    }
    StopControl(state, PeerJob.PAIR, actions)
}
