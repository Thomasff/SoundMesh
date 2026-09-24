package com.soundmesh.product

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.PowerManager
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import com.soundmesh.core.PeerBadge
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.RoomOrder
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.EventLog
import com.soundmesh.probe.sync.HandsetVolume
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.HostSearch
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.RoomCommandClient
import com.soundmesh.probe.sync.StoredApproximateCalibration
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.VolumeReading
import com.soundmesh.probe.sync.VolumeSaid
import com.soundmesh.probe.sync.handsetName
import com.soundmesh.probe.sync.radioHoldOf
import com.soundmesh.probe.sync.tellHostWhy
import com.soundmesh.session.CpuAwake
import com.soundmesh.session.SessionService
import com.soundmesh.session.cpuHoldOf

/**
 * Holds this handset's standing line to its host, whether or not anybody is looking at the screen.
 *
 * The line used to live on the home screen, closed on every pause, and the reason given was that an
 * app in the background cannot start an activity - so a command arriving then could not be obeyed.
 * That is true of exactly one command. Playing, stopping and setting a volume need nothing on
 * screen at all, and a phone put face down on a table is the ordinary way a room of them is used:
 * three handsets asleep in their corners and one in somebody's hand. With the line on the screen,
 * those three were not in the room.
 *
 * Measuring still needs a screen, and says so - see [RoomExcuse.ASLEEP] - rather than failing
 * quietly, because the fix is a person walking over and picking that phone up.
 *
 * A foreground service, on the same terms as [SessionService] and for the same reason: the work has
 * to outlive whatever started it, and a notification is what a phone that is quietly obeying
 * somebody else's button owes the person holding it.
 */
class StandbyService : Service() {

    /** The language this app was told to be, put on before anything here reads a string. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.inChosenLanguage())
    }

    private val handler = Handler(Looper.getMainLooper())
    private val events: EventLog by lazy { EventLog(filesDir) }
    private val handsetVolume by lazy {
        HandsetVolume(getSystemService(AudioManager::class.java), filesDir)
    }

    private var line: RoomCommandClient? = null

    /** What [line] was dialled with, so a change of any of it is a line worth rebuilding. */
    private var dialled: StandbyAnnounce? = null

    /** Whether a look for a host is out on its own thread right now. See [lookForAHost]. */
    @Volatile
    private var searching = false

    /** When the last look started, so they are spaced rather than run back to back. */
    private var lookedAt = 0L

    /**
     * When the line to the stored host last carried anything, or 0 while it is carrying now.
     *
     * Measured from the line rather than from the last look, because they answer different
     * questions: how long since anybody looked says whether a look is due, and how long the line
     * has been down says whether one is worth doing at all. See [lookForAHost].
     *
     * Carrying rather than open - see [carrying]. The two are not the same thing and the
     * difference is exactly the network change this clock is for.
     */
    private var downSince = 0L

    /** The last volume this handset got up the line, so only a change is worth a frame. */
    private var saidVolume: VolumeReading? = null

    /** The same for the battery exemption - see sayPowerIfChanged. Null means never said. */
    private var saidExempt: Boolean? = null

