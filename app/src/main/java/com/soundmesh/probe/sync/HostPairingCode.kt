package com.soundmesh.probe.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

import com.soundmesh.core.PairingCode
import com.soundmesh.core.PairingCodeCodec

/**
 * The code this handset offers a peer, and which network it is good on.
 *
 * One place, because three screens show it: a run shows it while it plays, the home screen shows
 * it, and [ShowCodeActivity] shows it standing still. Two spellings of the same payload would
 * eventually differ, and the one thing a scanned code cannot afford is to be almost right.
 */
object HostPairingCode {
    /** A code, and the network a phone has to be on for it to connect to anything. */
    data class Offer(val payload: String, val by: LocalAddress.ReachedBy)

    /**
     * The code to show, or null when this handset cannot name one address a peer would reach.
     *
     * [prefer] settles it when the handset has its access point up and is joined to a network at
     * the same time - two addresses, and no way for this app to know which one the phone doing
     * the scanning can see. Null when nobody has said, which is the refusal [LocalAddress] has
     * always made in that case.
     *
     * The network comes back with the payload rather than being worked out again by each screen.
     * Which one a code is good on is the thing that makes a wrong pick visible: a code labelled
     * with the network it names is one somebody can look at and say "that is not the one we are
     * on", where an unlabelled one just scans cleanly and then connects to nothing.
     */
    fun offer(
        context: Context,
        hostId: String,
        chunkPort: Int,
        prefer: LocalAddress.ReachedBy? = null
    ): Offer? {
        val own = LocalAddress.own()
        val joined = joinedWifiAddress(context)
        val address = LocalAddress.choose(own, joined, prefer) ?: return null
        val by = LocalAddress.reachable(own, joined).first { it.address == address }.by
        return Offer(PairingCodeCodec.encode(PairingCode(hostId, address, chunkPort)), by)
    }

    /**
     * Every address a peer in the room could reach, each labelled with how.
     *
     * For the screen that offers the choice rather than for making it: with one entry there is
     * nothing to choose, and a control that cannot change the answer is worse than none.
     */
    fun choices(context: Context): List<LocalAddress.Reachable> =
        LocalAddress.reachable(LocalAddress.own(), joinedWifiAddress(context))

    /** Null when this handset cannot name one address a peer in the room would reach. */
    fun of(
        context: Context,
        hostId: String,
        chunkPort: Int,
        prefer: LocalAddress.ReachedBy? = null
    ): String? = offer(context, hostId, chunkPort, prefer)?.payload

    /**
     * The IPv4 of whatever network the system calls WiFi, or null when this handset joined none.
     *
     * Every network rather than the active one. A handset with mobile data in front of a joined
     * WiFi has the mobile one as its active network, and the WiFi address is still the one a peer
     * in the room would use - reading only the active network would call that address this
     * handset's own access point and hand out the wrong code.
     *
     * No permission at all, which is the whole reason it is asked here and not of WifiManager:
     * that one needs ACCESS_FINE_LOCATION and live location services since Android 10, and this
     * app asks for neither.
     */
    private fun joinedWifiAddress(context: Context): String? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.allNetworks.firstNotNullOfOrNull { network ->
            val wifi = connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            if (!wifi) null else connectivity.getLinkProperties(network)
                ?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstNotNullOfOrNull { it.hostAddress }
        }
    }.getOrNull()
}
