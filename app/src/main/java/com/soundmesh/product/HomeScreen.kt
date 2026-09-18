package com.soundmesh.product

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundmesh.core.PairingCode
import com.soundmesh.core.SessionState
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.CaptureSilence
import com.soundmesh.probe.sync.HostPairingCode
import com.soundmesh.probe.sync.LocalAddress
import com.soundmesh.probe.sync.indexFor
import com.soundmesh.probe.sync.Playhead

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
    /**
     * The code to show a peer and the network it is good on, or null when this handset cannot
     * name one address it would be reached at. See [HostPairingCode].
     */
    val pairingOffer: HostPairingCode.Offer? = null,
    /**
     * Which networks this handset could hand out a code for, in the order [LocalAddress] lists
     * them. One entry means there is nothing to choose and the screen offers no choice.
     *
     * Two is what Android 11 made possible and what these handsets do: an access point up and a
     * network joined at the same time. The app cannot know which of them the phone doing the
     * scanning can see, so somebody says - see [HostPairingCode].
     */
    val codeChoices: List<LocalAddress.ReachedBy> = emptyList(),
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
     * The standing handsets one by one, for the screen that draws them rather than counts them.
     *
     * Beside the three counts rather than instead of them: a count is the right shape for the
     * checklist's "is anything wrong at all", and the wrong shape for the only thing anybody can
     * actually do about it, which happens at one particular phone. See [StandingRow].
     */
    val standing: List<StandingRow> = emptyList(),
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
     * This handset's own output lead, in milliseconds, or null if it has never been measured.
     *
     * On the home screen rather than only on the calibration screen, because on 2026-09-13 two
     * handsets played a whole afternoon carrying nothing: the app already said so, on a page in
     * each of them that nobody had a reason to open.
     */
    val selfCalibrated: Double? = null,
    /**
     * The names of standing handsets that have told this host they are not exempt from power
     * saving - which is to say, the ones that will be killed the moment their screen is left.
     *
     * Names rather than a count, because the thing to do about it is walk over to one of them,
     * and a count cannot say which.
     */
    val blockedPeerNames: List<String> = emptyList(),
    /**
     * What the rule is asking of each handset right now - see [roomReadings].
     *
     * Empty unless the diagnostic switch is on, and empty on a sink whatever the switch says.
     * It is the one thing here that has to be worked out afresh on every pass of the poll, so a
     * screen that is not showing it does not pay for it.
     */
    val roomReadings: List<RoomReading> = emptyList(),
    /**
     * How many seconds the capture has been handing over exactly zero, or null when it is not.
     *
     * On screen because the failure it names is invisible from every other direction: the session
     * says PLAYING, the counters are healthy, the drift is fine, and the room is silent. A
     * listener who cannot see this has nothing to tell anybody except that the music stopped.
     */
    val captureSilentSeconds: Int? = null,
    val running: Boolean = false,
    /**
     * Between the tap on play and a session being up, which is a second or more of decoding a
     * source and binding sockets.
     *
     * Its job is to grey the button out for that second. Tapping again while it is true is what
     * used to start a second session that could not have the ports the first one took - see
     * SessionService.startSession, which refuses that now on its own. This is the visible half:
     * a button that goes quiet is the only way of saying the tap landed.
     */
    val starting: Boolean = false,
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
    val room: RoomState? = null,
    /**
     * Whether this handset is on WiFi right now, from ConnectivityManager's active network
     * capabilities.
     *
     * Its own field rather than folded into [wifiName] being non-null: reading the actual SSID
     * needs ACCESS_FINE_LOCATION and live location services since Android 10, which this app does
     * not ask for just to print a network name, so "on WiFi with an unreadable name" and "not on
     * WiFi at all" have to be told apart some other way.
     *
     * Shown on the welcome screen because a phone on the wrong WiFi looks identical to a phone
     * that has not been told anything is wrong - the fix is switching networks and coming back,
     * so a stale value here would go on saying "connected" after somebody already left.
     */
    val onWifi: Boolean = false,
    /**
     * The SSID of the WiFi this handset is on, when it comes back as an actual name - see
     * [readableSsid]. Null whenever [onWifi] is false, and also null on WiFi with no readable
     * name.
     */
    val wifiName: String? = null,
    /**
     * This handset's own address on that WiFi, and how much of it names the network.
     *
     * Read for one purpose: telling a sink that cannot reach its host whether the host's address is
     * even on this network. See [joinTrouble] - it is the half of that answer that needs no packet.
     * Null when it could not be read, which is not evidence of anything.
     */
    val localNet: IpSubnet? = null
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
    /**
     * Line this handset up with one named peer, from that peer's own row on the status screen.
     *
     * Takes the peer because it tells it: a pair round needs both ends going at once, and until
     * this existed somebody had to press start here and then walk to the other phone and press
     * start there, in that order, or nothing happened at all.
     */
    val calibratePeer: (String) -> Unit,
    val setRoomVolume: (Int) -> Unit,
    /** One handset on its own, for the one standing next to a wall. */
    val setHandsetVolume: (String, Int) -> Unit,
    val restoreVolume: () -> Unit,
    /** Opens the one system dialog that can grant it. The vendor switches it cannot. */
    val allowBackground: () -> Unit,
    /** The gear in the top bar. */
    val openSettings: () -> Unit,
    /**
     * Back onto the playing stage after having pressed back out of it.
     *
     * The one way forward again, and it has to exist: back out of a playing room and the music
     * goes on playing with no control of it on screen, and the start button is not it - it would
     * start a second session over the top of the first.
     */
    val backToPlaying: () -> Unit,
    /**
     * Onto the playing stage with nothing playing, which is what the status board's button does.
     *
     * Not [play]. The board used to start the room, which meant it had to refuse anybody who had
     * not picked a song - and the screen that picks one is the playing stage. So the one door was
     * locked by the one thing on the other side of it.
     */
    val enterPlaying: () -> Unit,
    /** The full-screen pairing code, which is the whole screen because it is read from a metre away. */
    val showPairCode: () -> Unit,
    /**
     * Says which of two networks the code should name, and lets go of everybody standing by.
     *
     * The letting go is not a side effect, it is the point. A handset that joined over the hotspot
     * goes on obeying after the code is switched to the joined WiFi, so without it a room ends up
     * half on each network - and the phones that fall out of that do so one at a time, hours
     * later, with nothing to connect it to a tap somebody made at the start.
     */
    val setCodeNetwork: (LocalAddress.ReachedBy) -> Unit,
    /**
     * Where a checklist line's button goes. See [ReadyGoto].
     *
     * Takes a [PeerJob] alongside the destination because [ReadyGoto.PAIR_CALIBRATE] covers three
     * different measurements on the same screen - see [PeerJob] - and the checklist line that
     * offers it knows which one it is offering. Every other destination ignores it and callers
     * that are not asking for one of those three pass null.
     */
    val goto: (ReadyGoto, PeerJob?) -> Unit,
    val room: RoomActions
)

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
 *
 * [glow] is 0..1 and moves the band's width and alpha together - see [edgeGlow] for where it comes
 * from. The drawing itself is unchanged; only how wide and how bright it is at this instant moves.
 */
