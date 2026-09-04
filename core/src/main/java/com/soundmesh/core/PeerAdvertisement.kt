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
    val compatible: Int
)

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
     * Picks the host to connect to, or names why there isn't one.
     *
     * Two usable hosts is a refusal rather than a tie broken by arrival order. On a shared network
     * the quiet pick joins a stranger's session and nothing in the run would say so; a person who
     * meant a particular handset has to be able to say which, and that is what a scanned code does
     * that no amount of discovery can.
     */
    fun choose(candidates: List<DiscoveredPeer>): DiscoveryOutcome {
        val compatible = candidates.filter { isCompatible(it.attributes) }
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
            compatible = compatible.size
        )
    }
}
