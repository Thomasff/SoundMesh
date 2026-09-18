package com.soundmesh.probe.sync

import com.soundmesh.core.HostId
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomCommandCodec
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.RoomOrder
import com.soundmesh.core.RoomExcuseCodec
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** Next after the room's 45127. One socket per standing-by handset, and nothing on it per second. */
const val COMMAND_PORT = 45128

/**
 * What a standing handset said about the correction it carries for this host.
 *
 * Three states rather than two, because a build from before this said nothing at all, and
 * silence is its own answer: calling it uncalibrated would send somebody off to recalibrate a
 * handset that is already fine.
 */
enum class Carried { UNSAID, NOTHING, SOMETHING, APPROXIMATE }

/** What a standing handset says after its id and its correction: what to call it on a screen. */
private const val CALLED = "called "

/**
 * What a standing handset says its volume actually is, after being told to change it.
 *
 * Said rather than assumed, and this is the whole point of the message: setStreamVolume has been
 * seen on these handsets to take a value and move nothing, and under do-not-disturb it throws. A
 * host that showed what it asked for would show a room in agreement that is not.
 */
private const val VOLUME = "volume "

/** What one handset last said its own volume is. [percent] is what a screen shows. */
data class VolumeSaid(val index: Int, val max: Int, val stream: String) {
    val percent: Int get() = percentOf(index, max)
}

fun sayingVolume(index: Int, max: Int, stream: String): String = "$VOLUME$index $max $stream"

private fun volumeFrom(said: String): VolumeSaid? {
    if (!said.startsWith(VOLUME)) return null
    val fields = said.removePrefix(VOLUME).split(" ")
    if (fields.size != 3) return null
    val index = fields[0].toIntOrNull() ?: return null
    val max = fields[1].toIntOrNull() ?: return null
    return VolumeSaid(index, max, fields[2])
}

/**
 * What a standing handset says every couple of seconds to mean nothing has happened to it.
 *
 * The only thing on this channel that is said when nothing changed, and the reason it has to be:
 * holding the line open says nothing at all. A handset that walks out of the network leaves its
 * socket ESTABLISHED for as long as the kernel keeps retransmitting, and nothing is ever written
 * down it in between, so "how many are standing by" was really "how many sockets nothing has
 * failed to write to". Those are different numbers and a person reading the screen wants the
 * first one.
 *
 * A word of its own rather than repeating the volume, because the host has to be able to tell a
 * handset that has gone quiet from one whose build never learned to speak: those are in the room,
 * hand-distributed, and a handset judged by a signal it does not send reads as dropped while it
 * sits on somebody's home screen doing everything it is told.
 */
private const val HERE = "here"

/** The one thing a standing handset ever says after its name. Anything else is a later build's. */
private const val CARRYING = "carrying "
private const val NOTHING_CARRIED = "none"

/**
 * Whether that handset's own system exempts this app from its battery rules.
 *
 * Read on the handset and said up the line, because it is the one bit that says whether this room
 * is about to lose a phone: the evening of 2026-09-14 ended with a handset that was killed the
 * moment the app left the foreground, and the fix was a switch in that handset's settings. The
 * host already draws a state for it and already has a line of text for it; what it never had was
 * anybody saying the word.
 *
 * Said again whenever it changes, not only on connecting. Somebody told to go and allow it does
 * exactly that and comes back, and a warning that cannot clear itself is a warning nobody believes
 * the second time.
 */
private const val POWER = "power "
private const val EXEMPT = "exempt"
private const val NOT_EXEMPT = "not-exempt"

/** What a handset says about its own battery exemption. */
fun sayingPower(exempt: Boolean): String = POWER + if (exempt) EXEMPT else NOT_EXEMPT

/** Reads [sayingPower] back, or null when the phrase is somebody else's. */
internal fun exemptFrom(said: String): Boolean? =
    if (!said.startsWith(POWER)) null else said.removePrefix(POWER) == EXEMPT

/**
 * Said by a handset correcting off a room round rather than off a measurement of its own pair.
 *
 * A word rather than a flag beside the number, so a host from before this reads it as a phrase it
 * does not know and keeps that handset at UNSAID - which is the honest answer for a build that
 * cannot tell the two apart, and is the one state that sends nobody off to recalibrate anything.
 */
private const val APPROXIMATELY = "about "

private fun carriedFrom(said: String): Carried? {
    if (!said.startsWith(CARRYING)) return null
    val what = said.removePrefix(CARRYING)
    if (what == NOTHING_CARRIED) return Carried.NOTHING
    if (what.startsWith(APPROXIMATELY)) {
        return if (what.removePrefix(APPROXIMATELY).toLongOrNull() != null) Carried.APPROXIMATE
        else null
    }
    return if (what.toLongOrNull() != null) Carried.SOMETHING else null
}

