package com.soundmesh.product

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.math.abs
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundmesh.core.PairingCode
import com.soundmesh.core.SessionState
import com.soundmesh.probe.BuildConfig
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.CaptureSilence
import com.soundmesh.probe.sync.indexFor
import com.soundmesh.probe.sync.Playhead
import com.soundmesh.probe.sync.PairingCodeImage

/** Which half of the pair this handset is being right now. Not kept across launches - see below. */
enum class Role { NONE, HOST, SINK }

/**
 * Everything the screen draws, and nothing it decides.
 *
 * The role is in here rather than on disk on purpose: it answers "what is this phone being right
 * now", and swapping the two roles and trying again is the most common thing anybody does with
 * this project.
 */
data class HomeState(
    val role: Role = Role.NONE,
    /**
     * This handset's own name, and where in the palette it sits.
     *
     * On screen from the first launch rather than from the first session, because the number is
     * a property of the handset and not of the room - it is the same number before anything is
     * running, and being able to say "I am 42" into a phone call is most of the point. The
     * colour is the room's half and is null until there is a room to have handed one out.
     */
    val selfId: String? = null,
    val selfPlace: Int? = null,
    val songName: String? = null,
    /**
     * Where that song lives, which is what the service is handed when play is pressed.
     *
     * Beside the name rather than fetched again at that moment: the name on screen and the song
     * that starts have to be the same one, and re-reading is how they come apart.
     */
    val songUri: String? = null,
    /**
     * Whether that address is a folder rather than one song.
     *
     * The screen shows both the same way - a name - because from where a listener stands they are
     * the same choice. What it changes is which extra the service is handed, and that a folder's
     * songs are read at the moment play is pressed rather than at the moment it was picked.
     */
    val songIsFolder: Boolean = false,
    /**
     * Whether the host will stream what this phone is playing instead of a file it was handed.
     *
     * True only once the consent dialog has been answered and the projection exists, because a
     * screen that offered to play before then would be offering something that cannot start.
     */
    val capturing: Boolean = false,
    val checking: Boolean = false,
    val problem: Int? = null,
    val pairingPayload: String? = null,
    val paired: PairingCode? = null,
    /**
     * How many handsets are standing by for this host, and whether this sink is one of them.
     *
     * On screen because it is the answer to the question somebody asks one second after pressing
     * a button that was supposed to start three phones: a handset not holding the line is a
     * handset that did not hear, and without this the only way to find that out is that it never
     * started and nothing anywhere said why.
     */
    val standingBy: Int = 0,
    /**
     * How many of those said they carry no correction for this host.
     *
     * Beside the count rather than buried on each handset's own calibration screen, because
     * nobody watches four screens. On 2026-09-13 two handsets played a whole afternoon carrying
     * nothing - the app already said so, on a page in each of them that nobody had reason to
     * open - and what found it was a listener saying one of them sounded early.
     */
    val uncalibrated: Int = 0,
    /**
     * How many standing handsets are correcting off a room round instead of their own pair.
     *
     * Shown apart from [uncalibrated] and in a quieter colour, because it is a different size of
     * problem: about a millisecond against tens of them. It is here at all so that nobody has to
     * remember which phones were measured properly and which were caught up in a hurry.
     */
    val approximate: Int = 0,
    /**
     * What this handset is called on everybody else's screen.
     *
     * Shown read-only and shown at all for one reason: the host now names handsets out loud -
     * "the tablet is not joining, it has no microphone permission" - and a person can only act on
     * that if they know which phone answers to which name. Where it comes from is the phone's own
     * system name, so changing it is a thing they already know how to do.
     */
    val calledHere: String = "",
    val onStandby: Boolean = false,
    /**
     * Whether this handset lets this app keep running once nobody is looking at it.
     *
     * Read off the system rather than assumed, because the assumption cost an evening. Two
     * handsets in the same room on the same build behaved differently, and the difference was
     * this bit: one of them killed the standing service within seconds of the home button.
     */
    val backgroundAllowed: Boolean = true,
    /**
     * How many seconds the capture has been handing over exactly zero, or null when it is not.
     *
     * On screen because the failure it names is invisible from every other direction: the session
     * says PLAYING, the counters are healthy, the drift is fine, and the room is silent. A
     * listener who cannot see this has nothing to tell anybody except that the music stopped.
     */
    val captureSilentSeconds: Int? = null,
    val running: Boolean = false,
    val sessionState: SessionState? = null,
    val failure: String? = null,
    val counters: List<Counter> = emptyList(),
    /**
     * Where the room is in the song, or null when nothing can say.
     *
     * Null on a sink, on a capture, and on a song whose container does not give a length - a
     * slider whose right-hand end is a guess is worse than no slider at all.
     */
    val playhead: Playhead? = null,
    /**
     * Whether the room is quiet on purpose.
     *
     * Read back off the session rather than remembered from the button that was pressed: the
     * screen can be rebuilt while a session goes on running, and a toggle that forgot which way
     * it was would offer to pause a room that is already paused.
     */
    val paused: Boolean = false,
    /**
     * What the room is playing, by name, or null when nothing has said.
     *
     * On every handset rather than only the one holding the songs, which is the whole of why it is
     * sent: from across a room the sinks are the phones you can see, and until now the only thing
     * any of them could tell you was that a session was running.
     */
    val nowPlaying: String? = null,
    val health: Health = Health(null, null, null, null),
    /**
     * The accessibility output's volume, on a host that is capturing and therefore heard on it.
     *
     * Null when nothing is being captured: that output carries nothing then, and a level for a
     * silent stream is a number with nothing behind it. See [AccessibilityVolume].
     */
    val hostOutputVolume: OutputVolume? = null,
    /**
     * Where the room's volume slider sits, or null where there is no room to set one for.
     *
     * Follows this handset's own volume until somebody drags it, which is what makes the slider
     * start where the person expects: at whatever this phone is already playing at. After a drag
     * it is the room's number and stops following, because the two have parted and only one of
     * them is what the room was told.
     */
    val roomVolumePercent: Int? = null,
    /** What each handset says its volume actually came to. Said, never assumed. See [VolumeRow]. */
    val roomVolumes: List<VolumeRow> = emptyList(),
    /** Whether anything here has been changed and not yet put back. */
    val volumeChanged: Boolean = false,
    /**
     * The room's shape, on a host that is running one. Null everywhere else.
     *
     * Only the host draws: it is the handset that holds the timeline and the only one that knows
     * who else is in the room. A sink is told the rule and has nothing to say about it, which is
     * the same asymmetry the timeline itself has.
     */
    val room: RoomState? = null
)

