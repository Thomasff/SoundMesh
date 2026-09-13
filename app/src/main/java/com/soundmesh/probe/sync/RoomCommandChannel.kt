package com.soundmesh.probe.sync

import com.soundmesh.core.HostId
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomCommandCodec
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** Next after the room's 45127. One socket per standing-by handset, and nothing on it per second. */
const val COMMAND_PORT = 45128

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
    private class Standing(val socket: Socket, val peerId: String)

    private val clients = Collections.synchronizedList(ArrayList<Standing>())

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
        for (old in replaced) runCatching { old.socket.close() }
        // Then parked on a read that is never answered, which is what makes this a count rather
        // than a guess at one: the read ends the moment that handset closes its end, and this is
        // the only place that finds out without having something to send.
        runCatching {
            socket.use {
                while (running && stream.read() >= 0) Unit
            }
        }
        clients.remove(standing)
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
    fun send(command: RoomCommand): Int {
        val frame = SpatialFrame.encode(RoomCommandCodec.encode(command))
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
                }
            }
        }, "SoundMeshCommandSend").start()
        return told.size
    }

    /**
     * How many handsets are standing by.
     *
     * On screen rather than only in a log, because it is the answer to the question somebody asks
     * a second after pressing the button: a phone that was not holding the line is a phone that
     * did not hear, and without this the only way to find that out is that it never started.
     */
    fun standingBy(): Int = clients.size

    fun stop() {
        running = false
        runCatching { server?.close() }
        synchronized(clients) {
            for (standing in clients) runCatching { standing.socket.close() }
            clients.clear()
        }
    }

    private companion object {
        /** Long enough for a slow link, short enough that a silent socket is not a parked thread. */
        const val ANNOUNCE_TIMEOUT_MILLIS = 5_000
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
    private val onCommand: (RoomCommand) -> Unit
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
    fun send(command: RoomCommand): Int = server?.send(command) ?: 0

    @Synchronized
    fun standingBy(): Int = server?.standingBy() ?: 0
}