    /**
     * The volume keys are what move a stream without anybody asking, and there is no broadcast for
     * it on the versions here. A second is fine: this is a number on somebody else's screen, not a
     * control loop.
     */
    private val tellIfMoved = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            lastGap = gap.since(now)
            worst.ran(lastGap)
            worst.network(onWiFi(), now)
            sayVolumeIfMoved()
            sayPowerIfChanged()
            sayHereIfDue()
            lookForAHost()
            dialIfChanged()
            showTheLineIfItChanged()
            handler.postDelayed(this, TELL_EVERY_MILLIS)
        }
    }

    /** Whether what the notification says is still true. */
    private val notice = StandbyNotice()

    /**
     * Whether this service actually holds the microphone half of its foreground type.
     *
     * Read before a round rather than assumed from the permission: the type can be refused on its
     * own, and a round that opened no microphone would report silence as a room nobody could hear.
     */
    @Volatile
    private var micTyped = false

    /** What a round running here has to say, or null when none is. Goes in the notification. */
    @Volatile
    private var measuring: String? = null

    /**
     * Kept from suspending for as long as this handset is standing by.
     *
     * The night this was taken: a P30 with its screen off was dropped by the host, and its own
     * notification said nought attempts had failed to go out over the minute that followed - so
     * [tellIfMoved] had not run once. A foreground service keeps this process from being killed;
     * it does not keep the SoC from suspending, which an Android handset does about a minute
     * after the screen goes off. [CpuAwake] is the same fault caught session-side, and the note
     * there is worth reading: this is one of two halves, and the other is thread priority.
     *
     * **The cost, said rather than hidden.** A handset standing by is a handset not deep
     * sleeping, and that is battery for as long as it stands there. It is the price of the thing
     * being built - a phone left in a corner that plays when somebody else presses play - and
     * whoever does not want to pay it stops standing by, which is a button on the home screen.
     */
    private val awake by lazy { CpuAwake(cpuHoldOf(this)) }

    /** Whether the handset actually handed that lock over. Beside the symptom, not in a log. */
    private var heldAwake = false

    /**
     * The WiFi radio, held on the same terms as the CPU and for the half the CPU does not cover.
     *
     * Taken on 2026-09-14 after the CPU lock landed and changed nothing measurable: the loop was
     * running at its full rate, the process was the one that started, the lock was held - and the
     * host still let go of this handset seconds after its screen went off. What a write does when
     * the network underneath it has gone is succeed, into a kernel buffer, saying nothing; so the
     * handset noticed nothing until the network came back and the stale socket was destroyed.
     *
     * Acquired inline rather than through a class of its own: [RadioHold] is already reference-
     * counting-free, so a second acquire is harmless, and the only discipline left is giving it
     * back - which is one line in [onDestroy] and is why there is nothing here worth a test.
     */
    private val radio by lazy { radioHoldOf(this) }

    /** Whether the handset actually handed that one over either. */
    private var heldRadio = false

    /** How long the loop was away between its last two runs. */
    private val gap = StandbyGap()

    /** And the worst of all of it, which is what a person reading this later needs. */
    private val worst = StandbyWorst()

    /**
     * Why the line last went down, kept after it comes back up.
     *
     * The name of the exception is the discriminator nothing else here carries: a network that
     * went away and a peer that reset the connection are different faults in different places,
     * and both read as "not connected" everywhere else on this screen.
     */
    private var lastTrouble: String? = null

    /** What it last answered, because the notification is written from inside that run. */
    private var lastGap = 0L

    /**
     * When this instance started standing by.
     *
     * A field on the service object, so it resets when the process does - which is the point.
     * A handset whose ROM kills this service and lets START_STICKY bring it back looks, from
     * every other number here, exactly like one that simply stopped running.
     */
    private val standingSince = SystemClock.elapsedRealtime()

    /** When this handset last told the host it was still there. */
    private var saidHereAt = 0L

    /** What did not go out, written once per reason. */
    private val complaints = Complaints { events.write(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        standBy()
        // Restarted if the system kills it, because what it is is a handset saying "I am here".
        return START_STICKY
    }

    /**
     * Opens the line, or leaves the one that is already open alone.
     *
     * Rebuilt only when what it would announce has changed, and that is not tidiness: everything
     * this handset tells a host about itself is said once, on connecting - its name, and which
     * correction it carries. Coming back from a calibration with a fresh measurement is exactly a
     * change of that, and a line kept open through it would leave the host reading last week's.
     */
    private fun standBy() {
        // Asked for with the microphone in it, because a room round records this handset and a
        // foreground service may only open one under a type that says so. Two reasons it can be
        // refused, and both end in a round this handset cannot join rather than in a crash: the
        // permission is not granted, and Android will not let a while-in-use type be taken by a
        // service the system restarted into the background. Which one happened is written down,
        // because from the host the two look the same.
        val micWanted = micGranted()
        val took = runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    if (micWanted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        }.isSuccess
        // Media playback on its own is never refused; the while-in-use half is, to a service the
        // system restarted into the background. Falling back keeps the line up either way.
        if (!took) startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        // Only when it changes. This runs on every start command, and a timeline that repeats one
        // fact every few seconds is a timeline nobody can find anything in.
        val typed = micWanted && took
        if (typed != micTyped) {
            micTyped = typed
            events.write(
                when {
                    typed -> "standby holds the microphone type, so it can join a round"
                    !micWanted -> "standby has no microphone permission, so it cannot join a round"
                    else -> "standby could not take the microphone type, so it cannot join a round"
                }
            )
        }
        // Before the line rather than after: a line dialled by a handset that is about to
        // suspend is a line that dies with nobody on either end writing down why. Which arm ran
        // goes in the log, on the same terms as the session side - a hold a vendor build refused
        // would otherwise read as evidence that sleep was never the problem.
        awake.take { held ->
            heldAwake = held
            events.write(if (held) "standby held awake" else "standby not held awake")
        }
        if (!heldRadio) {
            heldRadio = radio?.let { runCatching { it.acquire() }.isSuccess } == true
            events.write(if (heldRadio) "standby held the radio" else "standby not holding the radio")
        }
        // No longer a reason to stop. A handset with nothing to dial is the ordinary way this
        // starts now: somebody picks 当从机 on a phone whose host has not been switched on yet,
        // and finding it is this service's work - see [lookForAHost]. Stopping here left that
        // phone doing nothing at all until a person picked it up again and scanned something.
        dialIfChanged()
        // Started here rather than inside the dial. The dial is now also reached from inside this
        // very runnable, and posting it from there would leave two copies of it running, then
        // four.
        handler.removeCallbacks(tellIfMoved)
        handler.post(tellIfMoved)
    }

    /**
     * Opens the line, or opens it again because what it announces has changed.
     *
     * Checked on every tick rather than only when something starts this service. Everything in an
     * announcement is read off disk, and one of the things on disk is the correction this handset
     * carries - which is written by a calibration that has just finished. The old comment on
     * [RoomCommandClient.carrying] said that coming back from the calibration screen builds a new
     * client, and it was true only because of which screen happened to be resumed next: leave that
     * screen some other way and the host went on drawing 未校准 beside a handset that had just been
     * measured, with nothing anywhere to say the number had arrived.
     */
    private fun dialIfChanged() {
        val wanted = announcement() ?: return
        if (line != null && wanted == dialled) return
        close()
        dialled = wanted
        events.write("standby dialling ${wanted.address}:$COMMAND_PORT")
        line = RoomCommandClient(
            wanted.address,
            COMMAND_PORT,
            wanted.selfId,
            wanted.carrying,
            wanted.approximately,
            wanted.called,
            { handsetVolume.read(capturing = false).let { VolumeSaid(it.index, it.max, it.stream) } },
            ::exempt
        ) { order ->
            // On to the main thread: this arrives on the socket thread, and everything it leads to
            // is either a service being asked for or an activity being started.
            handler.post { obey(order) }
        }.also { dialling ->
            // The notification carries this handset's colour, and a notification already on a
            // lock screen does not redraw itself: without this the colour arrives while nobody is
            // looking and the phone stays grey until something else happens to rebuild it.
            dialling.onPlaces = {
                handler.post {
                    if (line === dialling) runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification())
                    }
                }
            }
            dialling.start()
        }
        ACTIVE = this
    }

    /**
     * Looks for a host to point at, for as long as this handset has none.
     *
     * A search rather than one attempt, and that is the point of it living here: the host is very
     * often switched on minutes after the phone that will follow it was set down, and this service
     * is the only thing still running by then.
     *
     * A handset that has a host is left alone while the line to it is up: a scanned code, or a host
     * found on a previous evening, is somebody having said which handset they meant, and no amount
     * of answering on a network beats that - see [HostSearch].
     *
     * Once that line has been down for [HostSearch.STALE_AFTER_MILLIS] it looks anyway, and that
     * is [HostSearch.lookAgain] rather than a fresh search - it is still that handset's host, and
     * the question is only where it is now. What used to happen instead was nothing at all, for
     * ever: the stored address is the one thing in the file that expires, and a host moving onto
     * a hotspot or onto another network left every sink dialling somewhere nobody was, with the
     * only way out being somebody finding 忘记主机 by hand. Reported 2026-09-19.
     *
     * Off the ticker's thread, because one look holds still for the whole discovery window and the
     * ticker is what this handset says it is alive with.
     */
    private fun lookForAHost() {
        val now = SystemClock.elapsedRealtime()
        // On every tick and not only when a look is due. A line that comes back on its own is the
        // ordinary way this ends, and it has to clear the clock that would otherwise send this
        // handset looking for a host it is already talking to.
        if (carrying()) downSince = 0L else if (downSince == 0L) downSince = now
        if (searching) return
        val pointed = PairedHost(filesDir).read() != null
        val downFor = if (downSince == 0L) 0L else now - downSince
        if (pointed && downFor < HostSearch.STALE_AFTER_MILLIS) return
        if (lookedAt != 0L && now - lookedAt < HostSearch.GAP_MILLIS) return
        lookedAt = now
        // On the same cadence as the look and for the other half of the same fault. A look can
        // only fix a host that has moved; a line that is open and carrying nothing is fixed by
        // dialling again, and nothing else on either end ever closes one. See
        // [RoomCommandClient.dialAgain] - the case is a network changed and changed back, where
        // the address in the file is still right and the socket holding it open is dead.
        if (pointed) line?.dialAgain()
        searching = true
        Thread({
            val found = runCatching {
                if (pointed) HostSearch.lookAgain(this, filesDir, HostSearch.WINDOW_MILLIS)
                else HostSearch.lookOnce(this, filesDir, HostSearch.WINDOW_MILLIS)
            }
            searching = false
            // Written down whichever way it went, and the failures are the half worth keeping:
            // "nothing answered" and "something answered on an older build" send somebody to two
            // completely different places, and from this screen both are simply no host.
            found.onSuccess { events.write("standby looked for a host: ${it.why}") }
        }, "SoundMeshHostSearch").start()
    }

    /**
     * Whether the line is not only open but actually carrying.
     *
     * [RoomCommandClient.connected] is the socket's answer and it is the wrong question. A socket
     * whose far end walked out of the network stays open - writes wait in the kernel rather than
     * failing, and nothing arrives on it to fail either - so that flag reads true for as long as
     * this handset does not close it, which is for ever. The down clock hung off it, so the case
     * the clock exists for, a handset or its host changing network, was the one case it never
     * started in.
     *
     * [Complaints.missed] is what the other half already counts: every couple of seconds this handset says it
     * is still here, and the count is the number of those in a row that did not leave. Zero and
     * connected is a line something is moving on. See [sayHereIfDue] and [complain].
     */
    private fun carrying(): Boolean = line?.connected == true && complaints.missed == 0

    /** What this handset would say on connecting, read fresh because all of it can change. */
    private fun announcement(): StandbyAnnounce? {
        val host = PairedHost(filesDir).read() ?: return null
        val carrying = StoredCalibration(filesDir, host.hostId).read()?.micros
        // Only when there is no measurement, on the same terms as the home screen had it: the two
        // ask different things of whoever reads the host's screen.
        val approximately =
            if (carrying != null) null else StoredApproximateCalibration(filesDir, host.hostId).read()
        return StandbyAnnounce(
            host.address,
            HostIdentity(filesDir).current(),
            carrying,
            approximately,
            handsetName(this)
        )
    }

    /**
     * Does what the host asked.
     *
     * Each one is guarded by the state it would change, so a command that arrives twice - or about
     * something this handset is already doing - is nothing rather than a second session.
     */
    private fun obey(order: RoomOrder) {
        val away = !HomeActivity.inFront
        events.write(
            "told to ${order.command}" + (order.value?.let { " $it" } ?: "") +
                if (away) " with the screen away" else ""
        )
        // Answered before the guard below, because the guard would swallow exactly the message
        // this command exists to carry: it is not something else to do, it is about the round
        // already running. A handset that is not in a round has nothing to call off and says
        // nothing back - an excuse there would report a fault where there is none.
        if (order.command == RoomCommand.CALL_OFF) {
            if (MeasuringNow.busy) MeasuringNow.calledOff = true
            return
        }
        // A handset in the middle of a round does nothing else at all, whichever way it is
        // facing. It used to be unreachable then - the line lived on the home screen, which a
        // round leaves - and that accident was doing this job until now.
        if (MeasuringNow.busy) return excuse(RoomExcuse.BUSY)
        if (away && !canObeyWhileAway(order.command)) return excuse(RoomExcuse.ASLEEP)
        when (order.command) {
            RoomCommand.PLAY -> if (SessionService.ACTIVE == null) play()
            RoomCommand.STOP -> if (SessionService.ACTIVE != null) {
                startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP))
            }
            RoomCommand.MEASURE_ROOM -> goAndMeasure(room = true, called = "room")
            RoomCommand.MEASURE_OVERHEAD -> goAndMeasure(room = true, called = "overhead")
            RoomCommand.MEASURE_PAIR -> goAndMeasure(room = false, called = "pair")
            // Not guarded by anything: unlike the others this is a state and not an event, and a
            // handset told twice to be at sixty per cent is a handset at sixty per cent.
            RoomCommand.SET_VOLUME -> order.value?.let { applyVolume(it) }
            RoomCommand.RESTORE_VOLUME -> putVolumeBack()
            // Answered above, ahead of the busy guard. Named here as well so that it is spelled
            // out rather than defaulted, on the same terms as [canObeyWhileAway].
            RoomCommand.CALL_OFF -> Unit
        }
    }

    private fun play() {
        val host = PairedHost(filesDir).read() ?: return
        startForegroundService(
            Intent(this, SessionService::class.java)
                .setAction(SessionService.ACTION_START_SINK)
                .putExtra(SessionService.EXTRA_HOST_ADDRESS, host.address)
                .putExtra(SessionService.EXTRA_CHUNK_PORT, host.chunkPort)
                .putExtra(SessionService.EXTRA_PEER_ID, host.hostId)
        )
    }

    /**
     * Joins a room round here, with nothing of this handset's own screen in it.
     *
     * It used to start [PeerCalibrateActivity] and refuse outright when nobody was looking, because
     * an app in the background may not start an activity at all - so a phone lying face down
     * answered a round with ASLEEP, and somebody had to walk round the room waking handsets. What
     * a sink does in a round never needed a screen: hold a clock exchange open, play one chirp when
     * the plan says to while recording, hand back what it heard. [SinkRound] is that, and this runs
     * it on a thread of its own.
     *
     * [room] is the one thing that does change the run: a room round asks the host for a plan with
     * everybody's slot in it, a pair round asks for this handset's own. [called] changes nothing
     * and is written down rather than used - the overhead round differs only in what the handset
     * gathering the room does with the answer, and every sink measures the same way in both - so
     * the record says which of the three this handset joined.
     */
    private fun goAndMeasure(room: Boolean, called: String) {
        if (!micGranted()) return excuse(RoomExcuse.NO_MICROPHONE)
        // Said as the nearest true thing rather than invented: from the host what matters is that
        // this handset cannot open a microphone, and the log above says which of the two reasons.
        if (!micTyped) return excuse(RoomExcuse.NO_MICROPHONE)
        MeasuringNow.inBackground = true
        // Cleared as the round starts rather than when one ends, so a call-off that arrives as
        // the last round is finishing cannot end this one before it has begun.
        MeasuringNow.calledOff = false
        events.write("round-joining $called from the standing line")
        // Guarded here rather than inside: an uncaught throw on any thread takes the whole process
        // with it, and this one would take the standing line down with it.
        Thread({
            var heldForTheRound = false
            runCatching {
                hushWhateverIsPlaying(this, events)
                withRadioAwake(this, events, held = { heldForTheRound = it }) {
                    handsetSinkRound(
                        context = this,
                        request = SinkRoundRequest(room = room),
                        radioHeld = { heldForTheRound },
                        calledOff = { MeasuringNow.calledOff },
                        report = object : SinkRoundReport {
                            override fun say(line: RoundLine, untilLocalNanos: Long?) {
                                measuring = line.text(this@StandbyService)
                                handler.post { showTheRound() }
                            }
                        }
                    ).run()
                }
            }.onFailure { events.write("round-failed " + it.javaClass.simpleName + ": " + it.message) }
            MeasuringNow.inBackground = false
            measuring = null
            handler.post { showTheRound() }
        }, "SoundMeshStandbyRound").start()
    }


    private fun micGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Puts the round's own line in the notification this handset already has up. */
    private fun showTheRound() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    /**
     * Says no where somebody is standing, rather than letting a room wait for a phone.
     *
     * Its own connection rather than the standing line, because that is what an excuse is on this
     * channel - see [tellHostWhy] - and it costs nothing: the handset saying it was not going to
     * be doing anything anyway.
     */
    private fun excuse(why: RoomExcuse) {
        events.write("said no: ${why.name}")
        val host = PairedHost(filesDir).read() ?: return
        tellHostWhy(host.address, COMMAND_PORT, HostIdentity(filesDir).current(), why)
    }

    /**
     * Sets this handset's own volume and says up the line what it actually came to.
     *
     * Read back rather than echoed: setStreamVolume has been seen on these handsets to take a
     * value and move nothing, and under do-not-disturb it throws instead.
     *
     * Never capturing. That mode belongs to a host, and a handset running this is a sink.
     */
    private fun applyVolume(percent: Int) {
        val now = handsetVolume.set(percent, capturing = false)
        events.write("volume set to $percent%: ${now.index}/${now.max} on ${now.stream}")
        sayVolume(now)
    }

    private fun putVolumeBack() {
        handsetVolume.restore()
        val now = handsetVolume.read(capturing = false)
        events.write("volume put back: ${now.index}/${now.max} on ${now.stream}")
        sayVolume(now)
    }

    /**
     * Written down only when it actually went out.
     *
     * Written down regardless, one failed write would be the last thing this handset ever said
     * about its volume, because [sayVolumeIfMoved] would then see it agreeing with itself forever.
     */
    private fun sayVolume(now: VolumeReading) {
        val landed = line?.sayVolume(now.index, now.max, now.stream) == true
        // The failing case is the one worth having: a report that never left looks exactly like
        // one that arrived unchanged.
        if (!landed) complain("volume not said") else said("volume")
        saidVolume = now.takeIf { landed }
    }

    /** Writes down that something did not go out - see [Complaints]. */
    private fun complain(what: String) {
        val why = line?.lastRefusal ?: "there is no line at all"
        lastTrouble = why
        complaints.complain(what, why)
    }

    private fun said(what: String) = complaints.said(what)

    /**
     * Tells the host when this handset's volume moved without the host asking, which is the keys.
     *
     * Pressing them on one phone must not move anybody else's - but a host whose list did not
     * notice would be showing a room that agrees when it does not, which is the one thing that
     * list exists to refuse.
     */
    private fun sayVolumeIfMoved() {
        val now = handsetVolume.read(capturing = false)
        if (now == saidVolume) return
        sayVolume(now)
    }

    /**
     * Tells the host when this handset's battery exemption changes.
     *
     * Polled on the same loop as the volume, and for a stronger reason: the only way this reading
     * ever changes is that somebody was told to go and change it. A warning on the host's screen
     * that still names this handset after it has been allowed is a warning nobody reads twice, and
     * there is no callback for this switch - it can only be looked at.
     */
    private fun sayPowerIfChanged() {
        val now = exempt()
        if (now == saidExempt) return
        if (line?.sayPower(now) == true) saidExempt = now
    }

    /**
     * Tells the host this handset is still here, every so often, whether or not anything changed.
     *
     * The only thing this handset says when nothing has happened, and the reason it has to exist:
     * holding a socket open says nothing. A phone that walks out of the network leaves this one
     * ESTABLISHED for as long as the kernel keeps retransmitting, so the host's count of who is
     * standing by was really a count of who had not yet failed a write - and on a screen that
     * number is read as "these phones will follow when I press play".
     *
     * The clock is checked rather than a counter kept, because the loop it hangs off also carries
     * the volume poll and is not owed a fixed cadence.
     */
    private fun sayHereIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - saidHereAt < SAY_HERE_EVERY_MILLIS) return
        if (line?.sayHere() == true) {
            saidHereAt = now
            said("still here")
        } else {
            complain("still-here not said")
        }
    }

    /**
     * Which colour this handset holds in the room it is standing in, or null before it is told.
     *
     * Read out of the standing line rather than kept, so there is one copy of an answer only the
     * host can give. Null covers three cases that all draw the same way - no line, a host too old
     * to send a table, and a table that has not arrived yet - and all three are "no colour", which
     * is a thing the drawing already knows how to be.
     */
    fun place(): Int? = dialled?.selfId?.let { line?.places?.get(it) }

    /**
     * Puts the state of the line on the notification, which is the only place a person who is not
     * holding a cable can read it.
     *
     * Guarded: a notification that will not post is not a reason to stop standing by, and this
     * runs on the same tick as the thing that actually matters.
     */
    private fun showTheLineIfItChanged() {
        if (!notice.shouldPost(connected, SystemClock.elapsedRealtime())) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.standby_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // The session's answer where there is a session, and the standing line's where there is
        // not. Until 2026-09-18 only the first existed, so standing by - which is where a room
        // spends nearly all of its time - drew the grey fallback, and four handsets waiting to be
        // told to play were four identical grey notifications.
        val place = SessionService.ACTIVE?.badgePlace() ?: place()
        val colour = if (place != null) BadgePalette.colourOf(place, ComposeColor.Gray).toArgb() else AndroidColor.GRAY
        val titleRes = if (connected) R.string.standby_lock_title else R.string.standby_lock_title_down
        val builder = Notification.Builder(this, CHANNEL_ID)
            // The app's own mark. It was a stock Android speaker until 2026-09-19, which in a
            // shade full of other apps' icons is the one thing about this notification that does
            // not say which app it came from.
            .setSmallIcon(R.drawable.ic_notification)
            // The handset's own colour, which on a lock screen is a block of colour beside the
            // text - readable from across a room, which is where these phones are.
            .setColor(colour)
            .setLargeIcon(dot(colour))
            // A round takes the line over while it runs. Somebody holding this phone has a right
            // to know why it just went quiet and started chirping, and this notification is the
            // only surface it has - the round has no screen any more.
            .setContentTitle(
                if (measuring != null) getString(R.string.standby_measuring) else getString(titleRes, badgeWord(place))
            )
            .setContentText(
                measuring ?: getString(R.string.standby_lock_text, handsetVolume.read(capturing = false).percent)
            )
            .setOngoing(true)
        // Only for somebody who asked for the internals. What is in here is four numbers and
        // three locks, each of which rules something out for whoever is debugging a room, and
        // none of which means anything to the person whose phone this is - they pulled the
        // notification down to see whether it was following the host, and got a paragraph about
        // wake locks and battery exemptions. Reported 2026-09-19.
        //
        // Hidden rather than shortened. The evening these are wanted, not one of them can be
        // missing, and a version of the line with the boring half taken out is exactly the
        // version that turns out to be missing the number that mattered.
        if (wantsDetails(filesDir)) {
            builder.setStyle(Notification.BigTextStyle().bigText(readings()))
        }
        // Colorized only where the colour is real: a band drawn from the grey fallback would read
        // to somebody who has never seen this handset any other way as its actual colour, which is
        // worse than a plain notification.
        if (place != null) builder.setColorized(true)
        return builder.build()
    }

    /**
     * The line of readings behind the diagnostics switch, built only when somebody is shown it.
     *
     * Four numbers rather than a word, and each one rules something out. The count says whether
     * this handset is trying and failing or not trying at all. The gap says whether the loop was
     * away, and for how long. The standing time says whether this process is the one that started,
     * or one the ROM restarted underneath it. The hold says whether the arm under test ever ran.
     * On 2026-09-14 all four were a single fixed string.
     */
    private fun readings(): String {
        val locks = getString(if (heldAwake) R.string.standby_awake_held else R.string.standby_awake_refused) +
            "、" + getString(if (heldRadio) R.string.standby_radio_held else R.string.standby_radio_refused) +
            "、" + getString(if (exempt()) R.string.standby_exempt else R.string.standby_not_exempt)
        val trouble = lastTrouble?.let { getString(R.string.standby_trouble, it) }
            ?: getString(R.string.standby_no_trouble)
        val standing = (SystemClock.elapsedRealtime() - standingSince) / 1000L
        return if (connected) getString(
            R.string.standby_notification,
            worst.longestGapMillis / 1000L,
            worst.longestAwayMillis / 1000L,
            standing,
            locks,
            trouble
        )
        else getString(
            R.string.standby_notification_down,
            complaints.missed,
            worst.longestGapMillis / 1000L,
            worst.longestAwayMillis / 1000L,
            standing,
            locks,
            trouble
        )
    }

    /**
     * The 128x128 solid-colour dot behind [notification]'s large icon.
     *
     * A drawn bitmap rather than a vector resource: the colour is known only at the moment the
     * notification is built, and a circle this small has nothing else worth drawing in it.
     */
    private fun dot(argb: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(DOT_DIAMETER_PX, DOT_DIAMETER_PX, Bitmap.Config.ARGB_8888)
        val radius = DOT_DIAMETER_PX / 2f
        Canvas(bitmap).drawCircle(radius, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb })
        return bitmap
    }

    /**
     * The same identity [badgeWords] draws on a Compose screen, said in words here instead - this
     * runs from a service, which has no composition to draw one into.
     */
    private fun badgeWord(place: Int?): String {
        val number = PeerBadge.numberOf(HostIdentity(filesDir).current())
        val name = BadgePalette.nameOf(place) ?: return getString(R.string.badge_number_only, number)
        return getString(R.string.badge_in_words, number, getString(name))
    }

    /**
     * Whether this handset is on WiFi at all, asked once a second.
     *
     * WiFi rather than any network: everything this app does is on the local link, so a handset
     * that fell back to mobile data is a handset that cannot reach the room, however connected it
     * looks. Best effort - a ROM that answers oddly should not take the standing line down.
     */
    private fun onWiFi(): Boolean = runCatching {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    /**
     * Whether this handset exempts this app from its battery rules.
     *
     * Read rather than asked for. On 2026-09-14 a handset held both locks, kept its process alive
     * for four minutes, never lost WiFi - and still stopped running this loop for a hundred and
     * nineteen seconds. Neither lock can beat a ROM that has decided to freeze the process or to
     * ignore the lock, and this is the one bit that says whether it has decided that.
     */
    private fun exempt(): Boolean = runCatching {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    private fun close() {
        line?.close()
        line = null
        saidVolume = null
        // Cleared with the socket, not kept: the next connection says it on the way in, and a
        // remembered value would make that frame look like a repeat and hold it back.
        saidExempt = null
    }

    /**
     * Swiping the app off the recents list stops standing by.
     *
     * This is the service the START_STICKY above is for, and the two answer different questions.
     * Sticky is about the system killing a handset that still wants to follow the host; a removed
     * task is a person saying they are done - and a handset that kept standing by through it would
     * hold the microphone, hold the CPU, and start playing the next time somebody else pressed
     * play. stopSelf rather than a flag, because an explicit stop is also what keeps sticky from
     * bringing it back.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tellIfMoved)
        close()
        dialled = null
        if (ACTIVE === this) ACTIVE = null
        awake.give()
        if (heldRadio) {
            heldRadio = false
            runCatching { radio?.release() }
        }
        events.write("standby stopped")
        super.onDestroy()
    }

    companion object {
        /**
         * The one running instance, for a screen that wants to say whether the line is up.
         *
         * The same shape as [SessionService.ACTIVE] and for the same reason: what a screen wants is
         * one boolean about something that is not its to own, and binding for that is a lifecycle
         * apiece for both ends.
         */
        @Volatile
        var ACTIVE: StandbyService? = null
            private set

        const val ACTION_STOP = "com.soundmesh.product.STANDBY_STOP"


        private const val CHANNEL_ID = "soundmesh-standby"
        private const val NOTIFICATION_ID = 4
        private const val TELL_EVERY_MILLIS = 1_000L

        /**
         * How often this handset says it is still there. Four of these fit in the window the host
         * waits before letting go - see RoomCommandServer.GONE_QUIET_MILLIS.
         */
        private const val SAY_HERE_EVERY_MILLIS = 2_000L

        /** Side length of [dot]'s bitmap, in pixels. */
        private const val DOT_DIAMETER_PX = 128
    }

    /** True while there is a socket to the host actually open, which is what standing by means. */
    val connected: Boolean get() = line?.connected == true
}

