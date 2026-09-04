package com.soundmesh.probe.sync

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Which of this handset's own addresses to put in the code a peer scans.
 *
 * Not a question mDNS ever had to answer: there the sink resolves the host's address off the
 * network, so the host never has to know what it looks like from outside. A scanned code carries
 * the address itself, so the host has to pick one - and a phone acting as the access point has
 * several, of which the mobile data one is private too and reaches nothing in the room.
 *
 * Two candidates is a refusal rather than a guess, on the same grounds as two hosts in discovery:
 * the wrong pick produces a code that scans cleanly and then cannot be connected to, with nothing
 * in the run to say why. A host that cannot name itself simply shows no code and stays findable
 * over mDNS.
 */
object LocalAddress {
    /** Interface names one handset reaches another over. Mobile data is private too, hence names. */
    private val WIRELESS = listOf("wlan", "ap", "swlan", "p2p", "eth")

    /** Every interface name and IPv4 address this handset currently has up. */
    fun own(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { address -> address.hostAddress?.let { nif.name to it } }
            }
            .toList()
    }.getOrDefault(emptyList())

    /** The one a peer in the same room would reach, or null when this handset cannot say. */
    fun choose(own: List<Pair<String, String>>): String? =
        own.filter { (name, _) -> WIRELESS.any { name.startsWith(it) } }
            .distinctBy { (_, address) -> address }
            .singleOrNull()
            ?.second
}
