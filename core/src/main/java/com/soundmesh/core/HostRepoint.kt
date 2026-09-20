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

    /**
     * The same, except that the stored handset's record was still answering and was ignored.
     *
     * Only [HostRepoint.ofUnreachable] reaches this, and only where the record answers and the
     * port behind it does not. Kept apart from [REPLACED] because the two send whoever reads the
     * timeline to different places: that one is a host that left the network, this one is a
     * registration outliving the handset that made it.
     */
    REPLACED_A_GHOST,

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

    /**
     * The same read, made by a handset that cannot reach the host it has stored.
     *
     * Which is every caller there is: this is asked only once the line to that address has been
     * down long enough to go looking. Given that, [Repointing.STILL_THERE] is a record and not a
     * handset. Giving a registration back is a request to a platform daemon rather than an act,
     * and on 2026-09-21 a handset that had handed the role over went on being answered for
     * between 52 and 73 seconds. The one sink that stayed a sink across that handover sat
     * through five consecutive STILL_THERE looks, ten seconds apart, and the room was silent
     * for the whole minute.
     *
     * [stillServing] is what settles it, and the reason this is not simply "the line is down":
     * a line can be down because this handset's own radio hiccuped while its host is serving
     * perfectly well, and striking the record out on that would hand the room to a stranger.
     * The question it answers is the one a record cannot - see RoomCommands.stillServing, which
     * dials the port that is open exactly while a handset is the host. Asked lazily and only in
     * the one case where the answer can change anything, because it waits on a network.
     *
     * The record is struck out for exactly one purpose, taking the one host that is here. Where
     * that buys nothing the verdict is the one actually seen: "nothing answered" and "something
     * answered and it is a ghost" are read by somebody looking for two different faults.
     *
     * [Repointing.MOVED] is untouched, and has to be: a host that moved looks exactly like this
     * from here - the line down, a record answering - and following it is what this file is for.
     * Only a record answering from the address already failed at can be a ghost.
     */
    fun ofUnreachable(
        stored: PairingCode,
        hosts: List<DiscoveredPeer>,
        stillServing: (PairingCode) -> Boolean
    ): Repoint {
        val what = of(stored, hosts)
        if (what.verdict != Repointing.STILL_THERE) return what
        val without = of(stored, hosts.filterNot { PeerAdvertisement.hostIdOf(it) == stored.hostId })
        if (without.verdict != Repointing.REPLACED) return what
        if (stillServing(stored)) return what
        return Repoint(Repointing.REPLACED_A_GHOST, without.host)
    }
}