/**
 * Everything this handset says about itself on connecting, which is said once and never repeated.
 *
 * A value rather than six fields compared by hand, so that "has any of this changed" is one
 * question. Every part of it can change while the line is open - a rescan moves the address, a
 * calibration replaces the correction, a system rename changes the name - and each of them leaves
 * the host reading something that is no longer true.
 */
/**
 * Decides when the standing-by notification has to be written again.
 *
 * Split out of the service so the one thing that can go wrong here is testable: at one tick a
 * second, "keep it up to date" and "rewrite it every second" are one line apart.
 *
 * It exists at all because the notification it now drives used to be a fixed sentence - it said
 * the handset was standing by from the moment the service started until it died, whether or not
 * there was a line. On 2026-09-14 that sentence was read as evidence during a hunt for why the
 * host had let go of a handset, by a person standing in front of the phone that was saying it.
 * A piece of screen that asserts a state has to read that state.
 */
/**
 * How long the standing-by loop was away between one run and the next.
 *
 * Fed [SystemClock.elapsedRealtime], which counts through suspend - unlike the clock the loop
 * schedules itself on, which does not, and that difference is the whole measurement: a handset
 * whose SoC suspended reports the length of the suspend on the first run after it wakes.
 *
 * The first run answers nothing rather than the age of the handset. Measured against zero it
 * would report hours on a phone that had been up for hours, which is the same lie the fixed
 * notification told - a number that looks like a reading and is not one.
 */
