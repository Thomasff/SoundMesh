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
 * Two candidates with nothing to tell them apart is a refusal rather than a guess, on the same
 * grounds as two hosts in discovery: the wrong pick produces a code that scans cleanly and then
 * cannot be connected to, with nothing in the run to say why. A host that cannot name itself
 * simply shows no code and stays findable over mDNS.
 *
 * What changed on 2026-09-15 is that two candidates stopped being unanswerable. Android 11 lets a
 * handset run its access point and stay joined to a network at the same time, and these handsets
 * do - so a person with a hotspot up for some phones and WiFi for the rest had two addresses and
 * saw no code at all. The app still cannot know which network the phone doing the scanning is on,
 * so it does not guess: [reachable] says which address is which, and somebody answers.
 */
object LocalAddress {
    /**
     * Interface names one handset reaches another over. Mobile data is private too, hence names.
     * Matched as prefixes, so `softap` has to be listed beside `ap`: this tablet calls its access
     * point `softap0`, which `ap` does not begin, and a host that cannot name itself shows no code.
     */
    private val WIRELESS = listOf("wlan", "ap", "softap", "swlan", "p2p", "eth")

    /**
     * How a peer would get to one of this handset's addresses.
     *
     * Deliberately not read off the interface name. The obvious rule - `wlan` means joined, `ap`
     * means hotspot - is wrong on the first handset this project ever measured: the X10 runs its
     * access point on `wlan0`. See [reachable] for what is asked instead.
     */
    enum class ReachedBy {
        /** This handset is the access point, and a peer joins it. */
        HOTSPOT,

        /** This handset joined somebody else's network, and a peer has to be on the same one. */
        WIFI
    }

    /** One address a peer could reach, and which of the two networks it is on. */
    data class Reachable(val address: String, val by: ReachedBy)

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

    /**
     * The addresses a peer in the room could reach, each labelled with how.
     *
     * [joinedAddress] is the IPv4 of whatever network ConnectivityManager calls WiFi, or null when
     * this handset has not joined one. That is the whole of the classification: the address on the
     * joined network is the joined one, and any other wireless address is this handset's own
     * access point. Asked of the system rather than inferred from names, because names do not
     * carry it - see [ReachedBy].
     *
     * Null [joinedAddress] therefore labels everything HOTSPOT, which is right when there is one
     * address and a refusal when there are two: see [choose].
     */
    fun reachable(own: List<Pair<String, String>>, joinedAddress: String?): List<Reachable> =
        own.filter { (name, _) -> WIRELESS.any { name.startsWith(it) } }
            .map { (_, address) -> address }
            .distinct()
            .map { Reachable(it, if (it == joinedAddress) ReachedBy.WIFI else ReachedBy.HOTSPOT) }

    /**
     * The one a peer in the same room would reach, or null when this handset cannot say.
     *
     * One candidate answers itself, and [prefer] does not make it disappear: a handset with only
     * its hotspot up is reachable at that address whatever anybody asked for, and showing no code
     * because of a setting would be worse than showing the only code there is.
     *
     * More than one is answered only when [prefer] picks out exactly one of them. Two that both
     * look like this handset's own access point - which is what a null [joinedAddress] leaves -
     * cannot be told apart by anything here, and a phone cannot be its own access point twice.
     */
    fun choose(
        own: List<Pair<String, String>>,
        joinedAddress: String? = null,
        prefer: ReachedBy? = null
    ): String? {
        val candidates = reachable(own, joinedAddress)
        candidates.singleOrNull()?.let { return it.address }
        if (prefer == null) return null
        return candidates.filter { it.by == prefer }.singleOrNull()?.address
    }
}
