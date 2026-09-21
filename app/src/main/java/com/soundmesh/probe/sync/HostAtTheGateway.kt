package com.soundmesh.probe.sync

import android.content.Context
import android.net.ConnectivityManager
import com.soundmesh.core.PairingCode
import com.soundmesh.core.PairingCodeCodec
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The host reached by asking the network itself, for the one network that cannot be listened to.
 *
 * Measured on 2026-09-22, three handsets, one of them serving its hotspot: **its own clients never
 * find it**. A sink on that hotspot searched eleven times over two minutes and was answered
 * nothing; the moment a second client took the role it was found on the first search. So mDNS on
 * this ROM is one-way across an access point the handset is itself providing - the handset holding
 * it reaches its clients, its clients reach each other, and nobody reaches it.
 *
 * Two faults came out of that one blindness and only one of them looks like a search: the other is
 * that "somebody else is already hosting here" stopped holding a second host back, because the
 * evidence for that sentence is the first host's record. Nothing here fixes the second one, and it
 * does not need its own fix - it is the same handset becoming findable.
 *
 * **What is missing is a name, not a route.** A client can always reach the handset serving it:
 * that handset is the default gateway, and the chunk stream has run over exactly that hop since
 * the first hotspot session. Only the asking was multicast. So this asks, unicast, and the answer
 * is a pairing code - the same bytes [ShowCodeActivity] puts on the screen, read by the same
 * [PairingCodeCodec], which is why nothing here is a new protocol and why a build too old to
 * answer simply does not, leaving the day it was written behind unchanged.
 *
 * Held for exactly as long as the record is, by [HostBeacon], because the two say the same thing.
 *
 * The identity is why this is not one line on top of [RoomCommands.stillServing]. That call
 * answers whether somebody is hosting and cannot answer who, and the stored host id is not
 * decoration: [com.soundmesh.product.SinkRound] refuses a calibration plan whose host id is not
 * the stored one, and every per-host calibration on this handset is filed under it.
 */
object HostAtTheGateway {
    /**
     * Answers this handset's pairing code on [port] until [stop], and again is the same as once.
     *
     * [chunkPort] is passed rather than read so that this file knows nothing about screens; it is
     * the port a sink will actually dial, which is the host's business to say.
     */
    @Synchronized
    fun answer(hostId: String, chunkPort: Int, port: Int = PORT) {
        if (server != null) return
        // Bound on the caller's thread. A stop() arriving before an asynchronous bind finished
        // would read a null server, miss the close, and leave the socket behind - the shape
        // ChunkServer.start settled on for the same reason.
        val bound = runCatching { ServerSocket(port) }.getOrNull() ?: return
        server = bound
        running = true
        accepting = Thread({
            runCatching {
                bound.use {
                    while (running) {
                        val socket = bound.accept()
                        runCatching {
                            socket.use {
                                // Off the accepted socket rather than off this handset's own list
                                // of addresses. A handset serving an access point holds several,
                                // and the asker has already proved which one reaches it.
                                val here = it.localAddress.hostAddress ?: return@use
                                it.getOutputStream().write(
                                    (PairingCodeCodec.encode(PairingCode(hostId, here, chunkPort)) + "\n")
                                        .toByteArray()
                                )
                            }
                        }
                    }
                }
            }
        }, "SoundMeshHostAtTheGateway").also { it.start() }
    }

    /** Gives the port back and waits for the thread that holds it to notice. */
    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        // close() hands the descriptor to whoever is sitting in accept, so the instant it returns
        // and the instant that thread is gone are two different instants. Kept because a socket
        // outliving its stop has cost this project a whole evening before, and kept honestly:
        // **nothing here tests it.** Removing this line on 2026-09-22 left every assertion in
        // HostAtTheGatewayTest green, including binding the same port again in the next statement,
        // which is the one thing it was supposed to make safe. It is insurance against a platform
        // that behaves differently from this JVM, not a line any judgement rests on.
        accepting?.let { runCatching { it.join(JOIN_TIMEOUT_MILLIS) } }
        accepting = null
    }

    /**
     * The pairing code of whatever is hosting at [address], or null for anything else at all.
     *
     * Null covers every way this can fail and deliberately does not separate them: nothing
     * listening, something listening that is not us, a build too old to answer, a truncated read.
     * Every one of them means the same thing to the caller - there is no host to be had this way -
     * and the version check inside the decoder is what keeps the third from being mistaken for a
     * host that simply spoke oddly.
     */
    fun ask(address: String, port: Int = PORT, timeoutMillis: Int = ASK_TIMEOUT_MILLIS): PairingCode? =
        runCatching {
            Socket().use {
                it.connect(InetSocketAddress(address, port), timeoutMillis)
                it.soTimeout = timeoutMillis
                val text = it.getInputStream().readBytes().decodeToString()
                PairingCodeCodec.decode(text)
            }
        }.getOrNull()

    /**
     * The handset or router this one is reached through, or null where there is no telling.
     *
     * On a hotspot this is the handset serving it, which is the whole reason this file exists. On
     * a router it is the router, which answers nothing on [PORT] and so costs one refused connect
     * and changes nothing - there is no need to know which kind of network this is, and no way to
     * know it reliably, so nothing here tries.
     */
    fun gatewayOf(context: Context): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        manager.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress
    }.getOrNull()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var accepting: Thread? = null

    /** Its own port rather than a word on an existing channel, so no handshake anywhere changes. */
    const val PORT = 45129

    private const val ASK_TIMEOUT_MILLIS = 900
    private const val JOIN_TIMEOUT_MILLIS = 500L
}