/**
 * The worst this handset has been since it started standing by.
 *
 * Kept rather than shown live, and that is the whole point of the class. On 2026-09-14 a handset
 * was woken to be read and its line came back inside a second, so every live number on it said
 * the room was fine before the shade had finished opening - the act of taking the reading was
 * destroying the reading. A high-water mark survives the recovery, and can be read at leisure.
 */
internal class StandbyWorst {
    var longestGapMillis = 0L
        private set

    var longestAwayMillis = 0L
        private set

    /** Told how long the loop was away between its last two runs. */
    fun ran(gapMillis: Long) {
        if (gapMillis > longestGapMillis) longestGapMillis = gapMillis
    }

    /**
     * Told whether there is a network, every time the loop runs.
     *
     * An outage that is still going counts from where it started, rather than being written down
     * when it ends: a handset whose network never comes back is exactly the one somebody is
     * standing in front of, and a number that stays at nought until the fault is over is nought
     * for precisely as long as it is needed.
     */
    fun network(present: Boolean, now: Long) {
        // Closed out on the way back up as well as counted on the way down: the last sample
        // taken while away is a second before the network returned, so an outage measured only
        // from those is short by however long it took anybody to notice.
        val from = awayFrom ?: if (present) null else now.also { awayFrom = it }
        if (from != null && now - from > longestAwayMillis) longestAwayMillis = now - from
        if (present) awayFrom = null
    }

