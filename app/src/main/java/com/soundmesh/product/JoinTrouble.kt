package com.soundmesh.product

import androidx.annotation.StringRes
import com.soundmesh.probe.R

/** This handset's own IPv4 address, and how much of it names the network it is on. */
data class IpSubnet(val address: String, val prefixLength: Int)

/**
 * Why this handset cannot reach the host it is paired with, as far as it can tell alone.
 *
 * The point of doing it alone: everything here has to be worked out by the handset that cannot get
 * through, so nothing may depend on getting through. The pairing code already carries the host's
 * address, and this handset knows its own, which is enough for the two answers worth having.
 *
 * What this replaces is a timeout. A person on a network that forbids this sees the same nothing as
 * a person whose host has simply closed the app, and there is no way to tell from the screen which
 * of the two they are - so neither of them does the thing that would fix it.
 */
enum class JoinTrouble {
    /** Not on WiFi at all. Mobile data cannot reach a phone on the other side of a router. */
    NO_WIFI,

    /**
     * On WiFi, but the host's address is not on this handset's network.
     *
     * A fact about two addresses rather than a guess: no packet needs to be sent to know it. It is
     * what a guest network looks like, what a router that splits 2.4 and 5 GHz into two segments
     * looks like, and what two different WiFis look like.
     */
    OTHER_NETWORK,

    /**
     * Same network, still cannot get through.
     *
     * Deliberately not called isolation. Two causes end here and this handset can separate neither:
     * the network may forbid its clients to talk to each other, or the host may simply not have the
     * app open. So the wording names both and gives the one action that fixes either - the host's
     * own hotspot, where the host is the access point rather than another client of it.
     */
    CANNOT_REACH
}

/**
 * [mine] is null when this handset's own address could not be read, and [hostAddress] is null when
 * nothing has been paired yet. Both answer null rather than guessing: there is already a line on
 * screen for "not paired", and an unreadable address is not evidence of anything.
 */
internal fun joinTrouble(onWifi: Boolean, mine: IpSubnet?, hostAddress: String?): JoinTrouble? {
    if (hostAddress.isNullOrBlank()) return null
    if (!onWifi) return JoinTrouble.NO_WIFI
    if (mine == null) return null
    val host = ipv4Of(hostAddress) ?: return null
    val self = ipv4Of(mine.address) ?: return null
    if (mine.prefixLength !in 1..32) return null
    val mask = if (mine.prefixLength == 32) -1 else (-1 shl (32 - mine.prefixLength))
    return if ((host and mask) == (self and mask)) JoinTrouble.CANNOT_REACH
    else JoinTrouble.OTHER_NETWORK
}

/** What to say about it. [JoinTrouble.OTHER_NETWORK] takes the two addresses. */
@StringRes
internal fun joinWording(trouble: JoinTrouble): Int = when (trouble) {
    JoinTrouble.NO_WIFI -> R.string.join_no_wifi
    JoinTrouble.OTHER_NETWORK -> R.string.join_other_network
    JoinTrouble.CANNOT_REACH -> R.string.join_cannot_reach
}

/**
 * Dotted quad to the number it means, or null for anything that is not one.
 *
 * Its own parser rather than InetAddress, because this has to run on the JVM under the tests that
 * describe it, and because InetAddress.getByName on a name rather than an address would go and ask
 * a resolver - on the main thread, on a network that has already been established to be broken.
 */
private fun ipv4Of(text: String): Int? {
    val parts = text.trim().split('.')
    if (parts.size != 4) return null
    var packed = 0
    for (part in parts) {
        val byte = part.toIntOrNull() ?: return null
        if (byte !in 0..255) return null
        packed = (packed shl 8) or byte
    }
    return packed
}
