package com.soundmesh.product

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.RoomOrder
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.COMMAND_PORT
import com.soundmesh.probe.sync.EventLog
import com.soundmesh.probe.sync.HandsetVolume
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.RoomCommandClient
import com.soundmesh.probe.sync.StoredApproximateCalibration
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.VolumeReading
import com.soundmesh.probe.sync.VolumeSaid
import com.soundmesh.probe.sync.handsetName
import com.soundmesh.probe.sync.tellHostWhy
import com.soundmesh.session.SessionService

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
    private val handler = Handler(Looper.getMainLooper())
    private val events: EventLog by lazy { EventLog(filesDir) }
    private val handsetVolume by lazy {
        HandsetVolume(getSystemService(AudioManager::class.java), filesDir)
    }

    private var line: RoomCommandClient? = null

    /** What [line] was dialled with, so a change of any of it is a line worth rebuilding. */
    private var dialled: StandbyAnnounce? = null

    /** The last volume this handset got up the line, so only a change is worth a frame. */
    private var saidVolume: VolumeReading? = null

    /**
     * The volume keys are what move a stream without anybody asking, and there is no broadcast for
     * it on the versions here. A second is fine: this is a number on somebody else's screen, not a
     * control loop.
     */
    private val tellIfMoved = object : Runnable {
        override fun run() {
            sayVolumeIfMoved()
            sayHereIfDue()
            showTheLineIfItChanged()
            handler.postDelayed(this, TELL_EVERY_MILLIS)
        }
    }

    /** Whether what the notification says is still true. */
    private val notice = StandbyNotice()

    /** When this handset last told the host it was still there. */
    private var saidHereAt = 0L

    /** The last thing written about something not going out, so it is written once and not again. */
    private var complained: String? = null

    /** How many did not go out since then, which is the other half of one line in the log. */
    private var missed = 0

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
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        val wanted = announcement() ?: return stopSelf()
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
            { handsetVolume.read(capturing = false).let { VolumeSaid(it.index, it.max, it.stream) } }
        ) { order ->
            // On to the main thread: this arrives on the socket thread, and everything it leads to
            // is either a service being asked for or an activity being started.
            handler.post { obey(order) }
        }.also { it.start() }
        ACTIVE = this
        handler.removeCallbacks(tellIfMoved)
        handler.post(tellIfMoved)
    }

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
        // A handset in the middle of a round does nothing else at all, whichever way it is
        // facing. It used to be unreachable then - the line lived on the home screen, which a
        // round leaves - and that accident was doing this job until now.
        if (MeasuringNow.onScreen) return excuse(RoomExcuse.BUSY)
        if (away && !canObeyWhileAway(order.command)) return excuse(RoomExcuse.ASLEEP)
        when (order.command) {
            RoomCommand.PLAY -> if (SessionService.ACTIVE == null) play()
            RoomCommand.STOP -> if (SessionService.ACTIVE != null) {
                startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP))
            }
            RoomCommand.MEASURE_ROOM -> goAndMeasure(overhead = false)
            RoomCommand.MEASURE_OVERHEAD -> goAndMeasure(overhead = true)
            // Not guarded by anything: unlike the others this is a state and not an event, and a
            // handset told twice to be at sixty per cent is a handset at sixty per cent.
            RoomCommand.SET_VOLUME -> order.value?.let { applyVolume(it) }
            RoomCommand.RESTORE_VOLUME -> putVolumeBack()
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
     * The one command that needs this handset's own screen, and the one that can refuse.
     *
     * The calibration drives a screen of its own, and an app in the background cannot start one -
     * so this is the boundary the whole service is drawn around. Said out loud to the host, where
     * somebody is standing, because a handset that quietly did not join is the thing that cost an
     * evening on 2026-09-13.
     */
    private fun goAndMeasure(overhead: Boolean) {
        startActivity(
            Intent(this, PeerCalibrateActivity::class.java)
                .putExtra("role", CalibrationRole.SINK.name)
                .putExtra("room", true)
                .putExtra("overhead", overhead)
                .putExtra("auto", true)
                // Which is what sends it back to the home screen when the round ends.
                .putExtra("sent", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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

    /**
     * Writes down that something did not go out, once per reason rather than once per attempt.
     *
     * Per attempt, this is a line a second for as long as the fault lasts - 1,600 of them in one
     * evening on 2026-09-14, all identical, hiding the handful of lines that said what was
     * happening around them. What a person reading this file needs is when it started, what it
     * says, and when it stopped.
     */
    private fun complain(what: String) {
        val why = line?.lastRefusal ?: "there is no line at all"
        missed++
        val said = "$what: $why"
        if (said == complained) return
        complained = said
        events.write(said)
    }

    /** And the other end of it, which is the line that says the fault is over. */
    private fun said(what: String) {
        if (complained == null) return
        events.write("$what said again, after $missed that did not go out")
        complained = null
        missed = 0
    }

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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (connected) getString(R.string.standby_notification)
                // The count, not just the word: it is what tells a person whether this handset is
                // trying and failing or not trying at all, and those are different faults.
                else getString(R.string.standby_notification_down, missed)
            )
            .setOngoing(true)
            .build()
    }

    private fun close() {
        line?.close()
        line = null
        saidVolume = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(tellIfMoved)
        close()
        dialled = null
        if (ACTIVE === this) ACTIVE = null
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
 * something on this handset's own screen. Playing, stopping and moving a volume do not, which is
 * why a room of phones lying face down is a room at all. Measuring does - it opens a screen and
 * drives it - and an app in the background is not allowed to start one.
 *
 * Spelled out per command rather than defaulted, so that the next command added to the channel has
 * to answer this question instead of inheriting an answer that happens to be wrong for it.
 */
internal fun canObeyWhileAway(command: RoomCommand): Boolean = when (command) {
    RoomCommand.PLAY,
    RoomCommand.STOP,
    RoomCommand.SET_VOLUME,
    RoomCommand.RESTORE_VOLUME -> true
    RoomCommand.MEASURE_ROOM,
    RoomCommand.MEASURE_OVERHEAD -> false
}
