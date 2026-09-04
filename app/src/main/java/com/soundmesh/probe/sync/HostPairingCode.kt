package com.soundmesh.probe.sync

import com.soundmesh.core.PairingCode
import com.soundmesh.core.PairingCodeCodec

/**
 * The code this handset offers a peer.
 *
 * One place, because two screens show it: a run shows it while it plays, and [ShowCodeActivity]
 * shows it standing still. Two spellings of the same payload would eventually differ, and the one
 * thing a scanned code cannot afford is to be almost right.
 */
object HostPairingCode {
    /** Null when this handset cannot name one address a peer in the room would reach - see [LocalAddress]. */
    fun of(hostId: String, chunkPort: Int): String? =
        LocalAddress.choose(LocalAddress.own())?.let { address ->
            PairingCodeCodec.encode(PairingCode(hostId, address, chunkPort))
        }
}
