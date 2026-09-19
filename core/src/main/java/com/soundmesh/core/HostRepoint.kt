package com.soundmesh.core

/**
 * What a look at the network says about the host a sink is pointed at.
 *
 * Three of these five change nothing, and that is deliberate: a sink dialling an address that does
 * not answer is a phone standing idle, while a sink pointed at the wrong handset is one on somebody
 * else's timeline, and only the second of those is silent.
 */
enum class Repointing {
    /** It answered from the address it was stored at. Whatever is wrong is not the address. */
    STILL_THERE,

    /** The same handset, answering from somewhere else. */
    MOVED,

    /** A different handset, because the stored one is not on this network and exactly one other is. */
    REPLACED,

    /** Nothing this build can talk to answered, so the stored host stays stored. */
    NOBODY,

    /** More than one other host answered, and picking takes whichever answered first. */
    TOO_MANY
}

/** A verdict, and the code to store when there is one to store. */
data class Repoint(val verdict: Repointing, val host: PairingCode?)

/**
 * Re-reading a stored pairing against what is actually on the network.
 *
 * A pairing code carries three fields and only [PairingCode.hostId] is an identity. The address
 * and the port are where that handset was standing when somebody held a phone up to its screen,
 * and a host that joins a different network, turns its hotspot on, or is simply handed a new lease
 * has changed both without having changed at all. Until this existed the address was what a sink
 * dialled for ever after: the whole point of the stored file is that nobody has to scan anything
 * twice, and the cost of that was a sink that could never notice its host had moved.
 *
 * Kept pure and kept here so the decision can be read and tested without a network under it. What
 * the caller supplies is the compatible list out of [DiscoveryOutcome.hosts] - version-checked and
 * with a usable id - which is why [PeerAdvertisement.hostIdOf] is safe on every entry.
 */
object HostRepoint {
    fun of(stored: PairingCode, hosts: List<DiscoveredPeer>): Repoint {
        // The stored handset first and on its own, before anything is counted. A person choosing
        // which phone to follow outranks whatever else is advertising, including in the state
        // where two hosts are up and the room is already in trouble - that is the moment it would
        // be worst to quietly move somebody onto the other one.
        hosts.firstOrNull { PeerAdvertisement.hostIdOf(it) == stored.hostId }?.let { same ->
            val at = PairingCode(stored.hostId, same.hostAddress, same.port)
            return if (at == stored) Repoint(Repointing.STILL_THERE, null)
            else Repoint(Repointing.MOVED, at)
        }
        // Nothing answered is not the same as the host having gone. mDNS on a network that filters
        // multicast between clients answers exactly as an empty network does, and that is common
        // enough - a guest network, a campus one - that treating silence as absence would unpair
        // handsets on the networks where discovery was never going to work in the first place.
        val other = hosts.singleOrNull()
            ?: return Repoint(if (hosts.isEmpty()) Repointing.NOBODY else Repointing.TOO_MANY, null)
        return Repoint(
            Repointing.REPLACED,
            PairingCode(PeerAdvertisement.hostIdOf(other), other.hostAddress, other.port)
        )
    }
}
