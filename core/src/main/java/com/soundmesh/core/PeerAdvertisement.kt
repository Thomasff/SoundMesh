package com.soundmesh.core

/** One service the network answered with, resolved to somewhere a socket can be opened. */
data class DiscoveredPeer(
    val name: String,
    val hostAddress: String,
    val port: Int,
    val attributes: Map<String, String?>
)

/** Why discovery produced no host to connect to. */
enum class DiscoveryFailure {
    /** Nothing of this service type answered. */
    NOTHING_FOUND,

    /** Something answered, but nothing speaking this build's protocol. */
    NO_COMPATIBLE_VERSION,

    /** More than one usable host. Picking one silently would join whichever answered first. */
    AMBIGUOUS
}

/** What discovery came to, with enough of the count to say why. */
data class DiscoveryOutcome(
    val peer: DiscoveredPeer?,
    val failure: DiscoveryFailure?,
    val seen: Int,
    /**
     * Every host this build could talk to, including the one [peer] picked out.
     *
     * The list rather than only the count, because one caller wants the answer [peer] refuses to
     * give: a handset checking whether it may become the host is asking "is there another one",
     * and [DiscoveryFailure.AMBIGUOUS] - two of them - is the loudest possible yes while [peer] is
     * null for it. The refusal is right for joining a room and wrong for counting one.
     */
    val hosts: List<DiscoveredPeer>,
    /**
     * How many were heard and could not be resolved into an address, which [failure] counts as
     * nothing having answered. Said apart because the two are found in different places: a network
     * that carries no multicast, against a resolve stuck inside the handset that is looking.
     */
    val unresolved: Int = 0
) {
    /** How many of [seen] this build could actually talk to. */
    val compatible: Int get() = hosts.size
}

/**
 * What a host puts on the wire so a sink can find it without being told an address, and what a
 * sink checks before trusting what it found.
 *
 * The transport is mDNS: no server, no configuration. Every device joins one multicast group and
 * a question asked there is answered by whichever device owns the name. It is the fast path when
 * both handsets are already on the same network, and it is structurally unable to be more than
 * that - it cannot put them on the same network, and it cannot prove which answer is the handset
 * in your hand. Both of those are the scanned code's job.
 *
 * The attributes carry the version and the host's identity. The three ports are compile-time
 * constants shared by both sides, and the one the service record itself carries is the chunk port,
 * which the sink then uses rather than re-deriving - so the record is load bearing rather than
 * decorative.
 */
object PeerAdvertisement {
    const val SERVICE_TYPE = "_soundmesh._tcp"
    const val VERSION_KEY = "v"

    /**
     * Which handset this is, stable across runs - see [HostId].
     *
     * In the record rather than only in the scanned code because the sink needs it on both paths.
     * It is what a peer's remembered calibration is filed under, and a run that found its host over
     * mDNS has to file under the same name as a run that scanned it.
     */
    const val ID_KEY = "id"

    /**
     * Bumped whenever a sink of an older build would mis-handle a newer host.
     *
     * The check this feeds is not politeness. An older build advertising the same service type
     * would be connected to, its chunk frames would decode, and the run would fail somewhere
     * further along where nothing points back at the mismatch.
     *
     * 1 to 2: the record gained [ID_KEY]. An older host is not mis-read by a newer sink so much as
     * unfileable by it - it answers with no name to keep a calibration under, and the newer sink
     * would have to invent one.
     */
    const val PROTOCOL_VERSION = "2"

    fun attributes(hostId: String): Map<String, String> =
        mapOf(VERSION_KEY to PROTOCOL_VERSION, ID_KEY to hostId)

    /**
     * Whether this is a host this build can talk to and remember.
     *
     * The identity is required, not merely read. It travels with the version, so a host with the
     * matching version and no usable id is not an old build but a malformed record, and letting it
     * through would put the run's calibration under whatever the record happened to contain.
     */
    fun isCompatible(attributes: Map<String, String?>): Boolean =
        attributes[VERSION_KEY] == PROTOCOL_VERSION && HostId.isValid(attributes[ID_KEY])

    /** The stable name of a peer that [isCompatible] has already accepted. */
    fun hostIdOf(peer: DiscoveredPeer): String = peer.attributes.getValue(ID_KEY)!!

    /**
     * A host in [hosts] that is not the handset asking, or null.
     *
     * What a handset just told to be the host asks before it accepts, and the whole of the
     * question is the filter: it is already advertising by the time it looks - it has to be, or
     * two people pressing 当主机 at once could not see each other - so its own record comes back
     * in the answer. Without this line every host would find one and stand down, and the app
     * would have no host at all on a network where discovery works perfectly.
     *
     * Taken from the whole list rather than from [choose]'s single pick, which is null for two
     * hosts. Two of them is the loudest possible yes to this question and the one case where
     * [choose] has nothing to say.
     */
    fun otherThan(hosts: List<DiscoveredPeer>, myId: String): DiscoveredPeer? =
        hosts.firstOrNull { hostIdOf(it) != myId }

    /**
     * Picks the host to connect to, or names why there isn't one.
     *
     * Two usable hosts is a refusal rather than a tie broken by arrival order. On a shared network
     * the quiet pick joins a stranger's session and nothing in the run would say so; a person who
     * meant a particular handset has to be able to say which, and that is what a scanned code does
     * that no amount of discovery can.
     */
    fun choose(candidates: List<DiscoveredPeer>): DiscoveryOutcome {
        // By identity, not by record. A name has to be unique on the network, so a handset that
        // registers while its own earlier registration is still being answered is renamed by the
        // platform rather than refused, and both records answer for as long as the old one is
        // cached. Counting those as two hosts refuses the room the only host it has - seventy
        // seconds of it, measured on 09-21, in a flat with two handsets in it. Which of the two
        // is kept is arbitrary, and it has to be: they carry the same identity and nothing here
        // can tell which address is the live one. A sink that ends up on the dead one dials, gets
        // nowhere for twenty seconds and looks again - see HostSearch.lookAgain.
        val compatible = candidates
            .filter { isCompatible(it.attributes) }
            .distinctBy { hostIdOf(it) }
        val failure = when {
            candidates.isEmpty() -> DiscoveryFailure.NOTHING_FOUND
            compatible.isEmpty() -> DiscoveryFailure.NO_COMPATIBLE_VERSION
            compatible.size > 1 -> DiscoveryFailure.AMBIGUOUS
            else -> null
        }
        return DiscoveryOutcome(
            peer = if (failure == null) compatible.single() else null,
            failure = failure,
            seen = candidates.size,
            hosts = compatible
        )
    }
}