/** What the screen can ask for. Held as one object so a preview can hand it empty lambdas. */
/**
 * One handset's answer to being told what volume to be.
 *
 * [percent] is worked out from [index] and [max] rather than echoed from what was asked, and the
 * three are shown together on purpose: setStreamVolume has been seen on these handsets to take a
 * value and move nothing, and under do-not-disturb it throws. A row that disagrees with the
 * slider is the whole reason this list exists.
 */
data class VolumeRow(
    /** Which handset this is, which is what a control for it alone has to be addressed to. */
    val peerId: String,
    val name: String,
    val percent: Int,
    val index: Int,
    val max: Int,
    val stream: String,
    /**
     * What this handset was last told to be, or null if nobody has told it anything.
     *
     * Kept apart from [percent] because they answer different questions, and until they were
     * kept apart this control did not work: the thumb was drawn from [percent], which is what
     * the handset reported, so letting go of it put the thumb back where the handset last was
     * and left it there until a report arrived. A handset that is slow to report, or not
     * reporting at all, was a handset with no working control - and the fault was invisible,
     * because a thumb sitting still looks like a thumb that has been obeyed.
     */
    val asked: Int? = null,
    /** What is wrong with this row, if anything. See [volumeComplaint].  */
    val complaint: VolumeComplaint = VolumeComplaint.NONE
)

/**
 * The two ways a handset can fail to be where it was told to be, which want different sentences.
 *
 * They look the same on screen - a row that disagrees with the thumb above it - and they are
 * nothing alike underneath. One is a stream that took a value and did not move, which is a fault
 * on that handset and has been seen on these ones. The other is a handset that is doing as it is
 * told and not saying so, which means the number beside it is simply old.
 */