/**
 * The host end of the standing channel: a socket per handset that is sitting on its home screen
 * waiting to be told something.
 *
 * The direction is the same as everywhere else in this project - sinks dial the host - and that is
 * not a style choice. A host that dialled its sinks would have to know their addresses, and the
 * only thing that ever learns an address here is the sink, off the pairing code it scanned. So the
 * standing-by handsets connect and then say nothing, and the socket they are holding open is the
 * whole mechanism.
 *
 * Nothing is remembered. [SpatialFieldServer] keeps the last rule so a handset joining late is
 * caught up, and doing that here would be a bug rather than a feature: a command is something that
 * happened at an instant, and a phone that walks into the room afterwards and starts playing
 * because the host said "play" ten minutes ago is a phone nobody told to do anything.
 */
class RoomCommandServer(
    private val port: Int,
    /** Injectable only so that a test can watch the window pass without waiting out its length. */
    private val quietAfterMillis: Long = GONE_QUIET_MILLIS
) {
    private class Standing(val socket: Socket, val peerId: String) {
        @Volatile var carrying: Carried = Carried.UNSAID

        /**
         * When this handset last said it was still there, or null if it never has.
         *
         * Null is not "a long time ago": it is a build that does not say it, and the only honest
         * thing to do with one of those is what every build before this did - count the socket.
         */
        @Volatile var heardAt: Long? = null
    }

    private val clients = Collections.synchronizedList(ArrayList<Standing>())

    /**
     * What to call each handset that has ever stood by here, kept past its socket.
     *
     * Kept rather than cleared with the connection, because every screen that names a handset
     * names one that has just left this channel: obeying means leaving the home screen, so a
     * handset is never standing by at the moment its result or its excuse arrives.
     */
    private val names = Collections.synchronizedMap(LinkedHashMap<String, String>())

    /** The last reason each handset gave for not measuring, newest per handset. */
    private val excuses = Collections.synchronizedMap(LinkedHashMap<String, RoomExcuse>())

    /**
     * Which handsets have said their own system does not exempt this app - see [sayingPower].
     *
     * Only the ones that said so, so a handset whose build never learned the word is absent rather
     * than accused: this reading drives a warning that names a phone, and naming the wrong phone is
     * worse than naming none.
     */
    private val notExempt = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * What each handset last said its own volume is.
     *
     * Kept per handset and replaced, not accumulated: it is a state and not an event, unlike
     * everything else on this channel. Kept past the socket for the same reason the names are.
     */
    private val volumes = Collections.synchronizedMap(LinkedHashMap<String, VolumeSaid>())

    /**
     * When each of those arrived.
     *
     * Apart from the reading itself, because a handset that says the same number twice is not a
     * handset that has gone quiet, and from one reading the two are the same picture. That
     * difference is what two rounds of guesswork on 2026-09-13 were spent on.
     */
    private val volumeAt = Collections.synchronizedMap(LinkedHashMap<String, Long>())

    /**
     * Told the moment an excuse arrives rather than left to be polled.
     *
     * The whole value of the message is that somebody can still act on it: a handset waiting on
     * its own permission dialog is fixable in the ten seconds before the gathering window closes,
     * and unfixable a minute later. A screen that only learns afterwards is a log.
     */
    @Volatile
    var onExcuse: ((String, RoomExcuse) -> Unit)? = null

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        // Bound on the caller's thread, for the reason ChunkServer.start states: a stop() landing
        // before an async bind completed would find the socket null and miss the close.
        //
        // Reusable because this end is opened and closed by somebody changing what their phone is
        // being, which they do several times a minute while trying things. Sockets accepted on
        // this port sit in TIME_WAIT for a while after they close, and without this the next bind
        // fails - so picking host, then sink, then host again would leave a host nobody could
        // stand by for, with nothing on screen saying so.
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(port))
        server = bound
        running = true
        Thread {
            runCatching {
                bound.use {
                    while (running) {
                        val socket = bound.accept()
                        socket.tcpNoDelay = true
                        // The name is read on the new thread, not here: a handset that connects
                        // and then says nothing would otherwise hold the accept loop for as long
                        // as it stayed connected, and nobody else could stand by behind it.
                        Thread({ hold(socket) }, "SoundMeshCommandHeld").start()
                    }
                }
            }
        }.start()
        // No sweep. There used to be one here, closing the socket of any handset that had not
        // said it was there for eight seconds - see [quietPeerIds] for why that was the bug and
        // not the fix. Quiet is now something this end reports, not something it acts on.
    }

    /**
     * Holds one handset's socket for as long as it is there, under the name it gave.
     *
     * Named, and that is the whole difference between counting handsets and counting sockets. A
     * handset that rescans a code, or that walks out of range and back, opens a second connection
     * - and the first one is still here, because nothing has been written to it and a socket
     * nobody writes to is a socket nobody notices die. Counting those, a host says three handsets
     * are standing by in a room of two, which is what a listener hit on 09-11 by scanning twice.
     *
     * [SpatialFieldServer] carries the same rule for the same reason and got there first. The new
     * connection wins: the old one is only still here because nothing has been sent down it.
     */
    private fun hold(socket: Socket) {
        val stream = runCatching { socket.getInputStream().buffered() }.getOrNull()
        val announced = runCatching {
            // Bounded, or a socket that connects and says nothing parks this thread for the life
            // of the process. Cleared afterwards: a named handset is expected to stay quiet.
            socket.soTimeout = ANNOUNCE_TIMEOUT_MILLIS
            val name = SpatialFrame.read(stream!!)
            socket.soTimeout = 0
            name
        }.getOrNull()
        // A handset that is not going to measure says so here and hangs up. It is not standing
        // by - it is on its way to doing nothing - so it is never added to the list, and the
        // count of who is holding the line stays a count of who is holding the line.
        val excuse = announced?.let { RoomExcuseCodec.decode(it) }
        if (excuse != null) {
            excuses[excuse.first] = excuse.second
            runCatching { onExcuse?.invoke(excuse.first, excuse.second) }
            runCatching { socket.close() }
            return
        }
        if (stream == null || !HostId.isValid(announced)) {
            runCatching { socket.close() }
            return
        }
        val standing = Standing(socket, announced!!)
        val replaced = synchronized(clients) {
            val stale = clients.filter { it.peerId == standing.peerId }
            clients.removeAll(stale)
            clients.add(standing)
            stale
        }
        // Outside the lock, and closed rather than dropped: the thread parked on that socket ends
        // when the socket does, and a held socket per departed handset is a leak with a name.
        for (old in replaced) {
            runCatching { old.socket.close() }
            left(old.peerId, "it opened a second line, and the newer one wins")
        }
        // Then parked on a read, which is what makes this a count rather than a guess at one:
        // the read ends the moment that handset closes its end, and this is the only place that
        // finds out without having something to send.
        //
        // What comes down it, when anything does, is that handset saying what correction it
        // carries for this host. That constant lives on the handset that applies it, so the host
        // cannot look it up - and a handset carrying none plays tens of milliseconds out while
        // every screen says the room is fine.
        runCatching {
            socket.use {
                while (running) {
                    val said = SpatialFrame.read(stream) ?: break
                    standing.carrying = carriedFrom(said) ?: standing.carrying
                    // Remembered past this socket on purpose. A handset that is measuring is not
                    // standing by - it left this channel to go and do what it was told - and a
                    // screen naming it then is exactly the screen that needs the name.
                    if (said.startsWith(CALLED)) {
                        StoredHandsetName.cleaned(said.removePrefix(CALLED))
                            ?.let { names[standing.peerId] = it }
                    }
                    exemptFrom(said)?.let {
                        if (it) notExempt.remove(standing.peerId) else notExempt.add(standing.peerId)
                    }
                    if (said == HERE) standing.heardAt = System.currentTimeMillis()
                    volumeFrom(said)?.let {
                        volumes[standing.peerId] = it
                        volumeAt[standing.peerId] = System.currentTimeMillis()
                        runCatching { onVolume?.invoke(standing.peerId, it) }
                    }
                }
            }
        }
        if (clients.remove(standing)) left(standing.peerId, "it closed its line")
    }

    /**
     * Tells everybody standing by, on a thread of its own.
     *
     * Not on the caller's thread, and this is the same trap [SpatialFieldServer] documents: a
     * handset that left the network without closing its socket does not fail a write, it blocks
     * one, measured at 26.9 seconds. This is called from the screen, so blocking the caller would
     * freeze the button that was just pressed for half a minute.
     *
     * A socket that will not take it is dropped rather than retried. What it is being told is
     * "now", and there is no version of now that is worth queueing.
     */
    /**
     * Says it, and answers how many handsets it was said to.
     *
     * The count is the open lines at this instant rather than a delivery receipt, and that is
     * the distinction worth having: a host that told nobody and a host that told three handsets
     * that then failed to arrive look identical from here otherwise, and on 09-13 that was
     * exactly the fork a listener was stuck at - four presses, no handset ever arriving, and no
     * way to tell which half of the room was at fault.
     */
    fun send(order: RoomOrder): Int {
        val frame = SpatialFrame.encode(RoomCommandCodec.encode(order))
        val told = synchronized(clients) { ArrayList(clients) }
        Thread({
            for (standing in told) {
                runCatching {
                    standing.socket.getOutputStream().apply {
                        write(frame)
                        flush()
                    }
                }.onFailure {
                    clients.remove(standing)
                    runCatching { standing.socket.close() }
                    left(standing.peerId, "a write to it failed: ${it.javaClass.simpleName}")
                }
            }
        }, "SoundMeshCommandSend").start()
        return told.size
    }

    /** The plain form, for the commands that carry no number. */
    fun send(command: RoomCommand): Int = send(RoomOrder(command))

    /**
     * Says it to one handset, and answers whether there was a line to say it down.
     *
     * The room is the default and this is the exception, on purpose: what a person wants almost
     * always is one number for everybody, and what they occasionally want is one phone quieter
     * because of where it is standing. A room told one at a time would be a room nobody could
     * get level again.
     */
    fun sendTo(peerId: String, order: RoomOrder): Boolean {
        val frame = SpatialFrame.encode(RoomCommandCodec.encode(order))
        val standing = synchronized(clients) { clients.firstOrNull { it.peerId == peerId } }
            ?: return false
        Thread({
            runCatching {
                standing.socket.getOutputStream().apply {
                    write(frame)
                    flush()
                }
            }.onFailure {
                clients.remove(standing)
                runCatching { standing.socket.close() }
                left(standing.peerId, "a write to it alone failed: ${it.javaClass.simpleName}")
            }
        }, "SoundMeshCommandSendOne").start()
        return true
    }

    /**
     * How many handsets are standing by.
     *
     * On screen rather than only in a log, because it is the answer to the question somebody asks
     * a second after pressing the button: a phone that was not holding the line is a phone that
     * did not hear, and without this the only way to find that out is that it never started.
     */
    fun standingBy(): Int = clients.size

    /**
     * Which handsets those are, for the screen that draws them rather than counts them.
     *
     * Between sessions this is the only roster there is: no audio is going anywhere and nobody is
     * asking this host for the time, so the two lists the drawing reads while something is playing
     * are both empty.
     */
    fun standingPeerIds(): List<String> = synchronized(clients) { clients.map { it.peerId } }

    /**
     * Which handsets have stopped saying they are there, **without** letting go of any of them.
     *
     * Until 2026-09-14 this closed their sockets, and that turned out to be the fault rather than
     * the cure. Quiet has two causes that look identical from this end:
     *
     * - the handset walked out of the network, or
     * - its screen went off and the whole SoC suspended.
     *
     * The second is the ordinary behaviour of an Android handset about a minute after the screen
     * goes off. A foreground service does not prevent it: it keeps a process from being killed,
     * it does not keep a CPU awake, and the handset's one-second loop simply stops running.
     * Measured that evening on an unplugged P30, off the handset's own notification: nought
     * attempts had failed to go out after a minute of the host showing it as dropped, so the loop
     * behind the heartbeat had not run once.
     *
     * **A sleeping handset is not a gone handset.** It is still reachable - a packet arriving on
     * an established socket wakes the device, which is what every persistent-connection app on
     * the platform relies on. So closing that socket was the single action that turned a handset
     * which would have followed the next command into one that could not, and it did it eight
     * seconds after the screen went off.
     *
     * **What this gives up, said plainly.** A handset that really did walk out of the network now
     * stays in [standingPeerIds] until a write to it fails, which for a vanished peer can take
     * minutes. That is the thing the heartbeat was added for, and it is deliberately handed back:
     * between sessions the honest report is "connected, and not saying anything", and the moment
     * that actually matters - can it be told to play - is answered by telling it.
     *
     * Takes [now] rather than reading the clock so that the window can be tested without waiting
     * out its length.
     */
    fun quietPeerIds(now: Long = System.currentTimeMillis()): List<String> = synchronized(clients) {
        clients.filter { standing ->
            // Null is not "a long time ago": it is a build that does not say it at all, and those
            // builds are in the room. See [Standing.heardAt].
            standing.heardAt?.let { now - it >= quietAfterMillis } == true
        }.map { it.peerId }
    }

    /** How many standing handsets said they carry no correction for this host. */
    fun uncalibrated(): Int = synchronized(clients) { clients.count { it.carrying == Carried.NOTHING } }

    /**
     * What each standing handset said, one entry each, for the screen that draws them as rows.
     *
     * The three counts beside this stay: they are what the checklist reads, and a count is the
     * right shape for "is anything wrong at all". This is the other question - which one - and a
     * count has never been able to answer it.
     */
    fun carrying(): Map<String, Carried> =
        synchronized(clients) { clients.associate { it.peerId to it.carrying } }

    /**
     * How many said they are correcting off a room round rather than off their own measurement.
     *
     * Counted apart from [uncalibrated] rather than added to it, because the two ask for
     * different things from the person reading the screen. One of them has to be fixed before
     * anybody presses play - it is tens of milliseconds and audible across a room. The other is
     * about a millisecond and can wait until somebody has a quiet minute.
     */
    fun approximate(): Int =
        synchronized(clients) { clients.count { it.carrying == Carried.APPROXIMATE } }

    /** How many said neither way, which today means a build older than this message. */
    fun unsaid(): Int = synchronized(clients) { clients.count { it.carrying == Carried.UNSAID } }

    /**
     * What to call [peerId] on a screen, or null if this handset has never said.
     *
     * Disambiguated here rather than at each screen, and only when it has to be: a name is
     * chosen by a person and two handsets in one room may well share one, so the fallback is the
     * half of the identity that cannot collide. Ugly exactly when it needs to be and not before.
     */
    fun nameOf(peerId: String): String? = synchronized(names) {
        val name = names[peerId] ?: return null
        val shared = names.count { it.value == name } > 1
        if (shared) "$name (${peerId.takeLast(SHORT_NAME_CHARACTERS)})" else name
    }

    /**
     * What to call the handsets that have said they are not exempt from power saving.
     *
     * Names rather than ids, because this ends up in a sentence somebody reads. A handset that said
     * the word but has never said what to call it is left out: there is nothing to put in the
     * sentence, and "3f2a is not exempt" is not a sentence anybody can act on.
     */
    fun notExemptNames(): List<String> =
        synchronized(notExempt) { notExempt.toList() }.mapNotNull { nameOf(it) }

    /** What each handset last said about why it is not measuring. */
    fun excuses(): Map<String, RoomExcuse> = synchronized(excuses) { LinkedHashMap(excuses) }

    /**
     * What each handset **that is standing by** last said its own volume is, newest per handset.
     *
     * Kept past the socket in the map and filtered here, rather than forgotten when a handset
     * goes: a handset that comes straight back - which is what every reconnection is, and what
     * a second line replacing a first is - would otherwise lose its control for as long as it
     * took to say its volume again, and that gap is on the screen somebody is looking at.
     *
     * Filtered at all because a slider for a phone that is not in the room is the one thing on
     * that screen that still looks like it would do something. Reported on 2026-09-14: a dropped
     * handset was correctly drawn hollow and its slider sat there underneath.
     */
    fun volumes(): Map<String, VolumeSaid> = synchronized(clients) {
        val here = clients.map { it.peerId }.toSet()
        synchronized(volumes) { LinkedHashMap(volumes.filterKeys { it in here }) }
    }

    /** When that arrived, by the host's own clock, or null for a handset that has never said. */
    fun volumeSaidAt(peerId: String): Long? = volumeAt[peerId]

    /** Told the moment a handset says what its volume came to, so a screen can show it landing. */
    @Volatile
    var onVolume: ((String, VolumeSaid) -> Unit)? = null

    /**
     * Told when a handset stops standing by, with which of the three ways it went.
     *
     * The count on screen going down is the only trace these have otherwise, and a number cannot
     * say whether a phone was put in a pocket, was replaced by a second line from itself, or had
     * a write fail under it. Those want three different things from whoever is reading.
     */
    @Volatile
    var onLeft: ((String, String) -> Unit)? = null

    private fun left(peerId: String, why: String) {
        runCatching { onLeft?.invoke(peerId, why) }
    }

    /**
     * Drops them, which is what starting a round does.
     *
     * Kept per round rather than forever: an excuse is about one press of one button, and a
     * handset that could not measure an hour ago is not a fact about the round now starting.
     */
    fun forgetExcuses() = synchronized(excuses) { excuses.clear() }

    /**
     * Lets go of every standing handset without closing the door.
     *
     * Unlike [stop] the server keeps listening, so the handsets that come back are the ones that
     * can reach this one on the network the code now names. That is the whole point: after the
     * code is switched from the hotspot to the joined WiFi, a handset that joined over the hotspot
     * is still connected and still obeys, and a room half on each network is a room where the
     * phones that fall out do so one at a time, hours later, for no visible reason.
     */
    fun letEverybodyGo() {
        val told = synchronized(clients) {
            val here = ArrayList(clients)
            clients.clear()
            here
        }
        for (standing in told) {
            runCatching { standing.socket.close() }
            left(standing.peerId, "the code was switched to another network")
        }
    }

    fun stop() {
        running = false
        onExcuse = null
        runCatching { server?.close() }
        synchronized(clients) {
            for (standing in clients) runCatching { standing.socket.close() }
            clients.clear()
        }
    }

    internal companion object {
        /** Long enough for a slow link, short enough that a silent socket is not a parked thread. */
        const val ANNOUNCE_TIMEOUT_MILLIS = 5_000

        /** As many of the id as every screen and every log line has always printed. */
        const val SHORT_NAME_CHARACTERS = 4

        /**
         * How long a handset can go without saying it is there before it is called quiet.
         *
         * Four missed beats at the sink's cadence. It used to be how long before it was let go
         * of, and the name still carries that - kept rather than renamed, because every handset
         * in the room reads the cadence off it and the two ends have to agree about the number,
         * not about what it is called.
         *
         * **Quiet is no longer gone.** See [RoomCommandServer.quietPeerIds].
         */
        const val GONE_QUIET_MILLIS = 8_000L
    }
}

