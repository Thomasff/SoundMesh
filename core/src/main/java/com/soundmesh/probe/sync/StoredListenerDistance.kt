package com.soundmesh.probe.sync

import com.soundmesh.core.HostId
import java.io.File

/**
 * How far the listener was from one handset, the last time an overhead round measured it.
 *
 * The listener is the one thing in the room that has never been measured. Every distance this
 * system takes is handset to handset, so the drawing has had to assume the person is in the middle
 * of the phones - and every number playback reads is measured from the person, not from the middle.
 * An overhead round is what closes that: somebody holds one handset above their head, the room runs
 * an ordinary distance round, and what that handset measured is where the head was.
 *
 * Above the head rather than at the ear on purpose. At the ear, the head itself blocks the direct
 * sound from the handsets behind it, and the reading is taken at the first arrival - so a blocked
 * direct path is answered by a reflection and reads long. Held up, the path is clear and the only
 * cost is height, which 09-11 put at 1.6 cm over two metres against a 34 cm budget.
 *
 * Kept apart from [StoredSeparation] because it is a different fact under the same two names. That
 * file says where a handset stands; this one says where a person sat, measured in a round where one
 * handset was deliberately somewhere it will not be when it plays. Written to the same name, the
 * overhead round would erase the round that measured where the handset actually is - the two differ
 * in a dimension the file name did not carry.
 *
 * Two of these are the minimum that says anything: one distance to the listener is a circle around
 * one handset, and which point on it gets picked would come from the drawing. An overhead round
 * produces one per *other* handset, so a room of two handsets can never produce a usable set - the
 * handset being held is the one whose distance to the listener nothing can measure.
 */
class StoredListenerDistance(private val directory: File, private val peerId: String) {
    init {
        // The peer id can arrive off a scanned screen, and it is the file name.
        require(HostId.isValid(peerId)) { "unusable peer id" }
    }

    /** Metres, or null if no overhead round has ever measured this handset from the listener. */
    fun read(): Double? {
        val file = file()
        if (!file.isFile) return null
        return runCatching { file.readText().trim().toDouble() }
            .getOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * The most recent overhead round, replacing whatever was there.
     *
     * Replaced rather than averaged, for the reason a separation is: this describes where somebody
     * was sitting, and where they sat last week is not evidence about where they are sitting now.
     */
    fun write(metres: Double) {
        if (!metres.isFinite() || metres <= 0.0) return
        file().writeText(metres.toString())
    }

    /** Thrown away when the room changed under it, which only the person can tell us. */
    fun forget() {
        runCatching { file().delete() }
    }

    private fun file() = File(directory, "$FILE_PREFIX$peerId")

    companion object {
        const val FILE_PREFIX = "listener-edge-m-"

        /** Every handset an overhead round has measured from the listener, in this directory. */
        fun all(directory: File): Map<String, Double> {
            val files = directory.listFiles() ?: return emptyMap()
            val metres = LinkedHashMap<String, Double>()
            for (file in files.sortedBy { it.name }) {
                val peerId = file.name.removePrefix(FILE_PREFIX)
                if (peerId == file.name || !HostId.isValid(peerId)) continue
                StoredListenerDistance(directory, peerId).read()?.let { metres[peerId] = it }
            }
            return metres
        }
    }
}