private fun Modifier.badgeEdge(colour: Color?, glow: Float): Modifier {
    if (colour == null) return this
    return drawWithContent {
        drawContent()
        // Over the content rather than under it: the screen scrolls, and an edge drawn beneath
        // whatever happens to be at the top of the list is an edge that comes and goes.
        val band = size.minDimension * 0.045f * (0.6f + 0.8f * glow)
        val inward = listOf(colour.copy(alpha = 0.85f * (0.4f + 0.6f * glow)), Color.Transparent)
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
fun HomeScreen(
    state: HomeState,
    actions: HomeActions,
    showDetails: Boolean,
    steppedBack: Boolean,
    holding: Boolean = false,
    onBack: () -> Unit = {}
) {
    val route = routeOf(state, steppedBack, holding)
    val glow = edgeGlow(state)
    // Dimmed rather than only slowed: a disconnected sink's edge is meant to read as grey from
    // across the room, not just as a quieter version of its own colour.
    val edge = state.selfPlace?.let { BadgePalette.colourOf(it, MaterialTheme.colorScheme.primary) }
        ?.let { if (disconnectedSink(state)) it.copy(alpha = 0.3f) else it }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Ahead of the padding below, so the edge is the screen's edge and not the text's.
            .badgeEdge(edge, glow)
            // Android 15 draws every app edge to edge, so without this the title sits under the
            // status bar clock. Visible on the Magic6 and not on the X10, which is Android 10.
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 6.dp)) {
            TopBar(route, actions, onBack)
        }
        // Which of the three stages this handset is on is worked out fresh every draw rather than
        // remembered - see routeOf() - so there is one answer rather than two that can disagree.
        when (route) {
            HomeRoute.WELCOME -> ScrollingStage { WelcomeScreen(state, actions) }
            HomeRoute.READY -> {
                // Above the scroll rather than inside it. With a room already playing this is the
                // only thing on the board that cannot wait for somebody to scroll back up to it -
                // the music is on and there are no controls for it anywhere else on screen.
                if (state.running) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Solid(stringResource(R.string.ready_back_to_play), onClick = actions.backToPlaying)
                    }
                }
                ScrollingStage { ReadyScreen(state, actions) }
            }
            // PlayingScreen carries its own bottom tab bar, which is why its stage is not wrapped
            // in the same whole-page scroll the other two stages use: a bar pinned to the bottom
            // of the screen cannot sit inside a column that scrolls as a whole, or the tabs would
            // carry it away with them. It gets the remaining height instead.
            HomeRoute.PLAYING -> when (state.role) {
                // showDetails is HomeActivity state, not read from Preferences here - a read
                // in the composable would not repaint the instant the switch on the settings
                // screen is flipped. See the plan's "另外两条".
                Role.HOST, Role.SINK -> PlayingScreen(state, actions, showDetails, modifier = Modifier.weight(1f))
                Role.NONE -> Unit
            }
        }
    }
}

