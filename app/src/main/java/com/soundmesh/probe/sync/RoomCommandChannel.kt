package com.soundmesh.probe.sync

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
    private val clients = Collections.synchronizedList(ArrayList<Socket>())

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        // Bound on the caller's thread, for the reason ChunkServer.start states: a stop() landing
        // before an async bind completed would find the socket null and miss the close.
        //
        // Reusable because this end is opened and closed by somebody changing what their phone
        // is being, which they do several times a minute while trying things. Sockets accepted
        // on this port sit in TIME_WAIT for a while after they close, and without this the next
        // bind fails - so picking host, then sink, then host again would leave a host nobody
        // could stand by for, with nothing on screen saying so.
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
                        clients.add(socket)
                    }
                }
            }
        }.start()
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
    fun send(command: RoomCommand) {
        val frame = SpatialFrame.encode(RoomCommandCodec.encode(command))
        val told = synchronized(clients) { ArrayList(clients) }
        Thread({
            for (socket in told) {
                runCatching {
                    socket.getOutputStream().apply {
                        write(frame)
                        flush()
                    }
                }.onFailure {
                    clients.remove(socket)
                    runCatching { socket.close() }
                }
            }
        }, "SoundMeshCommandSend").start()
    }

    /**
     * How many handsets are standing by.
     *
     * On screen rather than only in a log, because it is the answer to the question somebody asks
     * a second after pressing the button: a phone that was not holding a socket is a phone that
     * did not hear, and without this the only way to find that out is that it never started.
     */
    fun standingBy(): Int = clients.size

    fun stop() {
        running = false
        runCatching { server?.close() }
        synchronized(clients) {
            for (socket in clients) runCatching { socket.close() }
            clients.clear()
        }
    }
}

/**
 * The sink end: holds one socket open to the host and does what comes down it.
 *
 * Reconnects for as long as it is open, because the host end comes and goes - it is bound while
 * somebody is being a host and closed while they are not, and a sink that gave up on the first
 * refused connection would need a person to press something, which is the entire thing this exists
 * to remove.
 */
class RoomCommandClient(
    private val hostAddress: String,
    private val port: Int,
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
        /** Long enough to cost nothing while nobody is hosting, short enough to feel like nothing. */
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
    fun send(command: RoomCommand) {
        server?.send(command)
    }

    @Synchronized
    fun standingBy(): Int = server?.standingBy() ?: 0
}