enum class VolumeComplaint { NONE, NOT_SAID, REFUSED }

class HomeActions(
    val pickRole: (Role) -> Unit,
    val chooseSong: () -> Unit,
    val chooseFolder: () -> Unit,
    val captureAudio: () -> Unit,
    val scan: () -> Unit,
    val play: () -> Unit,
    val stop: () -> Unit,
    val calibrate: () -> Unit,
    val seek: (Long) -> Unit,
    val stepSong: (Int) -> Unit,
    val setPaused: (Boolean) -> Unit,
    val pairCalibrate: () -> Unit,
    val setRoomVolume: (Int) -> Unit,
    /** One handset on its own, for the one standing next to a wall. */
    val setHandsetVolume: (String, Int) -> Unit,
    val restoreVolume: () -> Unit,
    /** Opens the one system dialog that can grant it. The vendor switches it cannot. */
    val allowBackground: () -> Unit,
    val room: RoomActions
)

/**
 * Whether the pair calibration is worth offering yet.
 *
 * It runs one half on each handset, and which half this one plays is the single thing that screen
 * cannot work out for itself - both phones of a pair hold a scanned pairing, so the file says
 * nothing. Offered with no role picked, the button leads somewhere whose only message is "go back
 * and pick one".
 */
internal fun offersPairCalibration(state: HomeState): Boolean = state.role != Role.NONE

/**
 * Whether to say that this handset will be stopped the moment nobody is looking at it.
 *
 * Only once it has a part to play: with no role picked nothing here outlives the screen, so
 * there is nothing the setting would protect, and a warning that cannot be acted on usefully is
 * one people learn to scroll past.
 */
internal fun warnsAboutBackground(state: HomeState): Boolean =
    !state.backgroundAllowed && state.role != Role.NONE

/**
 * The four edges of the screen, lit in this handset's own colour.
 *
 * The colour is the handset's name, and a name is only useful where the thing it names is. A chip
 * at the top of a screen names the phone to whoever is holding it; a room of phones lying face up
 * on tables and shelves is looked at from a chair several metres away, and from there a 28dp
 * circle is nothing. The whole edge of a screen is the largest thing a phone can say from that
 * distance without covering up what it is saying it about.
 *
 * Nothing is drawn for a handset with no colour yet, which is every handset before its host has a
 * room to hand colours out in. A default colour would be worse than none: two handsets sharing one
 * is exactly the confusion the colours exist to end.
 */