/**
 * The scrolling body every stage but [HomeRoute.PLAYING] draws into.
 *
 * Its own column rather than folding into the outer one: [HomeRoute.PLAYING] needs the outer
 * column to stop scrolling as a whole once its bottom tab bar exists, and the other two stages
 * keep scrolling exactly as they did before that split.
 */
@Composable
private fun ColumnScope.ScrollingStage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        // Nothing between blocks: a label carries its own space above it, and a run of hairline
        // rows is meant to read as one list rather than as rows with gaps. See Look.kt.
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content
    )
}

/**
 * Where you are, the way back, and the way into settings.
 *
 * It used to carry this handset's own badge and name instead, on every stage. That answered "which
 * phone is this" and nothing else, and it answered it three times over: the name is now the first
 * row of the roster, where it sits beside the other phones it is being told apart from. What the
 * bar says instead is which of the three stages this is, which is the thing a back gesture needs
 * somebody to be able to see before they use it.
 *
 * No arrow on the first stage, because there is nowhere above it - see [routeOf].
 */
@Composable
private fun TopBar(route: HomeRoute, actions: HomeActions, onBack: () -> Unit) {
    val settingsDescription = stringResource(R.string.settings_open)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (route != HomeRoute.WELCOME) {
            Text(
                "←",
                modifier = Modifier.clickable(onClick = onBack),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            stringResource(stageTitle(route)),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.settings_open),
            modifier = Modifier
                .clickable(onClick = actions.openSettings)
                .semantics { contentDescription = settingsDescription },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** What each stage is called at the top of it. */
@androidx.annotation.StringRes
private fun stageTitle(route: HomeRoute): Int = when (route) {
    HomeRoute.WELCOME -> R.string.welcome_title
    HomeRoute.READY -> R.string.ready_title
    HomeRoute.PLAYING -> R.string.play_title
}

/**
 * The volume of the output a capturing host is heard on.
 *
 * Read-only, because the app cannot set this stream and a control that cannot is a lie. What moves
 * it is the handset own volume keys, which this screen aims at it while a capture is up.
 */
@Composable
internal fun HostOutputVolumePanel(volume: OutputVolume) {
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
internal fun RoomVolumePanel(state: HomeState, actions: HomeActions) {
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
 *
 * Collapsed to the one summary line by default - a room with several handsets used to spend one
 * whole slider's worth of height on every one of them, which is what pushed the pairing code four
 * to six screens down. That summary line is what names this row to somebody reading the room, so
 * nothing about who is who is lost by hiding the slider and the complaint beneath it; a tap on the
 * line brings both back.
 */
@Composable
private fun HandsetVolumeRow(row: VolumeRow, actions: HomeActions) {
    var expanded by remember(row.peerId) { mutableStateOf(false) }
    var dragging by remember(row.peerId) { mutableStateOf<Int?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The colour, not just the name: it is how somebody standing across the room tells
            // this row apart from the room's own colours, and collapsing the row must not
            // collapse the half of the identity that reads from a distance. Place is unknown here
            // - VolumeRow carries only the peerId - so BadgeChip falls back to one colour for all,
            // the same fallback the top bar's own badge uses before a room has handed one out.
            BadgeChip(row.peerId, null, diameter = 18.dp)
            Spacer(Modifier.width(8.dp))
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
        }
        Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodySmall)
    }
    if (!expanded) return
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


/**
 * The host's choice of what to play, shared between the ready checklist and the playing screen.
 *
 * Pulled out of the flat host panel this replaces rather than left inline, so the checklist stage
 * does not carry a second copy of the same three buttons - it draws exactly what the playing stage
 * already has.
 */
@Composable
internal fun SongSection(state: HomeState, actions: HomeActions) {
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
            // Collapsed by default: three sentences long, and a checklist read top to bottom
            // before a room is even playing should not have to read all of them every time.
            var showingHint by remember { mutableStateOf(false) }
            TextButton(onClick = { showingHint = !showingHint }) {
                Text(stringResource(R.string.explain_show))
            }
            if (showingHint) {
                Text(stringResource(R.string.song_capture_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
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
internal fun CaptureSilenceLine(state: HomeState) {
    if (!capturesNothingWorthSaying(state.captureSilentSeconds)) return
    Text(
        stringResource(R.string.capture_silent, state.captureSilentSeconds ?: 0),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}

/**
 * Whether this handset can be started from the host, said in one line, and what is in the way.
 *
 * Only the sink says it now. The host used to carry a paragraph here explaining what standby is
 * for, under its volume sliders, and it was removed on 2026-09-18: the count it opened with is
 * already on the state screen beside the roster, and the rest of it was read once. What stays on
 * the host is the part that is about something being wrong - a permission not granted, a handset
 * not calibrated - which is why this is a skipped line rather than an early return.
 */
@Composable
internal fun StandbyLine(state: HomeState, actions: HomeActions) {
    if (state.role == Role.NONE) return
    if (state.role == Role.SINK) {
        Text(
            if (state.onStandby) stringResource(R.string.standby_sink)
            else stringResource(R.string.standby_sink_alone),
            style = MaterialTheme.typography.bodySmall
        )
        // Underneath the general line rather than instead of it, because the two say different
        // things: that one is what to do, this one is why. Only while the line is down - a paired
        // handset that is standing by has nothing to diagnose, and the reading behind this goes on
        // being true whether or not it matters.
        val trouble =
            if (state.onStandby) null
            else joinTrouble(state.onWifi, state.localNet, state.paired?.address)
        if (trouble != null) {
            Text(
                if (trouble == JoinTrouble.OTHER_NETWORK) stringResource(
                    joinWording(trouble),
                    state.paired?.address.orEmpty(),
                    state.localNet?.address.orEmpty()
                ) else stringResource(joinWording(trouble)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
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
internal fun PlayControls(state: HomeState, actions: HomeActions, canPlay: Boolean) {
    // Only where a length is known, which is a file or a folder. A capture has no end to slide
    // towards, and a slider whose right-hand end is a guess is worse than no slider - see
    // HomeState.playhead.
    state.playhead?.let { PlayheadPanel(it, actions.seek) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.weight(1f))
        // Previous and next are a folder's, because a single file has no neighbours and a capture
        // is not a queue at all. Drawn quiet rather than hidden, so the row does not change shape
        // under a finger that is reaching for the middle of it.
        val stepping = state.songIsFolder && !state.capturing
        Transport(TransportIcon.PREVIOUS, enabled = stepping) { actions.stepSong(-1) }
        // Pause and resume in all three modes, capture included: a capture that is paused stops
        // handing chunks over, which is the room going quiet, which is what the button says.
        Transport(
            if (state.paused) TransportIcon.PLAY else TransportIcon.PAUSE,
            enabled = state.running,
            filled = true
        ) {
            if (state.running) actions.setPaused(!state.paused) else actions.play()
        }
        Transport(TransportIcon.NEXT, enabled = stepping) { actions.stepSong(1) }
        Spacer(Modifier.weight(1f))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!state.running) {
            Button(
                onClick = actions.play,
                enabled = canPlay && !state.checking && !state.starting,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.play_start)) }
        } else {
            OutlinedButton(onClick = actions.stop, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.play_stop))
            }
        }
    }
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
private fun PlayheadPanel(playhead: Playhead, seek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    // Where the finger first landed, which is what tells a drag from a brush. A Material slider
    // treats a touch anywhere on the track as a complete gesture and reports it the same way it
    // reports a drag, so a sleeve across the screen used to buy a real jump and a second and a
    // half of silence in every handset in the room.
    var landedAt by remember { mutableStateOf<Float?>(null) }
    val duration = playhead.durationMicros.coerceAtLeast(1L)
    val position = dragging ?: (playhead.positionMicros.toFloat() / duration)
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
    // Under the track and at its two ends, which is where a person looks for them: the elapsed
    // time is read against the thumb, and the length is read against the end of the track.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            clockOf((position * duration).toLong()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            clockOf(playhead.durationMicros),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
internal fun StatePanel(state: HomeState) {
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
internal fun HealthPanel(health: Health) {
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