/**
 * The sink end: holds one socket open to the host and does what comes down it.
 *
 * Reconnects for as long as it is open, because the host end comes and goes - it is bound while
 * somebody is being a host and closed while they are not, and a sink that gave up on the first
 * refused connection would need a person to press something, which is the entire thing this
 * exists to remove.
 */
class RoomCommandClient(
    private val hostAddress: String,
    private val port: Int,
    /** This handset's own name, said first, so the host counts handsets and not sockets. */
    private val selfId: String,
    /**
     * The correction this handset carries for the host it is dialling, in microseconds, or null
     * if it carries none. Fixed for the life of this client: what changes it is a calibration
     * finishing, and the sink end watches for that and dials again - see
     * `StandbyService.dialIfChanged`. It used to say the same thing and be true only by accident,
     * because whether a new client was built turned on which screen happened to be resumed next.
     */
    private val carrying: Long?,
    /**
     * The same, when what this handset carries came off a room round instead of a measurement of
     * this pair. Only read when [carrying] is null, which is the only state it can exist in.
     */
    private val approximately: Long? = null,
    /**
     * What to call this handset on somebody else's screen. Null keeps this build silent about it,
     * which is what a host from before this reads anyway.
     */
    private val called: String? = null,
    /**
     * What this handset's volume is right now, read at the moment of connecting.
     *
     * Said on the way in rather than only after being told to change, because the host's table of
     * them is per connection at both ends: a host that has just started, or a handset that has
     * just come back from measuring, has no line for this handset at all - and the control for one
     * handset on its own is drawn from that line. So until the first room-wide change reached
     * everybody, the thing a person wanted for the phone standing next to a wall was simply not
     * on the screen.
     *
     * A lambda because a connection can be the fifth one this client has made and the answer will
     * have moved; null keeps this build silent, which is what an older host reads anyway.
     */
    private val volumeNow: (() -> VolumeSaid)? = null,
    /**
     * Whether this handset's own system exempts this app from its battery rules, read at the
     * moment of connecting. A lambda for the same reason [volumeNow] is one, and null keeps this
     * build silent - which is what an older host reads anyway.
     */
    private val exemptNow: (() -> Boolean)? = null,
    private val onCommand: (RoomOrder) -> Unit
) : AutoCloseable {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    /** True while a socket to the host is actually open, which is what "standing by" means. */
    @Volatile var connected = false
        private set

    /**
     * Why the last thing this handset tried to say did not go out, or null if it went.
     *
     * Held here rather than thrown because none of these is a reason to stop standing by: a line
     * that is not open right now comes back three seconds later. Held **at all** because the
     * caller is the only thing that can put it on a screen or in a log, and it has no other way
     * to tell three quite different faults apart.
     */
    @Volatile var lastRefusal: String? = null
        private set

    /**
     * Whatever this handset has been asked to say, and the thread that says it.
     *
     * Everything that asks is a screen or something a screen started, and Android refuses a socket
     * write made on the thread that draws one. See [ThingsToSay].
     */
    private val toSay = ThingsToSay("SoundMeshCommandSay") { sayNow(it) }

    /**
     * Held around every write, so the frames a connection opens with cannot be cut in half by one
     * that was queued a moment earlier. Nothing reads on this end, so there is nothing else in it.
     */
    private val writing = Any()

    fun start() {
        running = true
        toSay.start()
        Thread({ hold() }, "SoundMeshCommandHold").start()
    }

    private fun hold() {
        while (running) {
            runCatching {
                Socket().also { socket = it }.use { open ->
                    open.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MILLIS)
                    open.tcpNoDelay = true
                    // Under the same lock as everything else written here, so that a frame queued
                    // against the socket that just died cannot land in the middle of these.
                    synchronized(writing) {
                        open.getOutputStream().apply {
                            write(SpatialFrame.encode(selfId))
                            // A second frame rather than a longer first one: a host from before
                            // this validates the first frame as a name and would drop a handset
                            // that put anything else in it, while a frame it does not expect is
                            // read and discarded by the loop that is only there to notice the
                            // socket close.
                            write(SpatialFrame.encode(CARRYING + what()))
                            // A third frame on the same terms as the second: a reader that does
                            // not know it discards it, and the socket goes on being what it is for.
                            called?.let { write(SpatialFrame.encode(CALLED + it)) }
                            // A fourth on the same terms.
                            volumeNow?.invoke()?.let {
                                write(SpatialFrame.encode(sayingVolume(it.index, it.max, it.stream)))
                            }
                            // A fifth, still on those terms.
                            exemptNow?.let { write(SpatialFrame.encode(sayingPower(it()))) }
                            // And a sixth, which is the first of however many: the host counts a
                            // handset that says this and then stops, and leaves alone one that has
                            // never said it at all. Said here rather than waiting for the first
                            // tick so that a handset is never in the second group by accident.
                            write(SpatialFrame.encode(HERE))
                            flush()
                        }
                    }
                    connected = true
                    val stream = open.getInputStream()
                    while (running) {
                        val text = SpatialFrame.read(stream) ?: break
                        // Guarded: an unreadable command is one this build does not speak, and the
                        // socket is still worth holding for the next one it does.
                        runCatching { RoomCommandCodec.decode(text) }.getOrNull()?.let(onCommand)
                    }
                }
            }
            connected = false
            // What was waiting belonged to the socket that just died, and the next one opens by
            // saying who this handset is.
            toSay.forget()
            if (running) runCatching { Thread.sleep(RETRY_MILLIS) }
        }
    }

    /** What this handset says it carries: a measurement, a room round's guess, or nothing. */
    private fun what(): String = when {
        carrying != null -> carrying.toString()
        approximately != null -> APPROXIMATELY + approximately
        else -> NOTHING_CARRIED
    }

    /**
     * Says what this handset's volume actually is, and answers whether it went out.
     *
     * Asked from whatever thread just changed it, and said on [toSay]'s. Whatever thread just
     * changed it is the one that draws the screen - the volume keys are polled from a timer on it,
     * and being told to set a volume is answered on it - and Android throws rather than writing a
     * socket from there.
     *
     * Answered rather than swallowed, and that is the whole of it: the caller writes down what it
     * has said so as to stop repeating itself, so a write that never left has to be told apart
     * from one that did. Written down as said, it would be the last thing this handset ever
     * mentioned about its volume - and a line that is not open right now is the normal case, not
     * a fault. It comes back three seconds later.
     */
    fun sayVolume(index: Int, max: Int, stream: String): Boolean =
        say(sayingVolume(index, max, stream))

    /** Says whether this handset's own system exempts this app - see [sayingPower]. */
    fun sayPower(exempt: Boolean): Boolean = say(sayingPower(exempt))

    /**
     * Says this handset is still there, and answers whether it went out.
     *
     * Repeated on a cadence rather than said once, which is the whole point of it: a socket that
     * is never written to is a socket nobody notices die, at either end. Same shape and same
     * answer as [sayVolume] - a line that is not open right now is the normal case, not a fault.
     */
    fun sayHere(): Boolean = say(HERE)

    /**
     * Writes one frame up the line, and remembers **why** if it did not go.
     *
     * The reasons are different faults and until 2026-09-14 they shared one sentence on one
     * screen: no socket yet, a line that is down, a queue that has stopped emptying, and a write
     * that threw. A handset that had said nothing for eight seconds could be any of them, and a
     * log that says "no line to say it on" while the host is reading commands off that very
     * socket is not a clue, it is a wrong answer that costs a round of guessing.
     */
    private fun say(what: String): Boolean {
        if (socket == null) return refuse("there is no socket yet")
        if (!connected) return refuse("the line is down")
        // The fourth reason, and the one that says the line is up but nothing is moving on it:
        // a write to a handset that walked out of the network waits in the kernel rather than
        // failing, so what fills up is the queue in front of it.
        if (!toSay.say(what)) return refuse("the line has stopped moving")
        return true
    }

    /**
     * Puts one frame on the wire, on [toSay]'s thread and nobody else's.
     *
     * A write that throws takes the socket down with it rather than being counted and forgotten:
     * the hold loop is sitting in a read on that socket, and closing it is how it is told to go
     * round again.
     */
    private fun sayNow(what: String) {
        val open = socket ?: return
        runCatching {
            synchronized(writing) {
                open.getOutputStream().apply {
                    write(SpatialFrame.encode(what))
                    flush()
                }
            }
            lastRefusal = null
        }.onFailure {
            refuse("the write threw ${it.javaClass.simpleName}: ${it.message}")
            connected = false
            runCatching { open.close() }
        }
    }

    private fun refuse(why: String): Boolean {
        lastRefusal = why
        return false
    }

    override fun close() {
        running = false
        connected = false
        toSay.close()
        runCatching { socket?.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        /** Long enough to cost nothing while nobody hosts, short enough to feel like nothing. */
        const val RETRY_MILLIS = 3_000L
    }
}