private fun Modifier.badgeEdge(colour: Color?): Modifier {
    if (colour == null) return this
    return drawWithContent {
        drawContent()
        // Over the content rather than under it: the screen scrolls, and an edge drawn beneath
        // whatever happens to be at the top of the list is an edge that comes and goes.
        val band = size.minDimension * 0.045f
        val inward = listOf(colour.copy(alpha = 0.85f), Color.Transparent)
        val outward = inward.reversed()
        drawRect(Brush.verticalGradient(inward, 0f, band), size = Size(size.width, band))
        drawRect(
            Brush.verticalGradient(outward, size.height - band, size.height),
            topLeft = Offset(0f, size.height - band),
            size = Size(size.width, band)
        )
        drawRect(Brush.horizontalGradient(inward, 0f, band), size = Size(band, size.height))
        drawRect(
            Brush.horizontalGradient(outward, size.width - band, size.width),
            topLeft = Offset(size.width - band, 0f),
            size = Size(band, size.height)
        )
    }
}

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val edge = state.selfPlace?.let { BadgePalette.colourOf(it, MaterialTheme.colorScheme.primary) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Ahead of the padding below, so the edge is the screen's edge and not the text's.
            .badgeEdge(edge)
            // Android 15 draws every app edge to edge, so without this the title sits under the
            // status bar clock. Visible on the Magic6 and not on the X10, which is Android 10.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        // Above the role and before anything is running. Which phone this is does not depend on
        // either, and the moment somebody needs it is the moment they are holding two phones and
        // an instruction that names one of them.
        state.selfId?.let { self ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgeChip(self, state.selfPlace, diameter = 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.badge_this_phone, badgeWords(self, state.selfPlace)),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        when (state.role) {
            Role.NONE -> RolePicker(actions)
            Role.HOST -> HostPanel(state, actions)
            Role.SINK -> SinkPanel(state, actions)
        }
        if (state.role != Role.NONE) {
            StatePanel(state)
            // After the state and before the health numbers: it is a thing to play with while the
            // room is playing, not a thing to set up before starting.
            state.room?.let { SpatialPanel(it, actions.room) }
            HealthPanel(state.health)
            TextButton(onClick = { actions.pickRole(Role.NONE) }) {
                Text(stringResource(R.string.role_change))
            }
        }
        // Offered whatever this phone is being. The constant it measures belongs to the handset
        // rather than to a role, and it is wanted before the first session rather than during one.
        TextButton(onClick = actions.calibrate) {
            Text(stringResource(R.string.home_calibrate))
        }
        // The other calibration: that one is this handset against itself, this one is this pair
        // against each other. Both are wanted before the first session rather than during one.
        if (offersPairCalibration(state)) {
            TextButton(onClick = actions.pairCalibrate) {
                Text(stringResource(R.string.home_pair_calibrate))
            }
        }
        // Last line on the screen, because nobody wants it until the moment a room is behaving as
        // if the handsets were running different code - and then it is the first thing to check.
        // Every debug build carries the same version name, so this is the only thing that tells
        // two of them apart without a cable.
        Text(
            stringResource(R.string.home_build, BuildConfig.BUILD_MARK),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * The volume of the output a capturing host is heard on.
 *
 * Read-only, because the app cannot set this stream and a control that cannot is a lie. What moves
 * it is the handset own volume keys, which this screen aims at it while a capture is up.
 */
@Composable
private fun HostOutputVolumePanel(volume: OutputVolume) {
    Section(R.string.volume_title) {
        Text(
            stringResource(R.string.volume_level, volume.level, volume.max),
            style = MaterialTheme.typography.bodyLarge
        )
        // Shown, not offered. The app cannot set this stream - see AccessibilityVolume - so what
        // this panel does is say where the level is and where the control for it actually is.
        Text(stringResource(R.string.volume_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * One number for the whole room, and what each handset actually did with it.
 *
 * A percentage travels rather than an index, because handsets do not agree on how many steps a
 * stream has. Which stream each of them applies it to is its own business: a host capturing
 * another app is heard on the alarm stream and everybody else is on media, so "the volume" means
 * "whatever you are actually playing on" and not one stream named from here.
 */
@Composable
private fun RoomVolumePanel(state: HomeState, actions: HomeActions) {
    val percent = state.roomVolumePercent ?: return
    // Where the thumb is while a finger is on it, which is not yet where the room is. Told at the
    // end of the drag rather than through it: one drag is fifty values, and each one told to the
    // room is a frame to every handset and a thread to send it on. The number under the thumb
    // still moves, because a slider that does not is a broken slider.
    var dragging by remember { mutableStateOf<Int?>(null) }
    Section(R.string.room_volume_title) {
        Text(
            stringResource(R.string.room_volume_level, dragging ?: percent),
            style = MaterialTheme.typography.bodyLarge
        )
        Slider(
            value = (dragging ?: percent).toFloat(),
            onValueChange = { dragging = it.toInt() },
            onValueChangeFinished = {
                dragging?.let { actions.setRoomVolume(it) }
                dragging = null
            },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.capturing) {
            Text(
                stringResource(R.string.room_volume_capturing_hint),
                style = MaterialTheme.typography.bodySmall
            )
        }
        // What landed, one line per handset. Not a receipt for the message - a reading of the
        // stream afterwards - which is the only form of this that can say the word "no".
        if (state.roomVolumes.isEmpty()) {
            Text(stringResource(R.string.room_volume_none), style = MaterialTheme.typography.bodySmall)
        } else {
            for (row in state.roomVolumes) HandsetVolumeRow(row, actions)
            Text(
                stringResource(R.string.room_volume_row_hint),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.volumeChanged) {
            OutlinedButton(onClick = actions.restoreVolume, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.room_volume_restore))
            }
            Text(
                stringResource(R.string.room_volume_restore_hint),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * One handset's line, and its own control.
 *
 * The line is read back off that handset rather than echoed from what it was asked, which is why
 * it can disagree with the slider above it - and that disagreement is the only way a stream that
 * refused to move can be seen at all. The control beneath it is for the phone standing next to a
 * wall; the next drag of the room slider levels everybody again, including this one.
 */
@Composable
private fun HandsetVolumeRow(row: VolumeRow, actions: HomeActions) {
    var dragging by remember(row.peerId) { mutableStateOf<Int?>(null) }
    Text(
        stringResource(
            R.string.room_volume_row,
            row.name,
            row.percent,
            row.index,
            row.max,
            stringResource(
                if (row.stream == ALARM_STREAM_NAME) R.string.room_volume_alarm
                else R.string.room_volume_media
            )
        ),
        style = MaterialTheme.typography.bodySmall
    )
    if (row.complaint != VolumeComplaint.NONE) {
        Text(
            if (row.complaint == VolumeComplaint.NOT_SAID) {
                stringResource(R.string.room_volume_row_silent, row.name)
            } else {
                stringResource(R.string.room_volume_row_missed, row.name, row.asked ?: 0)
            },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Slider(
        // The thumb is where this handset was told to be, and the line above is where it says it
        // is. Driving the thumb from the report is what made this control unusable.
        value = (dragging ?: row.asked ?: row.percent).toFloat(),
        onValueChange = { dragging = it.toInt() },
        onValueChangeFinished = {
            dragging?.let { actions.setHandsetVolume(row.peerId, it) }
            dragging = null
        },
        valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth()
    )
}

/** The name [com.soundmesh.probe.sync.HandsetVolume] puts on the wire for the alarm stream. */
private const val ALARM_STREAM_NAME = "ALARM"

@Composable
private fun RolePicker(actions: HomeActions) {
    Section(R.string.role_pick) {
        Button(onClick = { actions.pickRole(Role.HOST) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.role_host))
        }
        Text(stringResource(R.string.role_host_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { actions.pickRole(Role.SINK) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.role_sink))
        }
        Text(stringResource(R.string.role_sink_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HostPanel(state: HomeState, actions: HomeActions) {
    Section(R.string.song_title) {
        Text(
            when {
                state.checking -> stringResource(R.string.song_checking)
                state.capturing -> stringResource(R.string.song_capturing)
                // A folder named 夜曲 and a song named 夜曲 read identically otherwise, and the
                // difference is what happens for the next hour.
                state.songName != null && state.songIsFolder ->
                    stringResource(R.string.song_folder_chosen, state.songName)
                state.songName != null -> state.songName
                else -> stringResource(R.string.song_none)
            },
            style = MaterialTheme.typography.bodyLarge
        )
        state.problem?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedButton(onClick = actions.chooseSong, enabled = !state.checking) {
            Text(stringResource(R.string.song_choose))
        }
        OutlinedButton(onClick = actions.chooseFolder, enabled = !state.checking) {
            Text(stringResource(R.string.song_choose_folder))
        }
        OutlinedButton(onClick = actions.captureAudio, enabled = !state.checking && !state.capturing) {
            Text(stringResource(R.string.song_capture))
        }
        if (state.capturing) {
            Text(stringResource(R.string.song_capture_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
    state.hostOutputVolume?.let { HostOutputVolumePanel(it) }
    RoomVolumePanel(state, actions)
    Section(R.string.pair_code) {
        if (state.pairingPayload == null) {
            Text(stringResource(R.string.pair_no_address))
        } else {
            // Remembered against the payload rather than the recomposition: the screen redraws five
            // times a second and encoding a QR code that often would be the most expensive thing on
            // it, for a picture that changes only when the address does.
            val code = remember(state.pairingPayload) {
                PairingCodeImage.bitmap(state.pairingPayload, PairingCodeImage.DEFAULT_PIXELS).asImageBitmap()
            }
            Image(code, contentDescription = null, modifier = Modifier.size(240.dp))
            Text(state.pairingPayload, style = MaterialTheme.typography.bodySmall)
        }
    }
    CaptureSilenceLine(state)
    StandbyLine(state, actions)
    PlayControls(state, actions, canPlay = state.capturing || state.songName != null)
}

@Composable
private fun SinkPanel(state: HomeState, actions: HomeActions) {
    Section(R.string.pair_title) {
        Text(
            state.paired?.let {
                stringResource(R.string.pair_with, it.hostId, it.address, it.chunkPort)
            } ?: stringResource(R.string.pair_none),
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedButton(onClick = actions.scan) { Text(stringResource(R.string.pair_scan)) }
        Text(stringResource(R.string.pair_scan_hint), style = MaterialTheme.typography.bodySmall)
    }
    StandbyLine(state, actions)
    PlayControls(state, actions, canPlay = state.paired != null)
}

/**
 * Said only once the silence has gone on longer than a song could plausibly be quiet for.
 *
 * A gap between tracks is a second or two of nothing and is not a fault; a path that has stopped
 * producing does not come back on its own. The threshold is what separates them, and it is short
 * enough that somebody watching a silent room reaches it before they reach for the phone.
 */
internal fun capturesNothingWorthSaying(seconds: Int?): Boolean =
    seconds != null && seconds >= CAPTURE_SILENCE_SECONDS

/** The same number the record is kept by: see [CaptureSilence.SPELL_SECONDS] for why it is one. */
const val CAPTURE_SILENCE_SECONDS = CaptureSilence.SPELL_SECONDS

@Composable
private fun CaptureSilenceLine(state: HomeState) {
    if (!capturesNothingWorthSaying(state.captureSilentSeconds)) return
    Text(
        stringResource(R.string.capture_silent, state.captureSilentSeconds ?: 0),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}

/**
 * Whether this handset can be started from the host, said in one line.
 *
 * The whole feature is invisible when it works - somebody presses one button and three phones do
 * something - so the only thing a person can check beforehand is this. A host that says two and a
 * room of three is the one case worth catching, and it is caught by looking rather than by having
 * the third phone sit there silently while the other two play.
 */
@Composable
private fun StandbyLine(state: HomeState, actions: HomeActions) {
    Text(
        when (state.role) {
            Role.HOST -> stringResource(R.string.standby_host, state.standingBy)
            Role.SINK ->
                if (state.onStandby) stringResource(R.string.standby_sink)
                else stringResource(R.string.standby_sink_alone)
            Role.NONE -> return
        },
        style = MaterialTheme.typography.bodySmall
    )
    if (warnsAboutBackground(state)) {
        Text(
            stringResource(R.string.background_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        TextButton(onClick = actions.allowBackground) {
            Text(stringResource(R.string.background_allow))
        }
    }
    if (state.role == Role.HOST && state.uncalibrated > 0) {
        Text(
            stringResource(R.string.standby_uncalibrated, state.uncalibrated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (state.role == Role.HOST && state.approximate > 0) {
        Text(
            stringResource(R.string.standby_approximate, state.approximate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlayControls(state: HomeState, actions: HomeActions, canPlay: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = actions.play,
            enabled = canPlay && !state.running && !state.checking,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.play_start))
        }
        OutlinedButton(onClick = actions.stop, enabled = state.running, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.play_stop))
        }
    }
    state.nowPlaying?.let {
        Text(stringResource(R.string.now_playing, it), style = MaterialTheme.typography.bodyMedium)
    }
    state.playhead?.let { PlayheadPanel(it, state.paused, actions.seek, actions.stepSong, actions.setPaused) }
}

/**
 * Where the song is, somewhere to drag it to, and the two songs either side of it.
 *
 * The position shown while a finger is down is the finger's, not the room's: a slider that snapped
 * back to the music every time the screen refreshed would be one nobody could aim. The jump is
 * asked for on release rather than as it moves, because each one empties every queue in the room
 * and a drag across a five minute song would ask for a hundred of them.
 *
 * **It goes quiet for about a second and a half after a jump.** That is the lead every chunk is
 * stamped with; the alternative was carrying on playing the old place for the same length of time,
 * which sounds like the app ignoring the drag. The buttons cost the same silence for the same
 * reason - they are the same jump.
 *
 * Here rather than beside start and stop, because this whole panel is drawn only for a source that
 * can say how long it is - and a source that cannot say that has no list to step through either.
 * A folder of one still gets them: next ends it and previous plays it again, which is what those
 * two words mean on a list of one and is better than a button that is there but does nothing.
 */
@Composable
private fun PlayheadPanel(
    playhead: Playhead,
    paused: Boolean,
    seek: (Long) -> Unit,
    stepSong: (Int) -> Unit,
    setPaused: (Boolean) -> Unit
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    // Where the finger first landed, which is what tells a drag from a brush. A Material slider
    // treats a touch anywhere on the track as a complete gesture and reports it the same way it
    // reports a drag, so a sleeve across the screen used to buy a real jump and a second and a
    // half of silence in every handset in the room.
    var landedAt by remember { mutableStateOf<Float?>(null) }
    val duration = playhead.durationMicros.coerceAtLeast(1L)
    val position = dragging ?: (playhead.positionMicros.toFloat() / duration)
    Text(
        stringResource(
            R.string.play_position,
            clockOf((position * duration).toLong()),
            clockOf(playhead.durationMicros)
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Slider(
        value = position.coerceIn(0f, 1f),
        onValueChange = {
            if (landedAt == null) landedAt = it
            dragging = it
        },
        onValueChangeFinished = {
            val from = landedAt
            val to = dragging
            if (from != null && to != null) {
                draggedTo(from, to)?.let { seek((it * duration).toLong()) }
            }
            landedAt = null
            dragging = null
        }
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = { stepSong(-1) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.play_previous))
        }
        Button(onClick = { setPaused(!paused) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(if (paused) R.string.play_resume else R.string.play_pause))
        }
        OutlinedButton(onClick = { stepSong(1) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.play_next))
        }
    }
}

/**
 * Where a slider gesture is asking the song to go, or null when it was not asking.
 *
 * A Material slider cannot tell the caller whether the finger moved: a touch on the track is a
 * whole gesture, reported exactly as a drag is, and it lands the value wherever the finger was.
 * That is right for a control somebody is aiming at and wrong for one somebody is listening past -
 * every jump empties every queue in the room and costs about a second and a half of silence, so a
 * sleeve across the screen was buying the loudest thing the screen can do.
 *
 * The rule is that a gesture has to have gone somewhere. [MIN_DRAG] is a fraction of the track
 * rather than of the song, because it is describing a finger and not a piece of music - a hundredth
 * of a phone's width is under two millimetres, well inside what a deliberate drag covers and
 * outside what a touch that meant to be still does.
 */
internal fun draggedTo(landedAt: Float, leftAt: Float): Float? =
    if (abs(leftAt - landedAt) < MIN_DRAG) null else leftAt

/** How far a finger has to travel before the room is asked to jump. */
internal const val MIN_DRAG = 0.01f

/** Minutes and seconds, from microseconds. Hours are somebody else's problem. */
private fun clockOf(micros: Long): String {
    val seconds = (micros / 1_000_000L).coerceAtLeast(0L)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun StatePanel(state: HomeState) {
    Section(R.string.state_title) {
        Text(
            if (!state.running && state.failure != null) {
                StateWording.failure(state.failure)
                    ?.let { stringResource(it) }
                    ?: stringResource(R.string.state_failed, state.failure)
            } else {
                stringResource(StateWording.of(state.sessionState))
            },
            style = MaterialTheme.typography.titleMedium
        )
        if (state.calledHere.isNotEmpty()) {
            Reading(stringResource(R.string.state_called_here), state.calledHere)
        }
        for (counter in state.counters) Reading(stringResource(counter.label), counter.value)
    }
}

@Composable
private fun HealthPanel(health: Health) {
    val unknown = stringResource(R.string.health_unknown)
    Section(R.string.health_title) {
        Reading(
            stringResource(R.string.health_battery),
            health.batteryPercent?.let { "$it%" } ?: unknown
        )
        Reading(
            stringResource(R.string.health_charging),
            health.charging?.let { if (it) "✓" else "—" } ?: unknown
        )
        Reading(
            stringResource(R.string.health_temperature),
            health.celsius?.let { String.format(null as java.util.Locale?, "%.1f °C", it) } ?: unknown
        )
        Reading(
            stringResource(R.string.health_thermal),
            health.thermalStatus?.let { stringResource(StateWording.thermal(it)) } ?: unknown
        )
    }
}

/** One label and one number, the shape every readable line on this screen has. */
@Composable
private fun Reading(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
internal fun Section(title: Int, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/**
 * Where the room slider sits, or null where there is no slider to sit anywhere.
 *
 * Following this phone until somebody drags it is what makes the slider start where the person
 * expects: at whatever this phone is already playing at. After a drag it is the room's number,
 * and following would fight whoever is holding it.
 *
 * [dragged] has to be exactly "somebody dragged it", which is why it is not the flag saying a
 * volume has been changed and not yet put back. That one is read off a file which outlives the
 * app, so it is already true at the next start - before a role has been picked, when there is
 * nothing to show - and latching on it hid this whole panel, restore button and all, for every
 * session after the first one that touched a volume.
 */
internal fun roomVolumeShown(
    isHost: Boolean,
    dragged: Boolean,
    shown: Int?,
    onThisPhone: () -> Int
): Int? = when {
    !isHost -> null
    dragged -> shown
    else -> onThisPhone()
}

/**
 * Whether a handset landed where it was told to, judged on its own scale rather than in percent.
 *
 * A percentage is what travels, and it lands on whichever step is nearest on that handset: 34 per
 * cent of fifteen steps is step five, which reads back as 33 per cent. Comparing the two
 * percentages would call that a disagreement every single time and the warning would mean nothing.
 * Comparing the steps calls it agreement, and keeps the word "no" for a stream that actually
 * refused to move - which is the thing this list exists to be able to say.
 */
internal fun landedWhereAsked(asked: Int?, index: Int, max: Int): Boolean =
    asked == null || indexFor(asked, max) == index

/**
 * Whether somebody standing at that handset moved it, as opposed to it refusing to move.
 *
 * Both look the same on one reading - a handset that is not where it was told to be - and they
 * want opposite things. A person who just pressed the volume keys on their own phone should see
 * the thumb on the host follow them; a stream that took the value and did nothing should leave
 * the thumb where it was put and be called out for it.
 *
 * What tells them apart is whether the reading moved at all. A refusal is a reading that did not
 * change; a person is a reading that changed to somewhere nobody asked for.
 */
internal fun somebodyElseMovedIt(asked: Int?, before: Int?, now: Int, max: Int): Boolean =
    asked != null && before != null && now != before && now != indexFor(asked, max)

/**
 * What to say about a handset that is not where it was told to be, if anything.
 *
 * Three answers rather than a boolean, and the third one is the point. Reported on 2026-09-14:
 * one handset showed "did not reach what you asked for" no matter what it was told, while its
 * volume plainly changed in the room. Both halves were true - it was obeying, and the number
 * beside it was not what was asked - because that number was the one it reported when it
 * connected and it has not reported since. "It has not said" and "it would not move" are the two
 * things worth telling apart here, and a single red line said neither.
 *
 * The grace is not politeness. A report crosses a room and back, so for a moment after every
 * instruction every handset is behind - and a warning that flashes on every drag is one nobody
 * reads by the end of the evening.
 */
internal fun volumeComplaint(
    asked: Int?,
    index: Int,
    max: Int,
    askedAt: Long,
    saidAt: Long?,
    now: Long
): VolumeComplaint = when {
    asked == null -> VolumeComplaint.NONE
    now - askedAt < VOLUME_GRACE_MILLIS -> VolumeComplaint.NONE
    saidAt == null || saidAt < askedAt -> VolumeComplaint.NOT_SAID
    landedWhereAsked(asked, index, max) -> VolumeComplaint.NONE
    else -> VolumeComplaint.REFUSED
}

/** Long enough for a handset to hear, set its stream and answer across a room. */
internal const val VOLUME_GRACE_MILLIS = 1_500L
