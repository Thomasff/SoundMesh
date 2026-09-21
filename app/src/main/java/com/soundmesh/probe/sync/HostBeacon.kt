package com.soundmesh.probe.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.soundmesh.core.HostId
import com.soundmesh.session.NetworkFootprint

/**
 * This handset's mDNS record, held for as long as it is a host.
 *
 * It used to belong to the session, and that was the whole reason a sink had to be shown a code:
 * a host that had picked its role but not yet pressed play said nothing on the network, so there
 * was nothing on the network to find. Standing by is the state a room spends nearly all of its
 * time in, and it is exactly the state somebody walks into the room during.
 *
 * Two things want it at once and neither knows about the other: the role outlives the session, the
 * session outlives the screen, and whichever ends first must not take the record away from the one
 * still running. So it is held by name rather than counted - see [Holder]. Counting was the
 * obvious thing and is wrong here, because the screen's hold is re-taken on every single resume:
 * a count would climb all evening and the record would survive the role being given up.
 *
 * One registration either way. The same service name registered twice is renamed by the platform,
 * and a renamed record is a host a sink cannot recognise by name afterwards.
 */
object HostBeacon {
    /** Who is holding the record. One hold each, however many times either of them asks. */
    enum class Holder {
        /** Somebody picked 当主机 on this handset and has not picked anything else since. */
        ROLE,

        /** A session is actually running here. Outlives the screen, so it holds its own. */
        SESSION
    }

    private val holders = HashSet<Holder>()
    private var open: AutoCloseable? = null
    private var application: Context? = null
    private var hostId: String? = null

    /** The network watch that keeps the record honest, held for as long as a record is. */
    private var watching: ConnectivityManager.NetworkCallback? = null

    /** The network the record was last said on, so a second reading of it is not a move. */
    private var footprint: NetworkFootprint? = null

    /**
     * Says this handset is a host until [who] gives it back, and again is the same as once.
     *
     * A failure to register is not reported anywhere and not fatal. What it costs is the finding,
     * not the session: the code on the host's own screen reaches exactly the same socket, which is
     * why that code stays on the screen rather than becoming a thing somebody has to go and find.
     */
    @Synchronized
    fun hold(context: Context, hostId: String, who: Holder) {
        if (!HostId.isValid(hostId)) return
        if (!holders.add(who)) return
        if (open != null) return
        this.application = context.applicationContext
        this.hostId = hostId
        register()
        watch()
    }

    /** Gives back [who]'s hold. The record goes when the last holder does. */
    @Synchronized
    fun release(who: Holder) {
        if (!holders.remove(who)) return
        if (holders.isNotEmpty()) return
        stopWatching()
        withdraw()
        // Here and not in [withdraw], which [again] also calls. The record names an address and
        // has to be said again when that changes; this is a port, and a port does not move when
        // the address under it does. Cycling it on every network change would only make this
        // handset briefly unanswerable to the handsets it is serving.
        HostAtTheGateway.stop()
        application = null
        hostId = null
    }

    /**
     * Says the record again because this handset's address has changed under it.
     *
     * Nothing if nobody is holding one. A record naming an address the handset no longer has is
     * worse than no record: a sink resolves it, opens a socket to nowhere, and waits out a
     * connect timeout before it can even start looking again.
     */
    @Synchronized
    fun again() {
        if (open == null) return
        withdraw()
        register()
    }

    /** Whether there is a record on the network right now, for a test and for a timeline line. */
    @Synchronized
    fun advertising(): Boolean = open != null

    /**
     * Watches this handset's own network for as long as it is saying it is a host.
     *
     * Here rather than in whatever asked for the record, and that is the fault this closes. The
     * watch used to belong to the session, so it existed only while something was playing - and
     * standing by is where a room spends nearly all of its time. A host that picked its role and
     * then switched to its hotspot went on advertising the address it had before, and a sink
     * looking for it resolved that record, read "the host is still where it was", and changed
     * nothing: the address in the answer matched the dead address it was already dialling. The
     * sink's half of this was fixed on 2026-09-19 and did not help on its own, because the two
     * halves are one feature and this is the half that was easy to leave out.
     *
     * The first reading is kept rather than acted on: registering delivers the current network,
     * and treating that as a move would withdraw and re-say the record the instant it was taken.
     */
    private fun watch() {
        if (watching != null) return
        val manager = application?.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                moved(
                    NetworkFootprint(
                        handle = network.networkHandle,
                        addresses = properties.linkAddresses.mapNotNull { it.address.hostAddress }.toSet()
                    )
                )
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { watching = callback }
    }

    /** One reading of the network, judged against the last one. Synchronised with everything else. */
    @Synchronized
    private fun moved(now: NetworkFootprint) {
        val before = footprint
        footprint = now
        if (before == null || !before.movedTo(now)) return
        again()
    }

    private fun stopWatching() {
        val callback = watching ?: return
        watching = null
        footprint = null
        val manager = application?.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun register() {
        val context = application ?: return
        val id = hostId ?: return
        open = runCatching {
            PeerDiscovery(context).register("$SERVICE_NAME_PREFIX-$id", SyncActivity.CHUNK_PORT, id)
        }.getOrNull()
        // Said here rather than anywhere else because it says the same thing this record does,
        // and two places saying "this handset is a host" would be two places to forget to stop.
        // Idempotent, so the re-say [again] does costs nothing - see [release] for why the
        // stop is not the mirror of this call.
        HostAtTheGateway.answer(id, SyncActivity.CHUNK_PORT)
    }

    private fun withdraw() {
        val held = open ?: return
        open = null
        runCatching { held.close() }
    }

    /** What the service is called on the network. The identity is the attribute, not this. */
    const val SERVICE_NAME_PREFIX = "SoundMesh"
}