/**
 * The one command server this process runs, held outside any screen.
 *
 * It has to outlive the home screen, and that is the whole reason this is an object rather than a
 * field. The host tells the room to go and measure from **inside** the calibration screen - after
 * its own plan server is bound, because [CalibrationPlanClient] opens a socket and throws if
 * nothing is listening rather than retrying. A server owned by the home screen is closed by the
 * time that instant arrives.
 */
object RoomCommands {
    private var server: RoomCommandServer? = null

    @Synchronized
    fun serve() {
        if (server != null) return
        server = runCatching { RoomCommandServer(COMMAND_PORT).also { it.start() } }.getOrNull()
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    @Synchronized
    fun send(order: RoomOrder): Int = server?.send(order) ?: 0

    @Synchronized
    fun send(command: RoomCommand): Int = send(RoomOrder(command))

    @Synchronized
    fun sendTo(peerId: String, order: RoomOrder): Boolean =
        server?.sendTo(peerId, order) ?: false

    @Synchronized
    fun standingBy(): Int = server?.standingBy() ?: 0

    @Synchronized
    fun uncalibrated(): Int = server?.uncalibrated() ?: 0

    @Synchronized
    fun carrying(): Map<String, Carried> = server?.carrying() ?: emptyMap()

    /** See [RoomCommandServer.letEverybodyGo]. Nothing to do where no server is up. */
    @Synchronized
    fun letEverybodyGo() = server?.letEverybodyGo() ?: Unit

    @Synchronized
    fun unsaid(): Int = server?.unsaid() ?: 0

    @Synchronized
    fun approximate(): Int = server?.approximate() ?: 0

    @Synchronized
    fun excuses(): Map<String, RoomExcuse> = server?.excuses() ?: emptyMap()

    @Synchronized
    fun nameOf(peerId: String): String? = server?.nameOf(peerId)

    @Synchronized
    fun notExemptNames(): List<String> = server?.notExemptNames() ?: emptyList()

    @Synchronized
    fun volumes(): Map<String, VolumeSaid> = server?.volumes() ?: emptyMap()

    /** Which handsets are standing by, for the drawing. See [RoomCommandServer.standingPeerIds]. */
    @Synchronized
    fun standingPeerIds(): List<String> = server?.standingPeerIds() ?: emptyList()

    /** Which of those have stopped saying so. Quiet, not gone - see [RoomCommandServer.quietPeerIds]. */
    @Synchronized
    fun quietPeerIds(): List<String> = server?.quietPeerIds() ?: emptyList()

    @Synchronized
    fun volumeSaidAt(peerId: String): Long? = server?.volumeSaidAt(peerId)

    /** Who to tell when a handset says what its volume came to, or null for nobody. */
    @Synchronized
    fun listenForVolumes(listener: ((String, VolumeSaid) -> Unit)?) {
        server?.onVolume = listener
    }

    /** Who to tell when a handset stops standing by, or null for nobody. */
    @Synchronized
    fun listenForDepartures(listener: ((String, String) -> Unit)?) {
        server?.onLeft = listener
    }

    @Synchronized
    fun forgetExcuses() {
        server?.forgetExcuses()
    }

    /**
     * Who to tell when a handset says why it is not measuring, or null for nobody.
     *
     * Set by the screen that is gathering a room and cleared when it stops, because that screen
     * is where somebody is standing while it matters.
     */
    @Synchronized
    fun listenForExcuses(listener: ((String, RoomExcuse) -> Unit)?) {
        server?.onExcuse = listener
    }
}

/**
 * Says why this handset is not going to measure, up the channel the host already holds open.
 *
 * On a thread of its own and swallowing everything, because every caller is a refusal: the run is
 * not happening either way, and a handset that crashed while apologising would be a worse bug
 * than the one being apologised for. Nothing is retried for the same reason the commands are not
 * queued - what is being said is about now.
 */
fun tellHostWhy(hostAddress: String, port: Int, selfId: String, excuse: RoomExcuse) {
    Thread({
        runCatching {
            Socket().use { open ->
                open.connect(InetSocketAddress(hostAddress, port), EXCUSE_TIMEOUT_MILLIS)
                open.tcpNoDelay = true
                open.getOutputStream().apply {
                    write(SpatialFrame.encode(RoomExcuseCodec.encode(selfId, excuse)))
                    flush()
                }
            }
        }
    }, "SoundMeshExcuse").start()
}

private const val EXCUSE_TIMEOUT_MILLIS = 3_000