    private var awayFrom: Long? = null
}

internal class StandbyGap {
    private var ranAt: Long? = null

    fun since(now: Long): Long {
        val before = ranAt
        ranAt = now
        return if (before == null) 0L else now - before
    }
}

internal class StandbyNotice(private val refreshMillis: Long = REFRESH_MILLIS) {
    private var shownUp: Boolean? = null
    private var shownAt = 0L

    fun shouldPost(up: Boolean, now: Long): Boolean {
        if (shownUp != up) {
            shownUp = up
            shownAt = now
            return true
        }
        // A line that is up says one thing and goes on saying it. A line that is down carries a
        // count of what did not go out, and that count is the whole value of the line: it says
        // whether the loop behind it is running at all.
        if (up || now - shownAt < refreshMillis) return false
        shownAt = now
        return true
    }

    private companion object {
        const val REFRESH_MILLIS = 10_000L
    }
}

internal data class StandbyAnnounce(
    val address: String,
    val selfId: String,
    val carrying: Long?,
    val approximately: Long?,
    val called: String?
)

/**
 * Whether a command can be obeyed by a handset nobody is looking at.
 *
 * The line between the two halves is one thing and one thing only: whether obeying means putting
 * something on this handset's own screen. Nothing on the list does any more - measuring was the
 * one that did, and it stopped when the sink half moved into this service - so every answer here
 * is currently true. The question is still asked per command rather than defaulted, which is the
 * point below.
 *
 * Spelled out per command rather than defaulted, so that the next command added to the channel has
 * to answer this question instead of inheriting an answer that happens to be wrong for it.
 */
internal fun canObeyWhileAway(command: RoomCommand): Boolean = when (command) {
    RoomCommand.PLAY,
    RoomCommand.STOP,
    RoomCommand.SET_VOLUME,
    RoomCommand.RESTORE_VOLUME,
    // Both were false until 09-15, and that was the whole reason a phone in somebody's pocket
    // could not join a round: measuring drove a screen, and an app in the background may not start
    // one. SinkRound took the screen out of it, so the answer changed rather than the rule.
    RoomCommand.MEASURE_ROOM,
    RoomCommand.MEASURE_OVERHEAD,
    // Same answer and the same reason: what a sink does in any round is hold a socket open, play
    // one chirp and hand back what it heard, none of which is a screen.
    RoomCommand.MEASURE_PAIR,
    // Obeying is setting a flag a round already under way reads, and a round that needs no screen
    // to start needs none to stop either.
    RoomCommand.CALL_OFF -> true
}
