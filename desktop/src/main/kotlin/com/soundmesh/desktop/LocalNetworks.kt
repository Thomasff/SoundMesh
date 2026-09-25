package com.soundmesh.desktop

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One of this machine's LAN addresses, the adapter Windows names it by, and its network's prefix. */
data class OwnAddress(val address: String, val adapter: String, val prefixLength: Int)

/**
 * Which networks this machine is on, and whether an address is on one of them - the handset's
 * network lines and its JoinTrouble.OTHER_NETWORK, worked out alone and without sending a packet.
 */
object LocalNetworks {
    /**
     * Every interface that is up, a tunnel's included: which one the other machine shares is not
     * something this side can tell. Private addresses only, as on the handset.
     */
    fun list(): List<OwnAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.interfaceAddresses
                    .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
                    .map { OwnAddress(it.address.hostAddress, nic.displayName ?: nic.name, it.networkPrefixLength.toInt()) }
            }
    }.getOrDefault(emptyList())

    /**
     * What a handset is shown to reach this machine by: [list], cut to the adapter the way out
     * goes through. The others are most often a virtual machine's network (VirtualBox, Hyper-V),
     * which no handset is on, and the first one listed is the one the code carries.
     */
    fun toOffer(): List<OwnAddress> = offered(list(), outward())

    /** [all] cut to the one at [outward], or all of them when that is unknown or none of them. */
    fun offered(all: List<OwnAddress>, outward: String?): List<OwnAddress> =
        all.filter { it.address == outward }.ifEmpty { all }

    /**
     * The address this machine would send from to reach beyond its networks, or null. A UDP
     * socket's connect sends nothing; it only asks the routing table. Null for a tunnel's (no
     * hardware address): a VPN that takes all traffic is the way out, and no handset is on it.
     */
    private fun outward(): String? = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName(BEYOND), 9)
            val local = socket.localAddress
            val nic = NetworkInterface.getByInetAddress(local)
            if (local.isAnyLocalAddress || nic?.hardwareAddress == null) null else local.hostAddress
        }
    }.getOrNull()

    // Any address past every private network: only the route to it is looked up.
    private const val BEYOND = "8.8.8.8"

    /**
     * True only when [address] is a dotted IPv4 address outside every network in [own].
     *
     * A fact about addresses and not a verdict on reaching it: a router or a tunnel can carry a
     * packet to another network, so this is said as a warning, never as a refusal. A name, a typo,
     * no networks of our own or a prefix that makes no sense answer false - none of them is
     * evidence of anything.
     */
    fun elsewhere(address: String, own: List<OwnAddress>): Boolean {
        val target = ipv4Of(address) ?: return false
        val known = own.mapNotNull { mine ->
            val self = ipv4Of(mine.address) ?: return@mapNotNull null
            if (mine.prefixLength !in 1..32) return@mapNotNull null
            val mask = if (mine.prefixLength == 32) -1 else (-1 shl (32 - mine.prefixLength))
            (self and mask) == (target and mask)
        }
        return known.isNotEmpty() && known.none { it }
    }

    private fun ipv4Of(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val octet = part.toInt()
            if (octet > 255) return null
            value = (value shl 8) or octet
        }
        return value
    }
}
