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
private enum class Carried { UNSAID, NOTHING, SOMETHING, APPROXIMATE }

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

/** The one thing a standing handset ever says after its name. Anything else is a later build's. */
private const val CARRYING = "carrying "
private const val NOTHING_CARRIED = "none"

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
class RoomCommandServer(private val port: Int) {
    private class Standing(val socket: Socket, val peerId: String) {
        @Volatile var carrying: Carried = Carried.UNSAID
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
     * What each handset last said its own volume is.
     *
     * Kept per handset and replaced, not accumulated: it is a state and not an event, unlike
     * everything else on this channel. Kept past the socket for the same reason the names are.
     */
    private val volumes = Collections.synchronizedMap(LinkedHashMap<String, VolumeSaid>())

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
                    volumeFrom(said)?.let {
                        volumes[standing.peerId] = it
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

    /** How many standing handsets said they carry no correction for this host. */
    fun uncalibrated(): Int = synchronized(clients) { clients.count { it.carrying == Carried.NOTHING } }

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

    /** What each handset last said about why it is not measuring. */
    fun excuses(): Map<String, RoomExcuse> = synchronized(excuses) { LinkedHashMap(excuses) }

    /** What each handset last said its own volume is, newest per handset. */
    fun volumes(): Map<String, VolumeSaid> = synchronized(volumes) { LinkedHashMap(volumes) }

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

    fun stop() {
        running = false
        onExcuse = null
        runCatching { server?.close() }
        synchronized(clients) {
            for (standing in clients) runCatching { standing.socket.close() }
            clients.clear()
        }
    }

    private companion object {
        /** Long enough for a slow link, short enough that a silent socket is not a parked thread. */
        const val ANNOUNCE_TIMEOUT_MILLIS = 5_000

        /** As many of the id as every screen and every log line has always printed. */
        const val SHORT_NAME_CHARACTERS = 4
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
     * if it carries none. Read once per client rather than per connection: the calibration screen
     * is the only thing that changes it, and coming back from it builds a new client.
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
    private val onCommand: (RoomOrder) -> Unit
) : AutoCloseable {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    /** True while a socket to the host is actually open, which is what "standing by" means. */
    @Volatile var connected = false
        private set

    fun start() {
        running = true
        Thread({ hold() }, "SoundMeshCommandHold").start()
    }

    private fun hold() {
        while (running) {
            runCatching {
                Socket().also { socket = it }.use { open ->
                    open.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MILLIS)
                    open.tcpNoDelay = true
                    open.getOutputStream().apply {
                        write(SpatialFrame.encode(selfId))
                        // A second frame rather than a longer first one: a host from before this
                        // validates the first frame as a name and would drop a handset that put
                        // anything else in it, while a frame it does not expect is read and
                        // discarded by the loop that is only there to notice the socket close.
                        write(SpatialFrame.encode(CARRYING + what()))
                        // A third frame on the same terms as the second: a reader that does not
                        // know it discards it, and the socket goes on being what it is for.
                        called?.let { write(SpatialFrame.encode(CALLED + it)) }
                        // A fourth on the same terms, and the last one that is said unasked.
                        volumeNow?.invoke()?.let {
                            write(SpatialFrame.encode(sayingVolume(it.index, it.max, it.stream)))
                        }
                        flush()
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
     * Says what this handset's volume actually is, up the line the host already holds open.
     *
     * Written from whatever thread just changed it, which is safe because this end only ever
     * writes and the hold loop only ever reads. Swallowed: a handset whose line has gone is a
     * handset the host has already stopped counting.
     */
    fun sayVolume(index: Int, max: Int, stream: String) {
        runCatching {
            socket?.getOutputStream()?.apply {
                write(SpatialFrame.encode(sayingVolume(index, max, stream)))
                flush()
            }
        }
    }

    override fun close() {
        running = false
        connected = false
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
    fun unsaid(): Int = server?.unsaid() ?: 0

    @Synchronized
    fun approximate(): Int = server?.approximate() ?: 0

    @Synchronized
    fun excuses(): Map<String, RoomExcuse> = server?.excuses() ?: emptyMap()

    @Synchronized
    fun nameOf(peerId: String): String? = server?.nameOf(peerId)

    @Synchronized
    fun volumes(): Map<String, VolumeSaid> = server?.volumes() ?: emptyMap()

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
