package com.soundmesh.probe.sync

import android.content.Context
import com.soundmesh.core.HostId

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
    }

    /** Gives back [who]'s hold. The record goes when the last holder does. */
    @Synchronized
    fun release(who: Holder) {
        if (!holders.remove(who)) return
        if (holders.isNotEmpty()) return
        withdraw()
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

    private fun register() {
        val context = application ?: return
        val id = hostId ?: return
        open = runCatching {
            PeerDiscovery(context).register("$SERVICE_NAME_PREFIX-$id", SyncActivity.CHUNK_PORT, id)
        }.getOrNull()
    }

    private fun withdraw() {
        val held = open ?: return
        open = null
        runCatching { held.close() }
    }

    /** What the service is called on the network. The identity is the attribute, not this. */
    const val SERVICE_NAME_PREFIX = "SoundMesh"
}
