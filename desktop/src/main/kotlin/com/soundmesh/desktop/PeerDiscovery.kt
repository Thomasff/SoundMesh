package com.soundmesh.desktop

import com.soundmesh.core.DiscoveryOutcome
import com.soundmesh.core.PeerAdvertisement

/**
 * Finding the other end without being told its address - this machine's half of what the handsets
 * do with NsdManager, speaking the same record so either side can find the other.
 *
 * Same shape as the handset's on purpose: the full window is spent, every answer is resolved, and
 * which one to dial is core's [PeerAdvertisement.choose], so a desktop sink refuses two hosts for
 * the same reason a handset does rather than for one of its own.
 */
object PeerDiscovery {

    /**
     * Advertises this machine as a host until the returned handle is closed.
     *
     * The port in the record is the chunk port, the one a sink dials after resolving it, as on the
     * handsets.
     */
    fun register(serviceName: String, port: Int, hostId: String): AutoCloseable =
        DnsSd.register(
            "$serviceName.$SERVICE_DOMAIN", "${hostName()}.local", port, PeerAdvertisement.attributes(hostId)
        )

    /** Listens for hosts for the whole of [windowMillis], then resolves what answered. */
    fun discover(windowMillis: Int): DiscoveryOutcome {
        // One resolve at a time, as on the handsets: each is a wait of its own, and there are only
        // ever a handful of names.
        val resolved = DnsSd.browse(SERVICE_DOMAIN, windowMillis).mapNotNull {
            DnsSd.resolve(it, RESOLVE_TIMEOUT_MILLIS)
        }
        return PeerAdvertisement.choose(resolved)
    }

    /**
     * The name the responder already answers to on every interface.
     *
     * Windows answers `<computer name>.local` by itself, which is what lets the record carry no
     * address of its own.
     */
    private fun hostName(): String = System.getenv("COMPUTERNAME") ?: error("COMPUTERNAME is not set")

    private const val SERVICE_DOMAIN = "${PeerAdvertisement.SERVICE_TYPE}.local"
    private const val RESOLVE_TIMEOUT_MILLIS = 5_000L
}
